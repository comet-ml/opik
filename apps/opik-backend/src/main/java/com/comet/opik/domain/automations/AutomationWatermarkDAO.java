package com.comet.opik.domain.automations;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Where each counted trigger last fired. */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(AutomationWatermarkDAO.Watermark.class)
public interface AutomationWatermarkDAO {

    record Watermark(UUID automationId, String triggerKey, Instant lastFiredAt, int lastCount) {
    }

    @SqlQuery("SELECT automation_id, trigger_key, last_fired_at, last_count FROM automation_watermarks "
            + "WHERE automation_id = :automationId AND trigger_key = :triggerKey")
    Optional<Watermark> find(@Bind("automationId") UUID automationId, @Bind("triggerKey") String triggerKey);

    @SqlUpdate("""
            INSERT INTO automation_watermarks (automation_id, trigger_key, last_fired_at, last_count)
            VALUES (:automationId, :triggerKey, :lastFiredAt, :lastCount)
            ON DUPLICATE KEY UPDATE last_fired_at = :lastFiredAt, last_count = :lastCount
            """)
    void save(@Bind("automationId") UUID automationId, @Bind("triggerKey") String triggerKey,
            @Bind("lastFiredAt") Instant lastFiredAt, @Bind("lastCount") int lastCount);
}
