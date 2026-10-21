package org.tamacat.tomcat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;

/**
 * Servlets used by the tests.
 */
public final class TestServlets {

    private TestServlets() {
    }

    public static class HelloServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/plain;charset=UTF-8");
            resp.getWriter().print("hello scheme=" + req.getScheme() + " secure=" + req.isSecure());
        }
    }

    public static class SessionServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            req.getSession(true).setAttribute("k", "v");
            resp.setContentType("text/plain;charset=UTF-8");
            resp.getWriter().print(resp.encodeURL("/next"));
        }
    }

    public static class UpgradeServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
            req.upgrade(NoopUpgradeHandler.class);
            resp.getWriter().print("upgraded");
        }
    }

    public static class NoopUpgradeHandler implements HttpUpgradeHandler {
        @Override
        public void init(WebConnection wc) {
        }

        @Override
        public void destroy() {
        }
    }

    public static class LoginServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String result;
            try {
                req.login("admin", "admin");
                result = "logged-in";
            } catch (ServletException e) {
                result = "login-rejected";
            }
            resp.getWriter().print(result + " inRole=" + req.isUserInRole("admin") + " principal=" +
                    req.getUserPrincipal());
        }
    }

    public static class FailingServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            throw new IllegalStateException("secret-internal-detail");
        }
    }

    public static class LifecycleCallbackServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;
        static final AtomicInteger POST_CONSTRUCT = new AtomicInteger();
        static final AtomicInteger PRE_DESTROY = new AtomicInteger();

        @jakarta.annotation.PostConstruct
        void postConstruct() {
            POST_CONSTRUCT.incrementAndGet();
        }

        @jakarta.annotation.PreDestroy
        void preDestroy() {
            PRE_DESTROY.incrementAndGet();
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("postConstruct=" + POST_CONSTRUCT.get());
        }
    }

    public static class ResourceInjectedServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @jakarta.annotation.Resource(name = "jdbc/app")
        private javax.sql.DataSource dataSource;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("dataSource=" + dataSource);
        }
    }

    @ServletSecurity(@HttpConstraint(rolesAllowed = "admin"))
    public static class AnnotatedSecureServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().print("should-never-be-served");
        }
    }
}
