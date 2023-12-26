package io.simonis.utils;

import java.lang.reflect.Method;

/**
 * Poor man's logging interface without any dependnecies.
 * Checks if it can find org.slf4j.LoggerFactory on the classpath
 * and uses it reflectively if available.
 */
public class Logger {

    private static Class LOGGER_FACTORY;
    private static Method getLogger;
    private static Method error, warn, info, debug, trace;

    static {
        try {
            LOGGER_FACTORY = Class.forName("org.slf4j.LoggerFactory");
            getLogger = LOGGER_FACTORY.getMethod("getLogger", Class.class);
            Class LOGGER = Class.forName("org.slf4j.Logger");
            error = LOGGER.getMethod("error", String.class, Object[].class);
            warn = LOGGER.getMethod("warn", String.class, Object[].class);
            info = LOGGER.getMethod("info", String.class, Object[].class);
            debug = LOGGER.getMethod("debug", String.class, Object[].class);
            trace = LOGGER.getMethod("trace", String.class, Object[].class);
        } catch (Exception ignore) {
            LOGGER_FACTORY = null;
        }
    }

    private Object logger; // Either a org.slf4j.Logger or null

    private Logger(Object logger) {
        this.logger = logger;
    }

    public static Logger getLogger(Class c) {
        if (LOGGER_FACTORY == null) {
            return new Logger(null);
        } else {
            try {
                Object logger = getLogger.invoke(null, c);
                return new Logger(logger);
            } catch (Exception ignore) {
                return new Logger(null);
            }
        }
    }

    public void error(String format, Object... arguments) {
        if (logger != null) {
            try {
                error.invoke(logger, format, arguments);
            } catch (Exception ignore) {}
        }
    }

    public void warn(String format, Object... arguments) {
        if (logger != null) {
            try {
                warn.invoke(logger, format, arguments);
            } catch (Exception ignore) {}
        }
    }

    public void info(String format, Object... arguments) {
        if (logger != null) {
            try {
                info.invoke(logger, format, arguments);
            } catch (Exception ignore) {}
        }
    }

    public void debug(String format, Object... arguments) {
        if (logger != null) {
            try {
                debug.invoke(logger, format, arguments);
            } catch (Exception ignore) {}
        }
    }

    public void trace(String format, Object... arguments) {
        if (logger != null) {
            try {
                trace.invoke(logger, format, arguments);
            } catch (Exception ignore) {}
        }
    }
}
