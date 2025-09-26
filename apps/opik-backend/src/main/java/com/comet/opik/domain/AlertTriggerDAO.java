package com.comet.opik.domain;

import com.comet.opik.api.AlertTrigger;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.List;
import java.util.UUID;

@RegisterConstructorMapper(AlertTrigger.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AlertTriggerDAO {

    @SqlBatch("""
            INSERT INTO alert_triggers (id, alert_id, event_type, created_by)
            VALUES (:id, :alertId, :eventType, :createdBy)
            """)
    void saveBatch(@BindMethods List<AlertTrigger> alertTriggers);

    @SqlQuery("SELECT id, alert_id, event_type, created_by, created_at " +
            "FROM alert_triggers WHERE alert_id = :alertId")
    List<AlertTrigger> findByAlertId(@Bind("alertId") UUID alertId);
}
