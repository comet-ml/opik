package com.comet.opik.api.resources.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Stream;

/**
 * Captures what the application logs, for tests asserting a value never reaches the logs.
 */
@UtilityClass
public class LogCaptureUtils {

    /** Logged at debug while capturing: Opik's own loggers, and LangChain4j's request and response logs. */
    private static final List<String> DEBUG_LOGGERS = List.of("com.comet.opik", "dev.langchain4j");

    /**
     * Every log event while {@code call} runs, with its throwable rendered: at debug for {@link #DEBUG_LOGGERS}, and
     * at the default {@code INFO} for every other logger, so the libraries' own debug output, a mock server's
     * included, is left out.
     */
    public static List<String> logEventsOf(@NonNull Runnable call) {
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var loggers = Stream.concat(Stream.of(root),
                DEBUG_LOGGERS.stream().map(name -> (Logger) LoggerFactory.getLogger(name))).toList();
        var previousLevels = loggers.stream().map(Logger::getLevel).toList();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.INFO);
        loggers.subList(1, loggers.size()).forEach(logger -> logger.setLevel(Level.DEBUG));
        try {
            call.run();
        } finally {
            root.detachAppender(appender);
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).setLevel(previousLevels.get(i));
            }
        }
        return appender.list.stream()
                .map(event -> event.getThrowableProxy() == null
                        ? event.getFormattedMessage()
                        : "%s%n%s".formatted(event.getFormattedMessage(),
                                ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .toList();
    }
}
