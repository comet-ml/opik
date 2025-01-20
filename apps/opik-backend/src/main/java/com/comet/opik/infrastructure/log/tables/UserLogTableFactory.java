package com.comet.opik.infrastructure.log.tables;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.comet.opik.domain.UserLog;
import io.r2dbc.spi.ConnectionFactory;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.List;

public interface UserLogTableFactory {

    static UserLogTableFactory getInstance(ConnectionFactory factory) {
        return new UserLogTableFactoryImpl(factory);
    }

    interface UserLogTableDAO {
        Mono<Void> saveAll(List<ILoggingEvent> events);
    }

    UserLogTableDAO getDAO(UserLog userLog);

}

@RequiredArgsConstructor
class UserLogTableFactoryImpl implements UserLogTableFactory {

    private final ConnectionFactory factory;

    @Override
    public UserLogTableDAO getDAO(@NonNull UserLog userLog) {
        return switch (userLog) {
            case AUTOMATION_RULE_EVALUATOR -> new AutomationRuleEvaluatorLogDAO(factory);
        };
    }
}
