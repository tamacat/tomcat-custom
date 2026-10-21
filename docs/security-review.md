# セキュリティレビュー（SAST + 手作業レビュー）

- 実施日: 2026-10-21
- 対象: `develop` ブランチ（Tomcat 11.0.27 ベース）
- ツール: SpotBugs 4.10.4（spotbugs-maven-plugin 4.10.4.1）+ FindSecBugs 1.14.0、effort=Max、threshold=Low

## 再実行方法

```bash
# 自作コード（org.tamacat.*）: 指摘が 1 件でもあればビルド失敗
mvn -Psast clean verify
# Tomcat を含む全体スキャン（レポートのみ）: tomcat-secure-embed/target/spotbugsXml.xml
cd tomcat-secure-embed && mvn -DskipTests package spotbugs:spotbugs
```

`-Psast` のゲートが機能することは、`Runtime.exec` を使う脆弱なクラスを一時的に追加し、
COMMAND_INJECTION でビルドが失敗することで確認済みです。

## 結果サマリ

同じツール・同じ設定で、改変前の上流 Tomcat 11.0.27（core + EL + Jasper + annotations-api のバイナリ）と比較しました。

| | 上流 11.0.27 | 本ビルド | 増減 |
|---|---|---|---|
| 全指摘 | 3,793 | 2,282 | −40% |
| セキュリティ指摘 | 1,257 | 480 | −62% |
| 　優先度 1 | 70 | 18 | −74% |
| 　優先度 2 | 746 | 200 | −73% |
| 　優先度 3 | 441 | 262 | −41% |
| 上流より件数が増えた指摘の種類 | | **なし** | |
| 自作コード（`org.tamacat.*`）のセキュリティ指摘 | | **0** | |

自作コードの非セキュリティ指摘 2 件は意図的なものとして [spotbugs-exclude.xml](../tomcat-secure-embed/spotbugs-exclude.xml) に理由付きで除外しています
（`getTomcat()` による内部オブジェクトの公開、`main()` の `throws Exception`）。

## 対応した指摘

| 指摘 | 対応 |
|---|---|
| `StandardSession` のデシリアライズ（OBJECT_DESERIALIZATION ×16, 優先度 1） | セッションの Java シリアライズ／デシリアライズ自体を拒否（`readObject` / `writeObject` が常に例外）。永続化用の読み書きメソッドを削除 |
| `DefaultInstanceManager` の JNDI 検索（LDAP_INJECTION ×4） | JNDI 削除で到達不能になった `@Resource` 注入・JNDI 検索コードを削除 |
| `StandardServer` のシャットダウンポート（UNENCRYPTED_SERVER_SOCKET, PREDICTABLE_RANDOM） | 平文の SHUTDOWN コマンド受付ソケットを削除。`setPort()` は -1 / -2 以外を拒否 |
| `DefaultServlet` のディレクトリ一覧（MALICIOUS_XSLT, XXE_*_TRANSFORM_FACTORY, POTENTIAL_XML_INJECTION, XSS_SERVLET） | 一覧表示（HTML / XML / XSLT / README / ソート）を削除。`listings=true` は起動失敗 |

### 副次的に見つかった不具合

`DefaultInstanceManager` は上流でも「JNDI が無効だと `@PostConstruct` を呼ばない」実装でした（組み込み Tomcat の既定）。
JNDI を削除したことで `@PostConstruct` が一切呼ばれなくなっていたため、JNDI と無関係に `@PostConstruct` / `@PreDestroy` を呼ぶよう修正し、テストを追加しました。

## 手作業レビューで見つけ修正した点（自作・変更コード）

| 箇所 | 問題 | 修正 |
|---|---|---|
| `SecureTomcat` 作業ディレクトリの削除 | リンクをたどってリンク先の中身を削除し得た。Windows のジャンクションは Java の `isSymbolicLink()` で検出できない | リンクをたどらない属性で判定し、通常のディレクトリだけ再帰（ジャンクションでテスト済み） |
| `SecureTomcat` のコンテキストパス | `/..` 等を受け付け、配置先が作業ディレクトリ外に出得た | 各セグメントを英数字と `._~-` に限定し、`.` / `..` / 空を拒否 |
| `Connector.setProperty("SSLEnabled", "true")` | 黙って無視され、HTTPS のつもりで平文 HTTP になり得た | 例外で拒否 |
| `tools/apply-removals.sh` | 削除リストのパスを検証せずに `rm -rf` | 絶対パス・`..`・バックスラッシュを含む行を拒否 |

## 残っている指摘（誤検知・許容と判断したもの）

| 種類 | 件数 | 判断理由 |
|---|---|---|
| IMPROPER_UNICODE | 154 | ASCII のヘッダ名・プロパティ名などに対する `equalsIgnoreCase` / `toLowerCase`。ユーザー入力の正規化による認可判断ではない |
| PATH_TRAVERSAL_IN / OUT | 116 / 2 | 起動時の設定値（work ディレクトリ、web.xml、JSP コンパイル出力など）が入力。リクエストのパスは `WebResourceRoot` の正規化・検証を経由する |
| URLCONNECTION_SSRF_FD | 29 | jar スキャン・TLD / web-fragment 読み込みなど、クラスパス上の URL のみが対象 |
| SERVLET_* / COOKIE_USAGE | 72 | Servlet API が要求を読む箇所そのもの（入力を読むたびに出る情報提供の指摘） |
| OBJECT_DESERIALIZATION | 12 | Jakarta EL の `ValueExpression` / `MethodExpression` は仕様上 `Serializable`。コンテナ自身が外部データをデシリアライズする経路はない |
| POTENTIAL_XML_INJECTION / XXE_* | 12 / 6 | JSP コンパイラと web.xml 読み込み（開発者が書いたファイルが入力）。Digester は外部エンティティを遮断（`xmlBlockExternal=true`） |
| UNVALIDATED_REDIRECT / URL_REWRITING | 13 | `sendRedirect` / `encodeURL` の API 実装そのもの。セッション ID の URL 埋め込みは `SecureTomcat` で無効（Cookie のみ） |
| PERMISSIVE_CORS / HRS | 3 | `CorsFilter` はアプリが明示的に設定した場合のみ動作し、既定の許可オリジンは空 |
| その他 | 少数 | 同期化（USO_*）は JDK 内部 API の利用、ログ書式、Unix ドメインソケットの権限設定など、設定駆動または影響なし |

## 依存ライブラリ（SCA）

OSV（osv.dev）で照会し、既知の脆弱性なし: `org.eclipse.jdt:ecj:3.46.100`、`tomcat-embed-core/el/jasper:11.0.27`、`tomcat-annotations-api:11.0.27`。
