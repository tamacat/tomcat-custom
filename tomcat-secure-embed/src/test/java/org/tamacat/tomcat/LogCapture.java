package org.tamacat.tomcat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures Tomcat log output (all {@code org.apache.*} loggers) while a test intentionally triggers an error, so
 * that expected errors and their stack traces are not printed to the console. The captured records can be checked
 * to verify that the failure happened for the expected reason.
 */
final class LogCapture implements AutoCloseable {

    // Strong reference: java.util.logging only keeps weak references to loggers
    private final Logger logger = Logger.getLogger("org.apache");
    private final boolean oldUseParentHandlers;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    LogCapture() {
        handler.setLevel(Level.ALL);
        oldUseParentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
    }

    /**
     * @param text text to look for
     * @return {@code true} if a captured log message, or the message of an exception (or cause) attached to it,
     *             contains the text
     */
    boolean contains(String text) {
        for (LogRecord record : records) {
            if (record.getMessage() != null && record.getMessage().contains(text)) {
                return true;
            }
            for (Throwable t = record.getThrown(); t != null; t = t.getCause()) {
                if (t.getMessage() != null && t.getMessage().contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return a short summary of the captured messages (for assertion failure messages)
     */
    String summary() {
        StringBuilder sb = new StringBuilder("captured log:");
        for (LogRecord record : records) {
            sb.append("\n  [").append(record.getLevel()).append("] ").append(record.getMessage());
            for (Throwable t = record.getThrown(); t != null; t = t.getCause()) {
                sb.append("\n      caused by ").append(t);
            }
        }
        return sb.toString();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setUseParentHandlers(oldUseParentHandlers);
    }
}
