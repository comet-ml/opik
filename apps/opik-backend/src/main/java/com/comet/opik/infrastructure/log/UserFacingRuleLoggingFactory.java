package com.comet.opik.infrastructure.log;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.r2dbc.spi.ConnectionFactory;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.slf4j.LoggerFactory;

import java.time.Duration;

@UtilityClass
public class UserFacingRuleLoggingFactory {

    static final LoggerContext CONTEXT = (LoggerContext) LoggerFactory.getILoggerFactory();
    static final AsyncAppender ASYNC_APPENDER = new AsyncAppender();

    public static void init(@NonNull ConnectionFactory connectionFactory, int batchSize,
            @NonNull Duration flushIntervalSeconds) {
        ClickHouseAppender.init(connectionFactory, batchSize, flushIntervalSeconds);

        ClickHouseAppender clickHouseAppender = ClickHouseAppender.getInstance();
        clickHouseAppender.setContext(CONTEXT);

        ASYNC_APPENDER.setContext(CONTEXT);
        ASYNC_APPENDER.setNeverBlock(true);
        ASYNC_APPENDER.setIncludeCallerData(true);
        ASYNC_APPENDER.addAppender(clickHouseAppender);
        ASYNC_APPENDER.start();

        Runtime.getRuntime().addShutdownHook(new Thread(CONTEXT::stop));
    }

    public static org.slf4j.Logger getLogger(Class<?> clazz) {
        Logger logger = CONTEXT.getLogger("%s.UserFacingLog".formatted(clazz.getName()));
        logger.addAppender(ASYNC_APPENDER);
        logger.setAdditive(false);
        return logger;
    }

}
