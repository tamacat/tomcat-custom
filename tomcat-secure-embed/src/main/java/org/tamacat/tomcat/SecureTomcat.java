/*
 * Copyright 2026 tamacat.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tamacat.tomcat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import jakarta.servlet.SessionTrackingMode;

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.filters.HttpHeaderSecurityFilter;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.apache.tomcat.util.modeler.Registry;

/**
 * Entry point for the hardened embedded Tomcat.
 * <p>
 * The server only speaks plain-text HTTP/1.1 (no HTTP/2, no WebSocket / HTTP Upgrade, no AJP, no TLS) and provides
 * Servlet, JSP and EL. Container managed authentication (Realms / Authenticators), JNDI, session persistence,
 * clustering, the RewriteValve, CGI, WebDAV and the Manager applications have been removed from the code base.
 * <p>
 * In addition this class applies secure defaults:
 * <ul>
 * <li>binds to {@code 127.0.0.1} unless another address is configured</li>
 * <li>JMX MBean registration disabled</li>
 * <li>shutdown port disabled, no automatic deployment, a web application fails to start if any of its
 * load-on-startup servlets fails to initialise</li>
 * <li>error pages without stack traces or server version</li>
 * <li>sessions tracked by cookie only (no {@code ;jsessionid=} URL rewriting), {@code HttpOnly},
 * {@code SameSite=Lax}</li>
 * <li>strict URI handling (encoded {@code /} and {@code \} rejected, suspicious URIs rejected, TRACE disabled)</li>
 * <li>security response headers ({@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY})</li>
 * </ul>
 * TLS is expected to be terminated by a reverse proxy / load balancer in front of this server. Use
 * {@link Builder#behindTlsProxy(boolean)} so that the application sees {@code https} / {@code isSecure()} and the
 * session cookie gets the {@code Secure} attribute. In that mode every request is treated as secure, so the server
 * must only be reachable through the proxy (bind it to a loopback / private address).
 *
 * <pre>
 * SecureTomcat server = SecureTomcat.builder()
 *         .port(8080)
 *         .webapp("", Path.of("src/main/webapp"))
 *         .build();
 * server.start();
 * server.await();
 * </pre>
 */
public final class SecureTomcat implements AutoCloseable {

    private final Tomcat tomcat;
    private final Builder config;
    private final Path baseDir;
    private final boolean deleteBaseDir;

    private SecureTomcat(Builder config) throws IOException {
        this.config = config;

        if (!config.jmxEnabled) {
            Registry.disableRegistry();
        }

        if (config.baseDir != null) {
            baseDir = config.baseDir.toAbsolutePath();
            Files.createDirectories(baseDir);
            deleteBaseDir = false;
        } else {
            // Owner-only permissions on POSIX file systems
            baseDir = Files.createTempDirectory("tomcat-secure-");
            deleteBaseDir = true;
        }

        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setHostname("localhost");
        tomcat.setAddDefaultWebXmlToWebapp(true);

        // Shutdown port disabled: stop via the API / JVM shutdown only
        tomcat.getServer().setPort(-1);

        configureConnector(tomcat.getConnector());
        configureHost(tomcat.getHost());

        for (WebappDef webapp : config.webapps) {
            Context context;
            if (webapp.docBase != null) {
                context = tomcat.addWebapp(webapp.contextPath, webapp.docBase.toAbsolutePath().toString());
            } else {
                File docBase = Files.createDirectories(baseDir.resolve("docbase").resolve(
                        webapp.contextPath.isEmpty() ? "ROOT" : webapp.contextPath.substring(1).replace('/', '#')))
                        .toFile();
                context = tomcat.addContext(webapp.contextPath, docBase.getAbsolutePath());
                Tomcat.initWebappDefaults(context);
            }
            configureContext(context);
            if (webapp.customizer != null) {
                webapp.customizer.accept(context);
            }
        }
    }


    /**
     * @return a new builder with secure defaults
     */
    public static Builder builder() {
        return new Builder();
    }


