package com.comet.opik.domain;

import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.infrastructure.db.MapFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.UUID;

@RegisterConstructorMapper(AlertTriggerConfig.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(MapFlatArgumentFactory.class)
@RegisterColumnMapper(MapFlatArgumentFactory.class)
interface AlertTriggerConfigDAO {

    @SqlUpdate("INSERT INTO alert_trigger_configs (id, alert_trigger_id, config_type, config_value, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :alertTriggerId, :bean.type, :bean.configValue, :bean.createdBy, :bean.lastUpdatedBy)")
    void save(@Bind("alertTriggerId") UUID alertTriggerId, @BindMethods("bean") AlertTriggerConfig alertTriggerConfig);

    @SqlQuery("SELECT id, alert_trigger_id, config_type, config_value, created_by, last_updated_by, created_at, last_updated_at "
            +
            "FROM alert_trigger_configs WHERE alert_trigger_id = :alertTriggerId")
    List<AlertTriggerConfig> findByAlertTriggerId(@Bind("alertTriggerId") UUID alertTriggerId);
}
