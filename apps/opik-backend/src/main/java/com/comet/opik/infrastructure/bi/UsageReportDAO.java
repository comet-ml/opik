package com.comet.opik.infrastructure.bi;

import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

@ImplementedBy(UsageReportDAOImpl.class)
interface UsageReportDAO {

    Optional<String> getAnonymousId();

    void saveAnonymousId(@NonNull String id);

    boolean isEventReported(@NonNull String eventType);

    void addEvent(@NonNull String eventType);

    void markEventAsReported(@NonNull String eventType);
}

@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
class UsageReportDAOImpl implements UsageReportDAO {

    private final Jdbi jdbi;

    public Optional<String> getAnonymousId() {
        return jdbi.inTransaction(handle -> handle.createQuery("SELECT value FROM metadata WHERE `key` = :key")
                .bind("key", Metadata.anonymous_id)
                .mapTo(String.class)
                .findFirst());
    }

    public void saveAnonymousId(@NonNull String id) {
        jdbi.useHandle(handle -> handle.createUpdate("INSERT INTO metadata (`key`, value) VALUES (:key, :value)")
                .bind("key", Metadata.anonymous_id)
                .bind("value", id)
                .execute());
    }

    public boolean isEventReported(@NonNull String eventType) {
        return jdbi.inTransaction(handle -> handle.createQuery("SELECT COUNT(*) > 0 FROM usage_information WHERE event_type = :eventType AND reported_at IS NOT NULL")
                .bind("eventType", eventType)
                .mapTo(Boolean.class)
                .one());
    }

    public void addEvent(@NonNull String eventType) {
        try {
            jdbi.useHandle(handle -> handle.createUpdate("INSERT INTO usage_information (event_type) VALUES (:eventType)")
                    .bind("eventType",eventType)
                    .execute());
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                log.warn("Event type already exists: {}", eventType);
            } else {
                log.error("Failed to add event", e);
            }
        }
    }

    public void markEventAsReported(@NonNull String eventType) {
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE usage_information SET reported_at = current_timestamp(6) WHERE event_type = :eventType")
                .bind("eventType", eventType)
                .execute());
    }
}
