package com.comet.opik.infrastructure.bi;

import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

import static com.comet.opik.infrastructure.db.TransactionTemplate.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplate.WRITE;

@ImplementedBy(UsageReportServiceImpl.class)
interface UsageReportService {

    Optional<String> getAnonymousId();

    void saveAnonymousId(@NonNull String id);

    boolean isEventReported(@NonNull String eventType);

    void markEventAsReported(@NonNull String eventType);
}

@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
class UsageReportServiceImpl implements UsageReportService {

    private final @NonNull TransactionTemplate template;

    public Optional<String> getAnonymousId() {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(MetadataDAO.class).getMetadataKey(Metadata.ANONYMOUS_ID.getValue()));
    }

    public void saveAnonymousId(@NonNull String id) {
        template.inTransaction(WRITE, handle -> {
            handle.attach(MetadataDAO.class).saveMetadataKey(Metadata.ANONYMOUS_ID.getValue(), id);
            return null;
        });
    }

    public boolean isEventReported(@NonNull String eventType) {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(UsageReportDAO.class).isEventReported(eventType));
    }

    public void markEventAsReported(@NonNull String eventType) {
        try {
            template.inTransaction(WRITE, handle -> {
                handle.attach(UsageReportDAO.class).markEventAsReported(eventType);
                return null;
            });
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                log.warn("Event type already exists: {}", eventType);
            } else {
                log.error("Failed to add event", e);
            }
        }
    }
}