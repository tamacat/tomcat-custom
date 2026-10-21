package org.tamacat.tomcat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the removed features are not present in the build output at all.
 */
class RemovedFeaturesTest {

    /** Package prefixes that must not exist in the build output. */
    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "org/apache/coyote/http2/",
            "org/apache/coyote/ajp/",
            "org/apache/coyote/http11/upgrade/",
            "org/apache/tomcat/websocket/",
            "jakarta/websocket/",
            "org/apache/tomcat/jni/",
            "org/apache/tomcat/util/net/openssl/",
            "org/apache/tomcat/util/openssl/",
            "org/apache/tomcat/util/net/jsse/",
            "org/apache/catalina/valves/rewrite/",
            "org/apache/catalina/realm/",
            "org/apache/catalina/authenticator/",
            "jakarta/security/auth/message/",
            "org/apache/catalina/users/",
            "org/apache/catalina/ha/",
            "org/apache/catalina/tribes/",
            "org/apache/catalina/manager/",
            "org/apache/catalina/ssi/",
            "org/apache/tomcat/dbcp/",
            "org/apache/naming/");

    @ParameterizedTest
    @ValueSource(strings = {
            // HTTP/2 / upgrade / WebSocket
            "org.apache.coyote.http2.Http2Protocol",
            "org.apache.coyote.UpgradeProtocol",
            "org.apache.coyote.UpgradeToken",
            "org.apache.tomcat.websocket.server.WsSci",
            // AJP
            "org.apache.coyote.ajp.AjpNioProtocol",
            // TLS / OpenSSL / APR / NIO2
            "org.apache.tomcat.util.net.SSLHostConfig",
            "org.apache.tomcat.util.net.SecureNioChannel",
            "org.apache.tomcat.util.net.jsse.JSSEImplementation",
            "org.apache.tomcat.util.net.openssl.OpenSSLImplementation",
            "org.apache.catalina.core.AprLifecycleListener",
            "org.apache.catalina.core.OpenSSLLifecycleListener",
            "org.apache.coyote.http11.Http11Nio2Protocol",
            "org.apache.tomcat.util.net.Nio2Endpoint",
            // Rewrite
            "org.apache.catalina.valves.rewrite.RewriteValve",
            // Realms / Authenticators
            "org.apache.catalina.Realm",
            "org.apache.catalina.Authenticator",
            "org.apache.catalina.realm.RealmBase",
            "org.apache.catalina.realm.JNDIRealm",
            "org.apache.catalina.realm.DataSourceRealm",
            "org.apache.catalina.authenticator.FormAuthenticator",
            "org.apache.catalina.authenticator.SpnegoAuthenticator",
            "org.apache.catalina.users.MemoryUserDatabase",
            // Session persistence / clustering
            "org.apache.catalina.session.PersistentManager",
            "org.apache.catalina.session.FileStore",
            "org.apache.catalina.session.DataSourceStore",
            "org.apache.catalina.Store",
            "org.apache.catalina.Cluster",
            "org.apache.catalina.valves.PersistentValve",
            "org.apache.catalina.util.CustomObjectInputStream",
            // Other
            "org.apache.catalina.servlets.CGIServlet",
            "org.apache.catalina.servlets.WebdavServlet",
            "org.apache.catalina.manager.ManagerServlet",
            "org.apache.catalina.startup.Catalina",
            "org.apache.catalina.startup.Bootstrap",
            "org.apache.catalina.startup.HostConfig",
            "org.apache.catalina.mbeans.MBeanFactory",
            "org.apache.catalina.valves.JDBCAccessLogValve",
            // JNDI
            "org.apache.naming.NamingContext",
            "org.apache.naming.java.javaURLContextFactory",
            "org.apache.naming.factory.BeanFactory",
            "org.apache.naming.factory.ResourceFactory",
            "org.apache.catalina.core.NamingContextListener",
            "org.apache.catalina.mbeans.NamingResourcesMBean" })
    void classIsAbsent(String className) {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(className));
    }

    @Test
    void forbiddenPackagesAreAbsent() throws IOException, URISyntaxException {
        Path classes = Path.of(Tomcat.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes) || Files.isRegularFile(classes));
        if (Files.isDirectory(classes)) {
            try (Stream<Path> files = Files.walk(classes)) {
                List<String> offending = files.map(p -> classes.relativize(p).toString().replace('\\', '/') + "/")
                        .filter(p -> FORBIDDEN_PACKAGES.stream().anyMatch(p::startsWith)).toList();
                assertEquals(List.of(), offending);
            }
        }
    }

    @Test
    void mbeanDescriptorsAreWellFormed() throws Exception {
        Path classes = Path.of(Tomcat.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (Stream<Path> files = Files.walk(classes)) {
            List<Path> descriptors = files.filter(p -> p.getFileName().toString().equals("mbeans-descriptors.xml"))
                    .toList();
            assertTrue(descriptors.size() > 10);
            for (Path descriptor : descriptors) {
                dbf.newDocumentBuilder().parse(descriptor.toFile());
            }
        }
    }

    @Test
    void keptFeaturesArePresent() throws ClassNotFoundException {
        Class.forName("org.apache.coyote.http11.Http11NioProtocol");
        Class.forName("org.apache.jasper.servlet.JspServlet");
        Class.forName("org.apache.el.ExpressionFactoryImpl");
        Class.forName("org.apache.catalina.servlets.DefaultServlet");
        Class.forName("org.eclipse.jdt.internal.compiler.Compiler");
    }
}
