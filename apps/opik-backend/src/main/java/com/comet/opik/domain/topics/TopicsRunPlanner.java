package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ConflictException;
import com.comet.opik.domain.topics.TopicProject.RunMode;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ServiceUnavailableException;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiFunction;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * Plans the Topics runs. One plan, on the leader only: settle the runs in flight ({@link TopicsRunReconciler}), then
 * fill the free slots of {@code topics.runs.maxInFlight}, across the cluster, with labelling passes that did not
 * finish and first builds ({@link TopicsFirstBuildCandidates}), one of each in turn. Each run is written
 * {@code queued} on its project's row first, then published on the stream, where any pod claims it.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsRunPlanner {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWorkspaceService workspaceService;
    private final @NonNull TopicRunStore store;
    private final @NonNull TopicsRunReconciler reconciler;
    private final @NonNull TopicsFirstBuildCandidates candidates;
    private final @NonNull TopicsRunPublisher publisher;
    private final @NonNull TopicsBackfillGate backfillGate;

    /** A run to queue: its message once queued, which gives it its id. */
    @Builder(toBuilder = true)
    record Planned(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull RunMode mode, LocalDate night,
            long windowTraces) {

        TopicsRunMessage message(UUID runId) {
            return new TopicsRunMessage(runId, workspaceId, projectId, mode, night, windowTraces);
        }
    }

    /** One plan at {@code start}: the window ends at tomorrow's start (UTC), so it holds today's traces so far. */
    public Mono<Integer> plan(@NonNull Instant start) {
        var night = LocalDate.ofInstant(start, ZoneOffset.UTC).plusDays(1);
        return Mono.fromCallable(workspaceService::enabledWorkspaceIds)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(workspaceIds -> store.findInFlight()
                        .flatMap(runs -> reconciler.reconcile(runs, workspaceIds, night, start))
                        .flatMap(inFlight -> {
                            int free = config.getRuns().getMaxInFlight() - inFlight.size();
                            if (free <= 0 || workspaceIds.isEmpty()) {
                                return Mono.just(0);
                            }
                            return labelsDue(workspaceIds, start)
                                    .zipWith(candidates.find(workspaceIds, night, start))
                                    .flatMap(due -> queue(interleave(due.getT1(), due.getT2(), night), free));
                        }))
                .doOnNext(queued -> log.info("Topics plan done, window until '{}', runs queued '{}'", night, queued));
    }

    /**
     * Queues the project's first build now, for the window that ends at {@code night}'s start, and returns its run id.
     * {@code ignoreBackfill} is the operator's way to build a project whose backfill cannot drain. The retry wait and
     * {@code maxInFlight} do not apply.
     *
     * @throws ConflictException when the workspace is not opted in, the window has too few vectors, the project has a
     *                           list or a run in flight or no trace in the window, or its backfill gate is closed and
     *                           {@code ignoreBackfill} is false
     */
    public Mono<UUID> runByHand(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull LocalDate night,
            boolean ignoreBackfill) {
        var now = Instant.now();
        return Mono.fromCallable(() -> workspaceService.isEnabled(workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(enabled -> enabled
                        ? candidates.findListless(workspaceId, projectId, night)
                        : Mono.error(new ConflictException(("Workspace '%s' is not opted in to topics: it needs to "
                                + "be in topics.enabledWorkspaces and enabled in ai_label_workspaces")
                                .formatted(workspaceId))))
                .flatMap(project -> {
                    if (project.isEmpty()) {
                        return Mono.error(new ConflictException(("Project '%s' has a topic list, a run in flight, "
                                + "or no trace in the window until '%s'").formatted(projectId, night)));
                    }
                    var gate = backfillGate.firstBuildGate(project.get(), now);
                    if (!ignoreBackfill && !gate.isOpen()) {
                        return Mono.error(new ConflictException(("Project '%s' waits for its vectors backfill to "
                                + "drain, gate '%s'; ignore_backfill=true builds it anyway").formatted(projectId,
                                        gate.name().toLowerCase(Locale.ROOT))));
                    }
                    return candidates.windowVectors(workspaceId, projectId, night);
                })
                .flatMap(vectors -> {
                    if (vectors < config.getBuild().getMinVectors()) {
                        return Mono.error(new ConflictException(
                                "Project '%s' has '%d' vectors in the window until '%s', the minimum is '%d'"
                                        .formatted(projectId, vectors, night, config.getBuild().getMinVectors())));
                    }
                    return queueByHand(Planned.builder().workspaceId(workspaceId).projectId(projectId)
                            .mode(RunMode.FIRST_BUILD).night(night).windowTraces(vectors).build())
                            .switchIfEmpty(Mono.error(() -> new ConflictException(
                                    "A topics run of project '%s' is in flight".formatted(projectId))));
                })
                .doOnNext(runId -> log.info("Topics run '{}' queued by hand, workspace '{}', project '{}', window "
                        + "until '{}'", runId, workspaceId, projectId, night));
    }

    /**
     * Queues the run and publishes it; empty when the project has a run in flight. A failed publish leaves the run
     * queued: the next plans publish it again.
     */
    Mono<UUID> queueOne(@NonNull Planned run) {
        return queueAndPublish(run, (runId, error) -> {
            log.warn("Topics run queued but not published, a later plan publishes it, run '{}', project '{}'",
                    runId, run.projectId(), error);
            return Mono.just(runId);
        });
    }

    /**
     * Queues a run started by hand and publishes it. When the publish fails, the run is dropped and the caller gets an
     * error to retry on: a run left queued would block the project's next run by hand until a plan publishes it again.
     * A run that already left the queue was published after all, for example by a publish that timed out after the
     * stream took it, so the caller gets its id.
     */
    private Mono<UUID> queueByHand(Planned run) {
        return queueAndPublish(run, (runId, error) -> {
            log.warn("Topics run by hand not published, dropping it, run '{}', project '{}'", runId,
                    run.projectId(), error);
            // No reason: nothing cancelled the run, no pod ever had it.
            return store.dropQueued(run.workspaceId(), run.projectId(), runId, null)
                    .onErrorResume(dropError -> {
                        log.error("Topics run by hand not published and not dropped, it stays queued until a plan "
                                + "publishes it, run '{}', project '{}'", runId, run.projectId(), dropError);
                        // Not known to have left the queue, so the caller gets the error to retry on.
                        return Mono.just(true);
                    })
                    .flatMap(dropped -> {
                        if (dropped) {
                            return Mono.error(new ServiceUnavailableException(
                                    "The topics run could not be started, try again, project '%s'"
                                            .formatted(run.projectId())));
                        }
                        log.info("Topics run by hand left the queue after its publish error, so it was published, "
                                + "run '{}', project '{}'", runId, run.projectId());
                        return Mono.just(runId);
                    });
        });
    }

    /** Queues the run and publishes it; empty when the project has a run in flight. */
    private Mono<UUID> queueAndPublish(Planned run, BiFunction<UUID, Throwable, Mono<UUID>> onPublishError) {
        return store.queue(run.workspaceId(), run.projectId(), run.mode())
                .flatMap(Mono::justOrEmpty)
                .flatMap(runId -> publisher.publish(run.message(runId))
                        .thenReturn(runId)
                        .onErrorResume(error -> onPublishError.apply(runId, error)));
    }

    private Mono<List<TopicProjectKey>> labelsDue(List<String> workspaceIds, Instant now) {
        var failedAfter = now.minus(config.getFirstBuild().getRetryDelays().getFirst().toJavaDuration());
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findLabelsDue(workspaceIds, failedAfter)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** One labelling pass, then one first build, in turn. */
    private static List<Planned> interleave(List<TopicProjectKey> labels,
            List<TopicsFirstBuildCandidates.Candidate> builds, LocalDate night) {
        var result = new ArrayList<Planned>();
        for (int i = 0; i < Math.max(labels.size(), builds.size()); i++) {
            if (i < labels.size()) {
                var project = labels.get(i);
                result.add(Planned.builder().workspaceId(project.workspaceId()).projectId(project.projectId())
                        .mode(RunMode.LABELS).build());
            }
            if (i < builds.size()) {
                var build = builds.get(i);
                result.add(Planned.builder().workspaceId(build.workspaceId()).projectId(build.projectId())
                        .mode(RunMode.FIRST_BUILD).night(night).windowTraces(build.vectors()).build());
            }
        }
        return result;
    }

    /** Queues the runs in order until {@code free} are queued; a project that got a run meanwhile is skipped. */
    private Mono<Integer> queue(List<Planned> runs, int free) {
        return Flux.fromIterable(runs)
                .concatMap(run -> queueOne(run).onErrorResume(error -> {
                    log.warn("Topics run not queued, project '{}'", run.projectId(), error);
                    return Mono.empty();
                }))
                .take(free)
                .count()
                .map(Long::intValue);
    }
}
