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

    /** The projects of {@code workspaceIds} not drained yet, by workspace id. */
    public Map<String, List<TopicVectorBackfill>> findUndrained(@NonNull Collection<String> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicVectorBackfillDAO.class).findUndrained(workspaceIds))
                .stream()
                .collect(Collectors.groupingBy(TopicVectorBackfill::workspaceId));
    }

    /** The rows of {@code projectIds} in one workspace, by project id. */
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

    /** Starts a backfill for each project that has no row: see {@link TopicVectorBackfillDAO#start}. */
    public void start(@NonNull List<TopicVectorBackfill> backfills) {
        if (backfills.isEmpty()) {
            return;
        }
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).start(backfills);
            return null;
        });
    }

    /**
     * Records one discovery of a workspace's projects, in one transaction: rows not checked since {@code staleBefore}
     * start again, every row is checked at {@code now}, and {@code found} are created or take their new counts.
     */
    public void discovered(@NonNull String workspaceId, @NonNull List<TopicVectorBackfill> found,
            @NonNull Instant now, @NonNull Instant staleBefore) {
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicVectorBackfillDAO.class);
            dao.resetStale(workspaceId, staleBefore, now);
            dao.touch(workspaceId, now);
            if (!found.isEmpty()) {
                dao.discovered(found);
            }
            return null;
        });
    }

    /** Records what a tick's backfill did: see {@link TopicVectorBackfillDAO#record}. */
    public void record(@NonNull List<TopicVectorBackfill.Progress> progress) {
        if (progress.isEmpty()) {
            return;
        }
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).record(progress);
            return null;
        });
    }
}
