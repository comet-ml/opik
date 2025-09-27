package com.comet.opik.domain;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

@RegisterRowMapper(AlertTriggerDAO.AlertTriggerRowMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(AlertTriggerDAO.AlertEventTypeArgumentFactory.class)
interface AlertTriggerDAO {

    @SqlBatch("""
            INSERT INTO alert_triggers (id, alert_id, event_type, created_by)
            VALUES (:id, :alertId, :eventType, :createdBy)
            """)
    void saveBatch(@BindMethods List<AlertTrigger> alertTriggers);

    @SqlQuery("SELECT id, alert_id, event_type, created_by, created_at " +
            "FROM alert_triggers WHERE alert_id = :alertId")
    List<AlertTrigger> findByAlertId(@Bind("alertId") UUID alertId);

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

    class AlertTriggerRowMapper implements RowMapper<AlertTrigger> {
        @Override
        public AlertTrigger map(ResultSet rs, StatementContext ctx) throws SQLException {
            return AlertTrigger.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .alertId(UUID.fromString(rs.getString("alert_id")))
                    .eventType(AlertEventType.fromString(rs.getString("event_type")))
                    .triggerConfigs(null) // Will be set separately by the service
                    .createdAt(rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant()
                            : null)
                    .createdBy(rs.getString("created_by"))
                    .build();
        }
    }
}
