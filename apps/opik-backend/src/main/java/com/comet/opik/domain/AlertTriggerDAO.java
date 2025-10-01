package com.comet.opik.domain;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;

import java.sql.Types;
import java.util.List;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(AlertTriggerDAO.AlertEventTypeArgumentFactory.class)
interface AlertTriggerDAO {

    @SqlBatch("""
            INSERT INTO alert_triggers (id, alert_id, event_type, created_by, created_at)
            VALUES (:id, :alertId, :eventType, :createdBy, COALESCE(:createdAt, CURRENT_TIMESTAMP(6)))
            """)
    void saveBatch(@BindMethods List<AlertTrigger> alertTriggers);

    class AlertEventTypeArgumentFactory extends AbstractArgumentFactory<AlertEventType> {
        public AlertEventTypeArgumentFactory() {
            super(Types.VARCHAR);
        }

        @Override
        protected Argument build(AlertEventType value, ConfigRegistry config) {
            return (position, statement, ctx) -> {
                if (value == null) {
                    statement.setNull(position, Types.VARCHAR);
                } else {
                    statement.setString(position, value.getValue());
                }
            };
        }
    }
}
