package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.google.common.collect.Lists;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
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

/** The topic vectors tick's reads and writes of its backfill state ({@link TopicProjectDAO}, {@link TopicVectorBackfillDAO}). */
@ImplementedBy(TopicVectorBackfillServiceImpl.class)
public interface TopicVectorBackfillService {

    /** The started backfills not drained yet, by workspace. */
    Map<String, List<TopicBackfill>> findUndrained(Collection<String> workspaceIds);

    /** The started backfills of {@code projectIds}, by project. */
    Map<UUID, TopicBackfill> findByKeys(String workspaceId, Collection<UUID> projectIds);

    /** Starts a backfill for each project that has none. */
    void start(List<TopicBackfill> backfills);

    /**
     * One discovery of a workspace's projects, in one transaction: restarts the backfills of a workspace not
     * discovered since {@code staleBefore}, marks it discovered at {@code now} and writes what was found. Then removes
     * the rows of deleted projects, {@code topics.storage.deleteBatchSize} projects per transaction, and returns how
     * many it removed.
     */
    int discovered(String workspaceId, List<TopicBackfill> found, Instant now, Instant staleBefore);

    void record(List<TopicBackfillProgress> progress);

    int deleteByProjects(String workspaceId, Collection<UUID> projectIds);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicVectorBackfillServiceImpl implements TopicVectorBackfillService {

    private final @NonNull TransactionTemplate template;
    private final @NonNull @Config("topics") TopicsConfig config;

    @Override
    public Map<String, List<TopicBackfill>> findUndrained(@NonNull Collection<String> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return Map.of();
        }
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findUndrainedBackfills(workspaceIds))
                .stream()
                .collect(Collectors.groupingBy(TopicBackfill::workspaceId));
    }

    @Override
    public Map<UUID, TopicBackfill> findByKeys(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findBackfills(workspaceId, projectIds))
                .stream()
                .collect(Collectors.toMap(TopicBackfill::projectId, Function.identity()));
    }

    @Override
    public void start(@NonNull List<TopicBackfill> backfills) {
        if (backfills.isEmpty()) {
            return;
        }
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicProjectDAO.class).startBackfills(backfills);
            return null;
        });
    }

    @Override
    public int discovered(@NonNull String workspaceId, @NonNull List<TopicBackfill> found, @NonNull Instant now,
            @NonNull Instant staleBefore) {
        template.inTransaction(WRITE, handle -> {
            var projects = handle.attach(TopicProjectDAO.class);
            projects.resetStaleBackfills(workspaceId, staleBefore, now);
            handle.attach(TopicVectorBackfillDAO.class).checked(workspaceId, now);
            if (!found.isEmpty()) {
                projects.discoverBackfills(found);
            }
            return null;
        });
        // After the discovery's commit, so a project deleted while the tick read it gets no row back.
        var missing = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findOfMissingProjects(workspaceId));
        return Lists.partition(missing, config.getStorage().getDeleteBatchSize()).stream()
                .mapToInt(batch -> deleteByProjects(workspaceId, batch))
                .sum();
    }

    @Override
    public void record(@NonNull List<TopicBackfillProgress> progress) {
        if (progress.isEmpty()) {
            return;
        }
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicProjectDAO.class).recordBackfills(progress);
            return null;
        });
    }

    @Override
    public int deleteByProjects(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return 0;
        }
        return template.inTransaction(WRITE,
                handle -> handle.attach(TopicProjectDAO.class).deleteByProjects(workspaceId, projectIds));
    }
}
