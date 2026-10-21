package org.tamacat.tomcat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SecureTomcatTest {

    private static SecureTomcat server;
    private static HttpClient client;

    @BeforeAll
    static void startServer() throws Exception {
        server = SecureTomcat.builder()
                .port(0)
                .webapp("/app", webappDir("jsp-app"))
                .context("/api", SecureTomcatTest::registerServlets)
                .build();
        server.start();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    private static void registerServlets(Context ctx) {
        Tomcat.addServlet(ctx, "hello", new TestServlets.HelloServlet());
        ctx.addServletMappingDecoded("/hello", "hello");
        Tomcat.addServlet(ctx, "session", new TestServlets.SessionServlet());
        ctx.addServletMappingDecoded("/session", "session");
        Tomcat.addServlet(ctx, "upgrade", new TestServlets.UpgradeServlet());
        ctx.addServletMappingDecoded("/upgrade", "upgrade");
        Tomcat.addServlet(ctx, "login", new TestServlets.LoginServlet());
        ctx.addServletMappingDecoded("/login", "login");
        Tomcat.addServlet(ctx, "fail", new TestServlets.FailingServlet());
        ctx.addServletMappingDecoded("/fail", "fail");
    }

    static Path webappDir(String name) throws URISyntaxException {
        return Path.of(SecureTomcatTest.class.getResource("/webapps/" + name).toURI());
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getPort() + path);
    }

    /** Sends raw bytes and returns everything the server sends back until it closes the connection. */
    private static String raw(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            try {
                while ((n = in.read(b)) != -1) {
                    buf.write(b, 0, n);
                }
            } catch (java.net.SocketException e) {
                // connection reset after the response is acceptable
            }
            return buf.toString(StandardCharsets.ISO_8859_1);
        }
    }

    // ------------------------------------------------------------------ Servlet / JSP / EL

    @Test
    void servletFromWebXml() throws Exception {
        HttpResponse<String> res = get("/app/hello");
        assertEquals(200, res.statusCode());
        assertEquals("hello scheme=http secure=false", res.body());
    }

    @Test
    void programmaticServlet() throws Exception {
        HttpResponse<String> res = get("/api/hello");
        assertEquals(200, res.statusCode());
        assertEquals("hello scheme=http secure=false", res.body());
    }

    @Test
    void jspAndEl() throws Exception {
        HttpResponse<String> res = get("/app/index.jsp?name=tamacat");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("JSP-OK"), res.body());
        assertTrue(res.body().contains("EL=3"), res.body());
        assertTrue(res.body().contains("PARAM=tamacat"), res.body());
        assertTrue(res.body().contains("LAMBDA=40"), res.body());
    }

    @Test
    void staticResource() throws Exception {
        HttpResponse<String> res = get("/app/static.txt");
        assertEquals(200, res.statusCode());
        assertEquals("static-content", res.body().trim());
    }

    @Test
    void sessionCookieHardening() throws Exception {
        HttpResponse<String> res = get("/api/session");
        assertEquals(200, res.statusCode());
        String cookie = res.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.startsWith("JSESSIONID="), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("SameSite=Lax"), cookie);
        // cookie-only tracking: no ;jsessionid= URL rewriting
        assertEquals("/next", res.body());
    }

    @Test
    void securityHeadersAndNoServerHeader() throws Exception {
        HttpResponse<String> res = get("/api/hello");
        assertEquals("nosniff", res.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", res.headers().firstValue("X-Frame-Options").orElse(null));
        assertTrue(res.headers().firstValue("Server").isEmpty());
        assertTrue(res.headers().firstValue("X-Powered-By").isEmpty());
        assertTrue(res.headers().firstValue("Strict-Transport-Security").isEmpty());
    }

    @Test
    void errorPageHidesDetails() throws Exception {
        HttpResponse<String> res;
        try (LogCapture log = new LogCapture()) {
            res = get("/api/fail");
            // the exception is logged on the server side only
            assertTrue(log.contains("secret-internal-detail"), log.summary());
        }
        assertEquals(500, res.statusCode());
        assertFalse(res.body().contains("secret-internal-detail"), res.body());
        assertFalse(res.body().contains("Exception"), res.body());
        assertFalse(res.body().contains("Apache Tomcat"), res.body());
    }

    // ------------------------------------------------------------------ HTTP/2, upgrade, WebSocket

    @Test
    void http2PriorKnowledgeIsNotSpoken() throws Exception {
        String res = raw("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");
        // An HTTP/2 server would answer with a binary SETTINGS frame. We expect a plain HTTP/1.1 error.
        assertTrue(res.startsWith("HTTP/1.1 "), res);
        assertFalse(res.startsWith("HTTP/1.1 101"), res);
    }

    @Test
    void h2cUpgradeIsIgnored() throws Exception {
        String res = raw("GET /api/hello HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade, HTTP2-Settings\r\n" +
                "Upgrade: h2c\r\nHTTP2-Settings: AAMAAABkAARAAAAAAAIAAAAA\r\nConnection: close\r\n\r\n");
        assertTrue(res.startsWith("HTTP/1.1 200"), res);
        assertFalse(res.contains("101"), res);
    }

    @Test
    void webSocketHandshakeIsNotAccepted() throws Exception {
        String res = raw("GET /api/hello HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade, close\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n");
        assertTrue(res.startsWith("HTTP/1.1 200"), res);
        assertFalse(res.contains("Sec-WebSocket-Accept"), res);
    }

    @Test
    void servletUpgradeApiIsRejected() throws Exception {
        HttpResponse<String> res;
        try (LogCapture log = new LogCapture()) {
            res = get("/api/upgrade");
            assertTrue(log.contains("HTTP Upgrade (including WebSocket and h2c) is not supported"), log.summary());
        }
        assertEquals(500, res.statusCode());
        assertFalse(res.body().contains("upgraded"));
    }

    @Test
    void http2ClientFallsBackToHttp11() throws Exception {
        HttpClient h2 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        HttpResponse<String> res = h2.send(HttpRequest.newBuilder(uri("/api/hello")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_1_1, res.version());
    }

    // ------------------------------------------------------------------ request hardening

    @Test
    void traceIsDisabled() throws Exception {
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(uri("/api/hello"))
                .method("TRACE", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, res.statusCode());
    }

    @Test
    void encodedSlashIsRejected() throws Exception {
        String res = raw("GET /app/..%2fWEB-INF/web.xml HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertTrue(res.startsWith("HTTP/1.1 400"), res);
    }

    @Test
    void webInfIsNotServed() throws Exception {
        assertEquals(404, get("/app/WEB-INF/web.xml").statusCode());
    }

    @Test
    void putIsNotAllowedOnStaticResources() throws Exception {
        // DefaultServlet is always read-only
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(uri("/app/evil.txt"))
                .PUT(HttpRequest.BodyPublishers.ofString("evil")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, res.statusCode());
        assertEquals(404, get("/app/evil.txt").statusCode());
        // *.jsp is handled by the JspServlet which never writes either (cf. CVE-2017-12617)
        res = client.send(HttpRequest.newBuilder(uri("/app/evil.jsp"))
                .PUT(HttpRequest.BodyPublishers.ofString("<%= 1 %>")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(res.statusCode() >= 400, Integer.toString(res.statusCode()));
        assertEquals(404, get("/app/evil.jsp").statusCode());
    }

    // ------------------------------------------------------------------ container authentication

    @Test
    void containerLoginIsNotAvailable() throws Exception {
        HttpResponse<String> res = get("/api/login");
        assertEquals(200, res.statusCode());
        assertEquals("login-rejected inRole=false principal=null", res.body());
    }

    @Test
    void webappWithSecurityConstraintFailsToStart() throws Exception {
        assertFailsToStart(SecureTomcat.builder().port(0).webapp("", webappDir("constraint-app")),
                "<security-constraint> is not supported");
    }

    @Test
    void webappWithLoginConfigFailsToStart() throws Exception {
        assertFailsToStart(SecureTomcat.builder().port(0).webapp("", webappDir("login-app")),
                "<login-config> is not supported");
    }

    @Test
    void servletSecurityAnnotationFailsToStart() throws Exception {
        assertFailsToStart(SecureTomcat.builder().port(0).context("", ctx -> {
            Tomcat.addServlet(ctx, "secure", TestServlets.AnnotatedSecureServlet.class.getName());
            ctx.addServletMappingDecoded("/secure", "secure");
        }), "@ServletSecurity / setServletSecurity() is not supported");
    }

    @Test
    void writableDefaultServletFailsToStart() throws Exception {
        assertFailsToStart(SecureTomcat.builder().port(0).webapp("", webappDir("readonly-false-app")),
                "readonly=false is not supported");
    }

    // ------------------------------------------------------------------ JNDI

    @Test
    void webappWithEnvEntryFailsToStart() throws Exception {
        assertFailsToStart(SecureTomcat.builder().port(0).webapp("", webappDir("env-entry-app")),
                "<env-entry> / <Environment> / @Resource is not supported");
    }

    @Test
    void webappWithResourceRefFailsToStart() throws Exception {
        assertFailsToStart(SecureTomcat.builder().port(0).webapp("", webappDir("resource-ref-app")),
                "<resource-ref> / <Resource> / @Resource is not supported");
    }

    @Test
    void contextXmlResourceFailsToStart() throws Exception {
        assertFailsToStart(SecureTomcat.builder().port(0).webapp("", webappDir("context-resource-app")),
                "<resource-ref> / <Resource> / @Resource is not supported");
    }

    @Test
    void resourceAnnotationFailsToStart() throws Exception {
        assertFailsToStart(SecureTomcat.builder().port(0).context("", ctx -> {
            Tomcat.addServlet(ctx, "injected", TestServlets.ResourceInjectedServlet.class.getName());
            ctx.addServletMappingDecoded("/injected", "injected");
        }), "JNDI has been removed");
    }

    @Test
    void namingCannotBeEnabled() {
        org.apache.catalina.core.StandardContext ctx = new org.apache.catalina.core.StandardContext();
        assertFalse(ctx.isUseNaming());
        assertThrows(IllegalArgumentException.class, () -> ctx.setUseNaming(true));
    }

    @Test
    void javaCompEnvIsNotAvailable() throws Exception {
        try (SecureTomcat other = SecureTomcat.builder().port(0).context("", ctx -> {
            Tomcat.addServlet(ctx, "lookup", new jakarta.servlet.http.HttpServlet() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void doGet(jakarta.servlet.http.HttpServletRequest req,
                        jakarta.servlet.http.HttpServletResponse resp) throws IOException {
                    String result;
                    try {
                        new javax.naming.InitialContext().lookup("java:comp/env");
                        result = "found";
                    } catch (javax.naming.NamingException e) {
                        result = "no-jndi";
                    }
                    resp.getWriter().print(result);
                }
            });
            ctx.addServletMappingDecoded("/lookup", "lookup");
        }).build()) {
            other.start();
            HttpResponse<String> res = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + other.getPort() + "/lookup")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("no-jndi", res.body());
        }
    }

    /**
     * Asserts that the server refuses to start and that the expected reason was logged. The (expected) error log
     * output is captured instead of being printed.
     */
    private static void assertFailsToStart(SecureTomcat.Builder builder, String expectedReason) throws IOException {
        SecureTomcat other = builder.build();
        try (LogCapture log = new LogCapture()) {
            try {
                assertThrows(LifecycleException.class, other::start);
            } finally {
                try {
                    other.stop();
                } catch (LifecycleException | RuntimeException ignore) {
                    // already stopped
                }
            }
            assertTrue(log.contains(expectedReason), "expected [" + expectedReason + "] in " + log.summary());
        }
    }

    // ------------------------------------------------------------------ TLS proxy mode

    @Test
    void behindTlsProxy() throws Exception {
        try (SecureTomcat proxied = SecureTomcat.builder().port(0).behindTlsProxy(true)
                .context("", SecureTomcatTest::registerServlets).build()) {
            proxied.start();
            HttpResponse<String> res = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + proxied.getPort() + "/session")).build(),
                    HttpResponse.BodyHandlers.ofString());
            String cookie = res.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(cookie.contains("Secure"), cookie);
            assertTrue(res.headers().firstValue("Strict-Transport-Security").isPresent());
            HttpResponse<String> hello = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + proxied.getPort() + "/hello")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("hello scheme=https secure=true", hello.body());
        }
    }

    // ------------------------------------------------------------------ protocol handlers

    @Test
    void onlyHttp11ProtocolHandlerCanBeCreated() throws Exception {
        assertEquals("org.apache.coyote.http11.Http11NioProtocol",
                org.apache.coyote.ProtocolHandler.create("HTTP/1.1").getClass().getName());
        assertThrows(IllegalArgumentException.class, () -> org.apache.coyote.ProtocolHandler.create("AJP/1.3"));
        assertThrows(IllegalArgumentException.class,
                () -> org.apache.coyote.ProtocolHandler.create("org.apache.coyote.http11.Http11Nio2Protocol"));
    }

    @Test
    void tlsCannotBeEnabled() {
        org.apache.catalina.connector.Connector connector = new org.apache.catalina.connector.Connector();
        assertFalse(connector.setProperty("SSLEnabled", "true"));
        assertNotEquals(Boolean.TRUE, connector.getProperty("SSLEnabled"));
        assertNull(connector.getProperty("sslImplementationName"));
    }
}
