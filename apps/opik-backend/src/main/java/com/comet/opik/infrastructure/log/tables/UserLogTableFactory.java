package com.comet.opik.infrastructure.log.tables;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.comet.opik.domain.alerts.AlertEventLogsDAO;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorLogsDAO;
import com.comet.opik.domain.evaluators.UserLog;
import io.r2dbc.spi.ConnectionFactory;
import lombok.NonNull;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface UserLogTableFactory {

    static UserLogTableFactory getInstance(@NonNull ConnectionFactory factory) {
        return new UserLogTableFactoryImpl(factory);
    }

    interface UserLogTableDAO {
        Mono<Void> saveAll(List<ILoggingEvent> events);
    }

    UserLogTableDAO getDAO(UserLog userLog);

}

class UserLogTableFactoryImpl implements UserLogTableFactory {

    private final Map<UserLog, UserLogTableDAO> daoMap;

    UserLogTableFactoryImpl(@NonNull ConnectionFactory factory) {
        daoMap = Map.of(
                UserLog.AUTOMATION_RULE_EVALUATOR, AutomationRuleEvaluatorLogsDAO.create(factory),
                UserLog.ALERT_EVENT, AlertEventLogsDAO.create(factory));
    }

    @Override
    public UserLogTableDAO getDAO(@NonNull UserLog userLog) {
        return daoMap.get(userLog);
    }
}
