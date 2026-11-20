package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorBackfillDAO.Backfill;
import com.comet.opik.domain.topics.TopicVectorBackfillDAO.Progress;
import com.comet.opik.infrastructure.TopicsConfig;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/** The topic vectors tick's reads and writes of its backfill state ({@link TopicVectorBackfillDAO}). */
@ImplementedBy(TopicVectorBackfillServiceImpl.class)
public interface TopicVectorBackfillService {

    /**
     * A workspace the tick has not discovered for this long is stale: the tick stopped for it, so its backfills restart
     * and its drained marks say nothing about the traces since. Twice {@code topics.vectors.longWindowInterval}.
     */
    static Duration staleAfter(@NonNull TopicsConfig.VectorsConfig vectors) {
        return vectors.getLongWindowInterval().toJavaDuration().multipliedBy(2);
    }

    /** The started backfills not drained yet, by workspace. */
    Map<String, List<Backfill>> findUndrained(Collection<String> workspaceIds);

    /** The started backfills of {@code projectIds}, by project. */
    Map<UUID, Backfill> findByKeys(String workspaceId, Collection<UUID> projectIds);

    /** Starts a backfill for each project that has none. */
    void start(List<Backfill> backfills);

    /**
     * One discovery of a workspace's projects, in one transaction: restarts the backfills of a workspace not
     * discovered since {@code staleBefore}, marks it discovered at {@code now} and writes what was found. Returns the
     * rows of deleted projects it removed.
     */
    int discovered(String workspaceId, List<Backfill> found, Instant now, Instant staleBefore);

    void record(List<Progress> progress);

    int deleteByProjects(String workspaceId, Collection<UUID> projectIds);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicVectorBackfillServiceImpl implements TopicVectorBackfillService {

    private final @NonNull TransactionTemplate template;

    @Override
    public Map<String, List<Backfill>> findUndrained(@NonNull Collection<String> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicVectorBackfillDAO.class).findUndrained(workspaceIds))
                .stream()
                .collect(Collectors.groupingBy(Backfill::workspaceId));
    }

    @Override
    public Map<UUID, Backfill> findByKeys(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicVectorBackfillDAO.class).findByKeys(workspaceId, projectIds))
                .stream()
                .collect(Collectors.toMap(Backfill::projectId, Function.identity()));
    }

    @Override
    public void start(@NonNull List<Backfill> backfills) {
        if (backfills.isEmpty()) {
            return;
        }
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).start(backfills);
            return null;
        });
    }

    @Override
    public int discovered(@NonNull String workspaceId, @NonNull List<Backfill> found, @NonNull Instant now,
            @NonNull Instant staleBefore) {
        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicVectorBackfillDAO.class);
            dao.resetStale(workspaceId, staleBefore, now);
            dao.checked(workspaceId, now);
            if (!found.isEmpty()) {
                dao.discovered(found);
            }
            // Last, so a project deleted while the tick read it gets no row back.
            return dao.deleteOfMissingProjects(workspaceId);
        });
    }

    @Override
    public void record(@NonNull List<Progress> progress) {
        if (progress.isEmpty()) {
            return;
        }
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicVectorBackfillDAO.class).record(progress);
            return null;
        });
    }

    @Override
    public int deleteByProjects(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return 0;
        }
        return template.inTransaction(WRITE,
                handle -> handle.attach(TopicVectorBackfillDAO.class).deleteByProjects(workspaceId, projectIds));
    }
}
