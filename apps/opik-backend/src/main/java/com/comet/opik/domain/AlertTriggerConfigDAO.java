package com.comet.opik.domain;

import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.infrastructure.db.MapFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;

import java.sql.Types;
import java.util.List;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(MapFlatArgumentFactory.class)
@RegisterColumnMapper(MapFlatArgumentFactory.class)
@RegisterArgumentFactory(AlertTriggerConfigDAO.AlertTriggerConfigTypeArgumentFactory.class)
interface AlertTriggerConfigDAO {

    @SqlBatch("INSERT INTO alert_trigger_configs (id, alert_trigger_id, config_type, config_value, created_by, last_updated_by, created_at) "
            +
            "VALUES (:bean.id, :alertTriggerId, :bean.type, :bean.configValue, :bean.createdBy, :bean.lastUpdatedBy, COALESCE(:bean.createdAt, CURRENT_TIMESTAMP(6)))")
    void saveBatch(@BindMethods("bean") List<AlertTriggerConfig> alertTriggerConfigs);

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
}
