# Tomcat Secure Embed

Apache Tomcat **11.0.27**（2026-10 時点の 11 系最新）の公式ソースから、セキュリティ上不要な機能を**ソースごと削除**して再ビルドした、
組み込み（Embedded）用 Tomcat です。

- 対応: **HTTP/1.1（平文, NIO）**, **Servlet 6.1**, **JSP 4.0**, **EL 6.0**
- 実行時の依存ライブラリ: **ECJ（JSP コンパイラ）のみ** — `org.eclipse.jdt:ecj:3.46.100`（Tomcat 11.0.27 公式と同じ版）
- 1 つの jar（`tomcat-secure-embed`）に core / EL / Jasper / annotations-api を統合

削除は「設定で無効化」ではなく**クラス自体が存在しない**状態にしているため、設定ミスや攻撃者による再有効化はできません。
削除後もコンパイルが通ること（＝参照が残っていないこと）で、削除の完全性を担保しています。

## 削除した機能

| 機能 | 削除したもの |
|---|---|
| HTTP/2 | `org.apache.coyote.http2`（h2 / h2c / prior-knowledge）, HTTP/2 プリフェイス検出 |
| WebSocket / HTTP Upgrade | `org.apache.coyote.http11.upgrade`, `UpgradeProtocol`, `UpgradeToken`。`HttpServletRequest.upgrade()` は常に `ServletException` |
| HTTPS / TLS | `util.net.jsse`, `SSLHostConfig` 等の TLS 関連クラス一式, `SecureNioChannel`, SNI / ALPN 処理 |
| OpenSSL / APR | `tomcat.jni`, `util.net.openssl`, `util.openssl`（FFM）, `AprLifecycleListener`, `OpenSSLLifecycleListener` |
| Apache 連携コネクタ | AJP（`coyote.ajp`）, `LoadBalancerDrainingValve`, コネクタ経由の REMOTE_USER 受け入れ |
| RewriteValve | `catalina.valves.rewrite` |
| Tomcat 独自認証 | `Realm` / `Authenticator` / `CredentialHandler` / `catalina.realm` / `catalina.authenticator`（JASPIC 含む）/ `catalina.users`（tomcat-users.xml）/ Jakarta Authentication API |
| セッション永続化 | JDBC（`DataSourceStore`）, `FileStore`, `PersistentManager`, `PersistentValve`, `StandardManager` の SESSIONS.ser 保存（デシリアライズ）, `CustomObjectInputStream` |
| クラスタリング | `Cluster`, `DistributedManager` |
| JNDI | `org.apache.naming`（`java:comp/env`, `BeanFactory` 等のオブジェクトファクトリ）, `NamingContextListener`, `ContextNamingInfoListener`, JNDI リソース作成用 MBean, `Tomcat.enableNaming()` |
| その他の攻撃面 | NIO2 コネクタ, CGI, WebDAV, Manager / Host-Manager / JMXProxy サーブレット, `JDBCAccessLogValve`, `SSLValve`, server.xml 起動系（`Catalina` / `Bootstrap`）, 自動デプロイ（`HostConfig`）, `MBeanFactory`, EJB / JPA / JAX-WS インジェクション, javax→jakarta 変換, ビルド時ツール |

削除パスの一覧と理由: [tools/removed-paths.txt](tools/removed-paths.txt)

## フェイルクローズ（安全側に倒す）動作

コンテナ認証がないため、それに依存するアプリは**起動を失敗させます**（保護されているつもりの URL を無防備に公開しないため）。

- `web.xml` の `<security-constraint>` / `<login-config>`、`@ServletSecurity`、`ServletRegistration.setServletSecurity()` → コンテキスト起動失敗
- `HttpServletRequest.login()` / `authenticate()` → `ServletException`、`isUserInRole()` → 常に `false`
- `DefaultServlet` の `readonly=false` → 起動失敗（PUT/DELETE によるファイル書き込みは不可。CVE-2025-24813 / CVE-2017-12617 系）
- `ProtocolHandler.create()` は HTTP/1.1 以外を拒否（任意クラス名からのプロトコル生成を廃止）
- 永続化された FORM 認証情報を含むセッションのデシリアライズを拒否
- JNDI 定義（web.xml の `<env-entry>` / `<resource-ref>` / `<resource-env-ref>` / `<ejb-ref>` / `<service-ref>` 等、context.xml の `<Resource>` / `<Environment>` / `<ResourceLink>`、`@Resource`）、`useNaming=true` → コンテキスト起動失敗

認証はアプリケーション側（Servlet Filter、Spring Security 等）で実装してください。
DataSource や設定値は JNDI ではなく、アプリケーション側（Spring Boot の設定、HikariCP の直接生成等）で用意してください。

## 上流ソースへの変更点（デフォルト値の強化）

