package com.comet.opik.domain;

import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.infrastructure.db.MapFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.utils.JsonUtils;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

@RegisterRowMapper(AlertTriggerConfigDAO.AlertTriggerConfigRowMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(MapFlatArgumentFactory.class)
@RegisterColumnMapper(MapFlatArgumentFactory.class)
@RegisterArgumentFactory(AlertTriggerConfigDAO.AlertTriggerConfigTypeArgumentFactory.class)
interface AlertTriggerConfigDAO {

    @SqlBatch("INSERT INTO alert_trigger_configs (id, alert_trigger_id, config_type, config_value, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :alertTriggerId, :bean.type, :bean.configValue, :bean.createdBy, :bean.lastUpdatedBy)")
    void saveBatch(@BindMethods("bean") List<AlertTriggerConfig> alertTriggerConfigs);

    @SqlQuery("SELECT id, alert_trigger_id, config_type, config_value, created_by, last_updated_by, created_at, last_updated_at "
            +
            "FROM alert_trigger_configs WHERE alert_trigger_id IN (<alertTriggerIds>)")
    List<AlertTriggerConfig> findByAlertTriggerIds(@BindList("alertTriggerIds") List<UUID> alertTriggerIds);

    class AlertTriggerConfigTypeArgumentFactory extends AbstractArgumentFactory<AlertTriggerConfigType> {
        public AlertTriggerConfigTypeArgumentFactory() {
            super(Types.VARCHAR);
        }

        @Override
        protected Argument build(AlertTriggerConfigType value, ConfigRegistry config) {
            return (position, statement, ctx) -> {
                if (value == null) {
                    statement.setNull(position, Types.VARCHAR);
                } else {
                    statement.setString(position, value.getValue());
                }
            };
        }
    }

    class AlertTriggerConfigRowMapper implements RowMapper<AlertTriggerConfig> {
        @Override
        public AlertTriggerConfig map(ResultSet rs, StatementContext ctx) throws SQLException {
            String configValueJson = rs.getString("config_value");
            return AlertTriggerConfig.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .alertTriggerId(UUID.fromString(rs.getString("alert_trigger_id")))
                    .type(AlertTriggerConfigType.fromString(rs.getString("config_type")))
                    .configValue(configValueJson != null
                            ? JsonUtils.readValue(configValueJson, MapFlatArgumentFactory.TYPE_REFERENCE)
                            : null)
                    .createdAt(rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant()
                            : null)
                    .createdBy(rs.getString("created_by"))
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at") != null
                            ? rs.getTimestamp("last_updated_at").toInstant()
                            : null)
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .build();
        }
    }
}
