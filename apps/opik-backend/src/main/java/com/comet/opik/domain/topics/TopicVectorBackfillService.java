package com.comet.opik.domain.topics;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/** The topic vectors tick's reads and writes of {@code topic_vector_backfills}, each in its own transaction. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicVectorBackfillService {

    private final @NonNull TransactionTemplate template;

    public Map<String, List<TopicVectorBackfill>> findUndrained(@NonNull Collection<String> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicVectorBackfillDAO.class).findUndrained(workspaceIds))
                .stream()
                .collect(Collectors.groupingBy(TopicVectorBackfill::workspaceId));
    }

    public Map<UUID, TopicVectorBackfill> findByKeys(@NonNull String workspaceId,
            @NonNull Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicVectorBackfillDAO.class).findByKeys(workspaceId, projectIds))
                .stream()
                .collect(Collectors.toMap(TopicVectorBackfill::projectId, Function.identity()));
    }

    public void start(@NonNull List<TopicVectorBackfill> backfills) {
        if (backfills.isEmpty()) {
            return;
        }
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).start(backfills);
            return null;
        });
    }

    /** One discovery of a workspace's projects, in one transaction. Returns the rows of deleted projects removed. */
    public int discovered(@NonNull String workspaceId, @NonNull List<TopicVectorBackfill> found,
            @NonNull Instant now, @NonNull Instant staleBefore) {
        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicVectorBackfillDAO.class);
            dao.resetStale(workspaceId, staleBefore, now);
            dao.touch(workspaceId, now);
            if (!found.isEmpty()) {
                dao.discovered(found);
            }
            // Last, so a project deleted while the tick read it gets no row back.
            return dao.deleteOfMissingProjects(workspaceId);
        });
    }

    public void record(@NonNull List<TopicVectorBackfill.Progress> progress) {
        if (progress.isEmpty()) {
            return;
        }
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).record(progress);
            return null;
        });
    }

    public int deleteByProjects(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return 0;
        }
        return template.inTransaction(WRITE,
                handle -> handle.attach(TopicVectorBackfillDAO.class).deleteByProjects(workspaceId, projectIds));
    }
}