- `StandardServer` のシャットダウンポート: 既定 `8005` → **無効（-1）**
- `ErrorReportValve`: スタックトレース・サーバーバージョンを**既定で非表示**
- `DefaultServlet`: 常に読み取り専用、partial PUT 無効、サーバー情報非表示

変更差分（58 ファイル）: [patches/tomcat-secure.patch](patches/tomcat-secure.patch)

## 使い方

```xml
<dependency>
  <groupId>org.tamacat</groupId>
  <artifactId>tomcat-secure-embed</artifactId>
  <version>11.0.27-1</version>
</dependency>
```

```java
SecureTomcat server = SecureTomcat.builder()
        .port(8080)
        .address("0.0.0.0")                    // 既定は 127.0.0.1
        .webapp("", Path.of("src/main/webapp")) // web.xml / アノテーション / JSP 対応
        .context("/api", ctx -> {               // プログラムでのサーブレット登録
            Tomcat.addServlet(ctx, "hello", new HelloServlet());
            ctx.addServletMappingDecoded("/hello", "hello");
        })
        .behindTlsProxy(true)                   // TLS 終端プロキシ配下の場合
        .build();
server.start();
server.await();
```

コマンドライン:

```bash
java -cp "tomcat-secure-embed-11.0.27-1.jar:ecj-3.46.100.jar" org.tamacat.tomcat.SecureTomcat --webapp ./webapp --port 8080
```

（Windows ではクラスパス区切りを `;` にしてください）

### `SecureTomcat` が適用する既定値

| 項目 | 既定 |
|---|---|
| バインドアドレス | `127.0.0.1` |
| JMX（MBean 登録） | 無効（`Registry.disableRegistry()`。JVM 内で一度無効化すると再有効化不可） |
| 自動デプロイ / WAR 展開 / シャットダウンポート | すべて無効 |
| エラーページ | スタックトレース・バージョン非表示 |
| URI | エンコード済み `/` `\` を拒否、怪しい URI を拒否、TRACE 無効、バックスラッシュ不可 |
| レスポンスヘッダ | `Server` / `X-Powered-By` なし、`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`（TLS プロキシ時は HSTS も） |
| セッション | Cookie のみ（URL への `;jsessionid=` 埋め込みなし）、`HttpOnly`、`SameSite=Lax`、TLS プロキシ時 `Secure` |
| 起動 | load-on-startup サーブレットの初期化失敗でアプリ起動失敗 |
| 制限 | POST 2MiB、パラメータ 1000、multipart パート 50、ヘッダ 8KiB、タイムアウト 20 秒（すべて変更可） |

HTTPS が必要な場合は、前段のリバースプロキシ / ロードバランサで TLS を終端し、`behindTlsProxy(true)` を指定してください。

## ビルドとテスト

```bash
mvn clean verify
```

（機能を削除した後は `clean` してください。`target/` に古いクラスが残るとテストが検出して失敗します）

テスト（76 件）は、Servlet / JSP / EL / 静的ファイル / セッションの動作に加え、次を検証します。

- HTTP/2 プリフェイス・h2c Upgrade・WebSocket ハンドシェイクが受け付けられないこと、HTTP/2 クライアントが HTTP/1.1 にフォールバックすること
- 削除したクラス・パッケージがビルド成果物に存在しないこと
- セキュリティ制約・ログイン設定・`@ServletSecurity`・書き込み可能 DefaultServlet・JNDI 定義（`<env-entry>` / `<resource-ref>` / context.xml `<Resource>` / `@Resource`）でアプリが起動しないこと、`java:comp/env` が引けないこと
- エラーページに例外内容が出ないこと、セキュリティヘッダ、Cookie 属性、TRACE / PUT / `%2F` の拒否

## Tomcat のバージョンアップ手順

```bash
# 1. 新バージョンの公式ソースを取得（Maven Central、Apache Tomcat KEYS で GPG 署名検証）→ 削除 → パッチ適用
bash tools/import-tomcat.sh 11.0.28
# 2. pom.xml の tomcat.version / ecj.version / version を更新（ECJ は上流の tomcat-embed-jasper の pom に合わせる）
# 3. ビルド・テスト。パッチが当たらない場合は手で修正後、差分を再生成
mvn clean verify
bash tools/make-patch.sh
```

`tools/import-tomcat.sh 11.0.27` を実行すると、現在のソースとバイト単位で同一のものが再生成されます。

## 制限事項・注意


- JSP は実行時に ECJ でコンパイルされます（作業ディレクトリは既定で一時ディレクトリ、停止時に削除）。
- `getTomcat()` で取得した `Tomcat` に対する個別設定は、上記の既定値を上書きできてしまう点に注意してください。

## ライセンス

Apache License 2.0。Apache Tomcat の著作権表示は [NOTICE](NOTICE) を参照してください。
ECJ は Eclipse Public License 2.0 で、別 jar として依存します（同梱していません）。
