package com.comet.opik.infrastructure.log;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.comet.opik.infrastructure.log.tables.UserLogTableFactory;
import io.r2dbc.spi.ConnectionFactory;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.slf4j.LoggerFactory;

import java.time.Duration;

@UtilityClass
public class UserFacingLoggingFactory {

    private static final LoggerContext CONTEXT = (LoggerContext) LoggerFactory.getILoggerFactory();
    private static AsyncAppender asyncAppender;

    public static synchronized void init(@NonNull ConnectionFactory connectionFactory, int batchSize,
            @NonNull Duration flushIntervalSeconds) {

        UserLogTableFactory tableFactory = UserLogTableFactory.getInstance(connectionFactory);
        ClickHouseAppender clickHouseAppender = ClickHouseAppender.init(tableFactory, batchSize, flushIntervalSeconds,
                CONTEXT);

        asyncAppender = new AsyncAppender();
        asyncAppender.setContext(CONTEXT);
        asyncAppender.setNeverBlock(true);
        asyncAppender.setIncludeCallerData(true);
        asyncAppender.addAppender(clickHouseAppender);
        asyncAppender.start();

        addShutdownHook();
    }

    private static void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(CONTEXT::stop));
    }

    public static org.slf4j.Logger getLogger(@NonNull Class<?> clazz) {
        Logger logger = CONTEXT.getLogger("%s.UserFacingLog".formatted(clazz.getName()));
        logger.addAppender(asyncAppender);
        logger.setAdditive(false);
        return logger;
    }

}