    private void configureConnector(Connector connector) {
        connector.setPort(config.port);
        connector.setProperty("address", config.address);

        // Request / URI handling
        connector.setAllowTrace(false);
        connector.setXpoweredBy(false);
        connector.setEnableLookups(false);
        connector.setEncodedSolidusHandling("reject");
        connector.setEncodedReverseSolidusHandling("reject");
        connector.setAllowBackslash(false);
        connector.setRejectSuspiciousURIs(true);
        connector.setMaxPostSize(config.maxPostSize);
        connector.setMaxParameterCount(config.maxParameterCount);
        connector.setMaxPartCount(config.maxPartCount);
        connector.setURIEncoding("UTF-8");

        if (config.behindTlsProxy) {
            connector.setScheme("https");
            connector.setSecure(true);
            if (config.proxyPort > 0) {
                connector.setProxyPort(config.proxyPort);
            }
        }

        if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> http) {
            http.setMaxHttpHeaderSize(config.maxHttpHeaderSize);
            http.setMaxSwallowSize(config.maxPostSize);
            http.setMaxKeepAliveRequests(100);
            http.setConnectionTimeout(config.connectionTimeoutMillis);
            http.setKeepAliveTimeout(config.connectionTimeoutMillis);
            http.setMaxThreads(config.maxThreads);
            http.setMaxConnections(config.maxConnections);
            // No "Server" response header
            http.setServer(null);
        }
    }


    private void configureHost(Host host) {
        if (host instanceof StandardHost standardHost) {
            standardHost.setAutoDeploy(false);
            standardHost.setDeployOnStartup(false);
            standardHost.setUnpackWARs(false);
            // Fail fast: a servlet that cannot be initialised (e.g. rejected insecure configuration) fails the whole
            // application instead of leaving it half-started
            standardHost.setFailCtxIfServletStartFails(true);
        }
        // Explicit error report valve: no stack traces, no server info
        ErrorReportValve errorReportValve = new ErrorReportValve();
        errorReportValve.setShowReport(false);
        errorReportValve.setShowServerInfo(false);
        host.getPipeline().addValve(errorReportValve);
    }


    private void configureContext(Context context) {
        context.setReloadable(false);
        context.setPrivileged(false);
        context.setUseHttpOnly(true);
        context.setAllowCasualMultipartParsing(false);
        context.setXmlBlockExternal(true);
        context.setSessionTimeout(config.sessionTimeoutMinutes);

        Rfc6265CookieProcessor cookieProcessor = new Rfc6265CookieProcessor();
        cookieProcessor.setSameSiteCookies("lax");
        context.setCookieProcessor(cookieProcessor);

        // Cookie-only session tracking (prevents session IDs in URLs / logs / Referer headers)
        context.addServletContainerInitializer((classes, servletContext) -> {
            servletContext.setSessionTrackingModes(EnumSet.of(SessionTrackingMode.COOKIE));
            servletContext.getSessionCookieConfig().setHttpOnly(true);
            if (config.behindTlsProxy) {
                servletContext.getSessionCookieConfig().setSecure(true);
            }
        }, null);

        if (config.securityHeaders) {
            FilterDef filterDef = new FilterDef();
            filterDef.setFilterName("tomcatSecureHttpHeaderSecurityFilter");
            filterDef.setFilterClass(HttpHeaderSecurityFilter.class.getName());
            filterDef.addInitParameter("hstsEnabled", Boolean.toString(config.behindTlsProxy));
            filterDef.addInitParameter("antiClickJackingEnabled", "true");
            filterDef.addInitParameter("antiClickJackingOption", "DENY");
            filterDef.addInitParameter("blockContentTypeSniffingEnabled", "true");
            filterDef.setAsyncSupported("true");
            context.addFilterDef(filterDef);
            FilterMap filterMap = new FilterMap();
            filterMap.setFilterName(filterDef.getFilterName());
            filterMap.addURLPattern("/*");
            context.addFilterMapBefore(filterMap);
        }
    }


    /**
     * Starts the server.
     *
     * @throws LifecycleException if the server, or any web application, fails to start
     */
    public void start() throws LifecycleException {
        tomcat.start();
        for (org.apache.catalina.Container child : tomcat.getHost().findChildren()) {
            if (!child.getState().isAvailable()) {
                stop();
                throw new LifecycleException("Web application [" + child.getName() + "] failed to start");
            }
        }
    }


    /**
     * Blocks until the server is stopped.
     */
    public void await() {
        tomcat.getServer().await();
    }


    /**
     * Stops and destroys the server.
     *
     * @throws LifecycleException if the server fails to stop
     */
    public void stop() throws LifecycleException {
        try {
            tomcat.stop();
            tomcat.destroy();
        } finally {
            if (deleteBaseDir) {
                deleteRecursively(baseDir.toFile());
            }
        }
    }


    @Override
    public void close() throws LifecycleException {
        stop();
    }


    /**
     * @return the actual local port (useful when port 0 was configured)
     */
    public int getPort() {
        return tomcat.getConnector().getLocalPort();
    }


    /**
     * Access to the underlying {@link Tomcat} instance for advanced customization. Customizations made through this
     * object bypass the secure defaults applied by this class.
     *
     * @return the underlying Tomcat instance
     */
    public Tomcat getTomcat() {
        return tomcat;
    }


    private static void deleteRecursively(File file) {
        // Never follow links: only recurse into real directories. Symbolic links and other reparse points (e.g.
        // Windows junctions, which are not reported as symbolic links) are deleted themselves, not their targets.
        if (isRealDirectory(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            file.deleteOnExit();
        }
    }

    private static boolean isRealDirectory(Path path) {
        try {
            BasicFileAttributes attrs =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther();
        } catch (IOException e) {
            return false;
        }
    }


    private record WebappDef(String contextPath, Path docBase, Consumer<Context> customizer) {
    }


    /**
     * Builder with secure defaults.
     */
    public static final class Builder {
        private int port = 8080;
        private String address = "127.0.0.1";
        private Path baseDir;
        private boolean jmxEnabled = false;
        private boolean behindTlsProxy = false;
        private int proxyPort = -1;
        private boolean securityHeaders = true;
        private int maxPostSize = 2 * 1024 * 1024;
        private int maxParameterCount = 1000;
        private int maxPartCount = 50;
        private int maxHttpHeaderSize = 8 * 1024;
        private int connectionTimeoutMillis = 20_000;
        private int maxThreads = 200;
        private int maxConnections = 8192;
        private int sessionTimeoutMinutes = 30;
        private final List<WebappDef> webapps = new ArrayList<>();

        private Builder() {
        }

        /**
         * @param port TCP port to listen on ({@code 0} = pick a free port). Default {@code 8080}.
         * @return this builder
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * @param address IP address to bind to. Default {@code 127.0.0.1}. Use {@code 0.0.0.0} to listen on all
         *                    interfaces.
         * @return this builder
         */
        public Builder address(String address) {
            this.address = Objects.requireNonNull(address);
            return this;
        }

        /**
         * @param baseDir Tomcat work directory (compiled JSPs etc.). Default: a new temporary directory that is
         *                    deleted on stop.
         * @return this builder
         */
        public Builder baseDir(Path baseDir) {
            this.baseDir = baseDir;
            return this;
        }

        /**
         * @param jmxEnabled register Tomcat MBeans with the platform MBean server. Default {@code false}.
         * @return this builder
         */
        public Builder jmx(boolean jmxEnabled) {
            this.jmxEnabled = jmxEnabled;
            return this;
        }

        /**
         * @param behindTlsProxy {@code true} when TLS is terminated by a reverse proxy in front of this server: the
         *                           request scheme becomes {@code https}, {@code isSecure()} returns {@code true},
         *                           the session cookie gets the {@code Secure} attribute and HSTS is sent. Default
         *                           {@code false}.
         * @return this builder
         */
        public Builder behindTlsProxy(boolean behindTlsProxy) {
            this.behindTlsProxy = behindTlsProxy;
            return this;
        }

        /**
         * @param proxyPort public port of the TLS proxy (used for redirects), e.g. {@code 443}
         * @return this builder
         */
        public Builder proxyPort(int proxyPort) {
            this.proxyPort = proxyPort;
            return this;
        }

        /**
         * @param securityHeaders add {@code X-Content-Type-Options} / {@code X-Frame-Options} (and HSTS when behind a
         *                            TLS proxy) to every response. Default {@code true}.
         * @return this builder
         */
        public Builder securityHeaders(boolean securityHeaders) {
            this.securityHeaders = securityHeaders;
            return this;
        }

        /**
         * @param maxPostSize maximum size in bytes of a POST body that is parsed for parameters, and of a request
         *                        body that is swallowed. Default 2 MiB.
         * @return this builder
         */
        public Builder maxPostSize(int maxPostSize) {
            this.maxPostSize = maxPostSize;
            return this;
        }

        /**
         * @param maxParameterCount maximum number of request parameters. Default 1000.
         * @return this builder
         */
        public Builder maxParameterCount(int maxParameterCount) {
            this.maxParameterCount = maxParameterCount;
            return this;
        }

        /**
         * @param maxPartCount maximum number of parts in a multipart request. Default 50.
         * @return this builder
         */
        public Builder maxPartCount(int maxPartCount) {
            this.maxPartCount = maxPartCount;
            return this;
        }

        /**
         * @param maxHttpHeaderSize maximum size in bytes of the request line plus headers. Default 8 KiB.
         * @return this builder
         */
        public Builder maxHttpHeaderSize(int maxHttpHeaderSize) {
            this.maxHttpHeaderSize = maxHttpHeaderSize;
            return this;
        }

        /**
         * @param connectionTimeoutMillis connection and keep-alive timeout. Default 20 seconds.
         * @return this builder
         */
        public Builder connectionTimeout(int connectionTimeoutMillis) {
            this.connectionTimeoutMillis = connectionTimeoutMillis;
            return this;
        }

        /**
         * @param maxThreads maximum number of request processing threads. Default 200.
         * @return this builder
         */
        public Builder maxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
            return this;
        }

        /**
         * @param maxConnections maximum number of concurrent connections. Default 8192.
         * @return this builder
         */
        public Builder maxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        /**
         * @param sessionTimeoutMinutes default HTTP session timeout. Default 30 minutes.
         * @return this builder
         */
        public Builder sessionTimeout(int sessionTimeoutMinutes) {
            this.sessionTimeoutMinutes = sessionTimeoutMinutes;
            return this;
        }

        /**
         * Adds a web application from a directory or a WAR file. {@code WEB-INF/web.xml}, annotations,
         * {@code ServletContainerInitializer}s and JSPs are supported.
         *
         * @param contextPath context path ({@code ""} for the root context, otherwise {@code /name})
         * @param docBase     directory or WAR file
         * @return this builder
         */
        public Builder webapp(String contextPath, Path docBase) {
            return webapp(contextPath, docBase, null);
        }

        /**
         * Adds a web application from a directory or a WAR file.
         *
         * @param contextPath context path ({@code ""} for the root context, otherwise {@code /name})
         * @param docBase     directory or WAR file
         * @param customizer  optional callback to customize the context before start
         * @return this builder
         */
        public Builder webapp(String contextPath, Path docBase, Consumer<Context> customizer) {
            webapps.add(new WebappDef(checkContextPath(contextPath), Objects.requireNonNull(docBase), customizer));
            return this;
        }

        /**
         * Adds a programmatic web application (no document base on disk). Servlets / filters are registered by the
         * customizer, e.g. with {@link Tomcat#addServlet(Context, String, jakarta.servlet.Servlet)}.
         *
         * @param contextPath context path ({@code ""} for the root context, otherwise {@code /name})
         * @param customizer  callback that registers servlets / filters
         * @return this builder
         */
        public Builder context(String contextPath, Consumer<Context> customizer) {
            webapps.add(new WebappDef(checkContextPath(contextPath), null, Objects.requireNonNull(customizer)));
            return this;
        }

        /**
         * @return the configured server (not yet started)
         *
         * @throws IOException if the work directory cannot be created
         */
        public SecureTomcat build() throws IOException {
            return new SecureTomcat(this);
        }

        private static String checkContextPath(String contextPath) {
            Objects.requireNonNull(contextPath);
            if (contextPath.equals("/")) {
                return "";
            }
            if (contextPath.isEmpty()) {
                return contextPath;
            }
            if (!contextPath.startsWith("/") || contextPath.endsWith("/")) {
                throw new IllegalArgumentException("Invalid context path [" + contextPath + "]");
            }
            // Each segment: non-empty, not "." / "..", only unreserved URI characters. This also keeps the
            // document base derived from the context path inside the work directory.
            for (String segment : contextPath.substring(1).split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..") ||
                        !segment.matches("[A-Za-z0-9._~-]+")) {
                    throw new IllegalArgumentException("Invalid context path [" + contextPath + "]");
                }
            }
            return contextPath;
        }
    }


    /**
     * Command line entry point.
     *
     * <pre>
     * java -jar app.jar --webapp ./webapp [--path /app] [--port 8080] [--address 127.0.0.1] [--behind-tls-proxy]
     * </pre>
     *
     * @param args command line arguments
     *
     * @throws Exception if the server fails to start
     */
    public static void main(String[] args) throws Exception {
        Builder builder = builder();
        String path = "";
        Path webapp = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> builder.port(Integer.parseInt(value(args, ++i)));
                case "--address" -> builder.address(value(args, ++i));
                case "--path" -> path = value(args, ++i);
                case "--webapp" -> webapp = Path.of(value(args, ++i));
                case "--basedir" -> builder.baseDir(Path.of(value(args, ++i)));
                case "--behind-tls-proxy" -> builder.behindTlsProxy(true);
                case "--jmx" -> builder.jmx(true);
                default -> throw new IllegalArgumentException("Unknown option [" + args[i] + "]");
            }
        }
        if (webapp == null) {
            throw new IllegalArgumentException("--webapp <dir|war> is required");
        }
        SecureTomcat server = builder.webapp(path, webapp).build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop();
            } catch (LifecycleException e) {
                e.printStackTrace();
            }
        }, "tomcat-secure-shutdown"));
        server.start();
        server.await();
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for [" + args[i - 1] + "]");
        }
        return args[i];
    }
}
