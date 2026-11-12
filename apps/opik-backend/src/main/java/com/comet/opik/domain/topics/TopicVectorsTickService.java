package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.EmbedRequest;
import com.comet.opik.infrastructure.inference.EmbedResponse;
import com.comet.opik.infrastructure.inference.EmbedResult;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceErrorCode;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import com.google.common.collect.Lists;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * One topic vectors tick: for every workspace topics and ai-labels are enabled for, find the traces whose latest
 * {@code trace_summary} status is done and that have no vector yet, embed their summaries with {@code /v1/embed} and
 * store the unit-length vectors in {@code topic_vectors}. Runs on the leader only. A tick has three parts:
 * <ul>
 * <li>The fresh phase, every tick: per workspace, one read of the done statuses written in {@code window} (24h), all
 * its drained projects together, up to {@code maxTracesPerTick}, oldest first. It keeps new traces served.</li>
 * <li>The backfill phase, every tick, with the budget the fresh phase left: the projects not drained yet, one at a
 * time. A project's read covers the long window (or the backfill window) and only that project, so it follows the
 * tables' sort keys; a large project is read in {@code find.sliceSize} slices of trace creation time, the newest
 * first. A project whose read finds fewer than {@code maxTracesPerTick} traces, all embedded, is marked drained in
 * {@code topic_vector_backfills}, and the tick goes on to the next one. Workspaces take turns, one project each; a
 * workspace's projects go in {@link TopicVectorBackfill#backfillOrder}.</li>
 * <li>The daily pass, once per {@code longWindowInterval}, in place of that tick's fresh read: the same read over the
 * long window, in slices, for rows the fresh phase missed. Until one of its ticks drains it, it runs every second
 * tick.</li>
 * </ul>
 * The fresh phase and the daily pass leave out the projects not drained yet, and the backfill phase reads only those,
 * so a trace is embedded by one phase in a tick, and a project's backlog is never embedded mixed with the others'. A
 * project the fresh phase sees for the first time gets its row there and its traces go to the backfill phase of the
 * same tick. About once per {@code longWindowInterval} per workspace, the tick discovers the projects with done
 * statuses in the long window, with two plain aggregates, and creates or refreshes their rows.
 *
 * <p>ClickHouse alone says which traces have no vector: every read is the done statuses less the keys already in
 * {@code topic_vectors}, so a repeated, overlapping or late tick changes nothing, and a leader change loses nothing.
 * The rows in MySQL only say which projects the backfill still has to read. Work per tick is bounded per read by
 * {@code maxTracesPerTick} and in total by the tick budget; what is left is found again by the next tick. Workspaces
 * are visited from an offset that moves every tick, so none is always last.
 */
@Slf4j
@Singleton
public class TopicVectorsTickService {

    static final String TASK = "trace_summary";
    static final String EMBED_MODEL = "bge-small-en-v1.5";
    static final int EMBED_DIMENSIONS = 384;

    static final String MISSING_SUMMARY = "missing_summary";
    static final String EMBED_FAILED = "embed_failed";
    static final String INVALID_VECTOR = "invalid_vector";
    static final String OVER_BUDGET = "over_budget";
    static final String INSERT_FAILED = "insert_failed";
    static final String READ_FAILED = "read_failed";
    static final String TRACE_DELETED = "trace_deleted";

    /**
     * The most undrained projects a workspace's fresh read or daily pass leaves out by id: the ids go into the query
     * text, about 40 bytes each. A workspace with more is not read by those two until its backfill brings it under.
     */
    static final int MAX_EXCLUDED_PROJECTS = 2_000;
    /** The most workspaces whose projects one tick discovers, so a new leader's first ticks still serve traces. */
    static final int MAX_DISCOVERIES_PER_TICK = 5;
    /**
     * A project with at most this many traces in the backfill window, as discovered ({@code traces_total}), is read in
     * one query over the whole window; a larger one in {@code find.sliceSize} slices. Measured on ClickHouse 26.3 with
     * the real DDL, 100 projects in a workspace and a 30d window: one read of a project costs about 0.45 KB of memory
     * and 6 rows read per trace (224k traces: 98 MB, 1.3M rows, 0.3 s; 1.2M traces: 447 MB, 5.8M rows, 1.6 s), while a
     * one-day slice of the 1.2M-trace project costs 23 MB. At this value one read stays near 110 MB and 1.5M rows, an
     * eighth of the default {@code find.maxMemoryUsage}, which leaves room for the count being approximate.
     */
    static final long ONE_SLICE_MAX_TRACES = 250_000;

    /**
     * Present while the daily long-window pass is not due. A tick that finds it absent sets it for one lease and runs the
     * pass. When the pass drains, the tick sets it again for {@code longWindowInterval}; otherwise it sets it to expire
     * 1.5 tick intervals after the tick started, so the next tick is a normal one and the one after runs the pass
     * again. A leader that dies mid-pass leaves it to expire with its lease.
     */
    static final Lock LONG_WINDOW_LOCK = new Lock("topics:vectors:long_window");
    // The insert after the last embed call, which has no deadline of its own, and the status check after it, which has
    // at least a second, still end within this past the rest.
    private static final Duration LEASE_SLACK = Duration.ofSeconds(5);
    // ClickHouse's max_execution_time is in whole seconds: a window read starts only with at least one left.
    private static final Duration MIN_READ_TIME = Duration.ofSeconds(1);

    private final TopicsConfig config;
    private final AiLabelsConfig aiLabelsConfig;
    private final TopicWorkspaceService workspaces;
    private final TopicVectorsDAO dao;
    private final TopicVectorBackfillService backfills;
    private final InferenceClient client;
    private final LockService lockService;
    private final AiLabelTaskRegistry registry;
    private final TopicsWorkspaceNames workspaceNames;
    private final Clock clock;

    /**
     * The oldest done status without a vector, per workspace, as this pod's latest tick found it, and when. Only the
     * leader ticks, so on any other pod it is null or goes stale; a stale one is not published.
     */
    private volatile OldestSnapshot lastOldest;
    /** Per workspace, the projects not drained after this pod's latest tick; published like {@link #lastOldest}. */
    private volatile UndrainedSnapshot lastUndrained;
    /**
     * When this pod last discovered each workspace's projects. Only a hint for when to discover again: a new leader
     * starts empty and discovers every workspace once more, which changes no row's {@code drained_at}.
     */
    private final Map<String, Instant> discoveredAt = new ConcurrentHashMap<>();
    /** The workspaces whose workspace reads this pod is skipping, each logged once until it clears. */
    private final Set<String> overExcluded = ConcurrentHashMap.newKeySet();

    private final LongCounter written;
    private final LongCounter skipped;
    private final LongCounter embedFailures;
    private final LongCounter readFailures;
    private final LongCounter workspacesNotRead;
    private final LongCounter longWindowMissed;
    private final DoubleHistogram embedDuration;
    private final DoubleHistogram phaseDuration;

    /**
     * What one tick did: the vectors it wrote, the traces it skipped by reason, whether its workspace reads were the
     * daily long-window pass, and the projects its backfill phase drained.
     */
    @Builder(toBuilder = true)
    record TickResult(int written, @NonNull Map<String, Integer> skipped, boolean longWindowPass,
            int projectsDrained) {

        TickResult(int written, Map<String, Integer> skipped, boolean longWindowPass) {
            this(written, skipped, longWindowPass, 0);
        }

        TickResult {
            skipped = Map.copyOf(skipped);
        }
    }

    private record OldestSnapshot(@NonNull Instant at, @NonNull Map<String, Oldest> oldest) {
    }

    /**
     * One workspace's oldest done status without a vector ({@link Instant#MAX} with none), and the workspace's name as
     * the tick resolved it: the gauge's callback must not look names up.
     */
    private record Oldest(@NonNull String workspaceName, @NonNull Instant statusAt) {
    }

    private record UndrainedSnapshot(@NonNull Instant at, @NonNull Map<String, Undrained> undrained) {
    }

    /** One workspace's projects not drained yet: how many, and when the oldest of their backfills started. */
    private record Undrained(@NonNull String workspaceName, int projects, @Nullable Instant oldestStartedAt) {
    }

    /**
     * The workspace reads' outcome: the candidates, in visiting order, and whether they saw everything in the window:
     * every workspace read and none at its cap.
     */
    private record Found(@NonNull List<TopicVectorCandidate> candidates, boolean complete) {
    }

    /**
     * One window read, of a workspace or of a project: the candidates, the newest slice's first and each slice's
     * oldest first, and whether the read saw the whole window: every slice read, under the cap in all.
     */
    private record WindowRead(@NonNull List<TopicVectorCandidate> candidates, boolean complete) {
    }

    /** One project's turn in the backfill phase: what to record, and whether the project should get another turn. */
    private record Turn(@NonNull Optional<TopicVectorBackfill.Progress> progress, boolean more) {
    }

    /** The bounds every window read of a tick has in common. */
    private record Window(@NonNull Instant lowerBound, @NonNull Instant createdAfter, @NonNull Instant start,
            @NonNull Instant deadline) {
    }

    /**
     * The counts one tick keeps while it runs, reported both as metrics and in its {@link TickResult}, and the names of
     * the workspaces it reads.
     */
    private final class Tally {
        private final Map<String, Integer> skipped = new HashMap<>();
        // Looked up once per tick, before the workspace's window read, so no later step waits on the name service.
        private final Map<String, String> names = new HashMap<>();
        private int written;
        // How many times candidates were left for the next tick for want of time, a read, a back-off or an insert.
        private int cuts;
        // The workspaces an embed call backed off for: not called again this tick, in either phase.
        private final Set<String> backedOff = new HashSet<>();

        String name(String workspaceId) {
            return names.computeIfAbsent(workspaceId, workspaceNames::name);
        }

        /** Both workspace attributes, to add a metric's own attributes to. */
        AttributesBuilder workspace(String workspaceId) {
            return TopicsWorkspaceNames.builder(workspaceId, name(workspaceId));
        }

        void skip(String workspaceId, String reason, int traces) {
            if (traces > 0) {
                skipped.merge(reason, traces, Integer::sum);
                TopicVectorsTickService.this.skipped.add(traces,
                        workspace(workspaceId).put(stringKey("reason"), reason).build());
            }
        }

        void cut(String workspaceId, String reason, int traces) {
            skip(workspaceId, reason, traces);
            if (traces > 0) {
                cuts++;
            }
        }

        void written(String workspaceId, int rows) {
            written += rows;
            TopicVectorsTickService.this.written.add(rows, workspace(workspaceId).build());
        }
    }

    /** Trace ids per summary read: about 40 KB of SQL. */
    static final int SUMMARY_READ_CHUNK = 1_000;

    private record Pending(TopicVectorCandidate candidate, String text) {
    }

    /**
     * What one chunk's turn made: its vectors, whether an embed call was made for it, and what to count, for the
     * tick's thread to apply.
     */
    private record Embedded(List<TopicVector> vectors, boolean called, List<Consumer<Tally>> effects) {

        static Embedded notCalled(Consumer<Tally> effect) {
            return new Embedded(List.of(), false, List.of(effect));
        }
    }

    @Inject
    public TopicVectorsTickService(@NonNull @Config("topics") TopicsConfig config,
            @NonNull @Config("aiLabels") AiLabelsConfig aiLabelsConfig, @NonNull TopicWorkspaceService workspaces,
            @NonNull TopicVectorsDAO dao, @NonNull TopicVectorBackfillService backfills,
            @NonNull InferenceClient client, @NonNull LockService lockService,
            @NonNull AiLabelTaskRegistry registry, @NonNull TopicsWorkspaceNames workspaceNames) {
        this(config, aiLabelsConfig, workspaces, dao, backfills, client, lockService, registry, workspaceNames,
                GlobalOpenTelemetry.get().getMeter("opik.topics"), Clock.systemUTC());
    }

    TopicVectorsTickService(@NonNull TopicsConfig config, @NonNull AiLabelsConfig aiLabelsConfig,
            @NonNull TopicWorkspaceService workspaces, @NonNull TopicVectorsDAO dao,
            @NonNull TopicVectorBackfillService backfills, @NonNull InferenceClient client,
            @NonNull LockService lockService, @NonNull AiLabelTaskRegistry registry,
            @NonNull TopicsWorkspaceNames workspaceNames, @NonNull Meter meter, @NonNull Clock clock) {
        this.config = config;
        this.aiLabelsConfig = aiLabelsConfig;
        this.workspaces = workspaces;
        this.dao = dao;
        this.backfills = backfills;
        this.client = client;
        this.lockService = lockService;
        this.registry = registry;
        this.workspaceNames = workspaceNames;
        this.clock = clock;

        meter.gaugeBuilder("topics.vectors.oldest_unvectored_seconds").setUnit("s")
                .setDescription("Age of the oldest done trace_summary without a vector, per enabled workspace, among "
                        + "the projects whose backfill has drained, as the latest tick's workspace read found it; 0 "
                        + "with none. Projects still in backfill are not in it: see topics.vectors.backfill.*. "
                        + "Published only by the pod that ticked within two tick intervals; a workspace whose read "
                        + "failed or was not reached has no value")
                .buildWithCallback(measurement -> fresh(lastOldest, OldestSnapshot::at)
                        .ifPresent(snapshot -> {
                            var now = clock.instant();
                            snapshot.oldest().forEach((workspaceId, oldest) -> measurement.record(
                                    oldest.statusAt().equals(Instant.MAX) ? 0 : seconds(oldest.statusAt(), now),
                                    TopicsWorkspaceNames.builder(workspaceId, oldest.workspaceName()).build()));
                        }));
        meter.gaugeBuilder("topics.vectors.backfill.undrained_projects")
                .setDescription("Projects whose backfill has not drained, per enabled workspace, after the latest "
                        + "tick. Published only by the pod that ticked within two tick intervals")
                .buildWithCallback(measurement -> fresh(lastUndrained, UndrainedSnapshot::at)
                        .ifPresent(snapshot -> snapshot.undrained().forEach((workspaceId, undrained) -> measurement
                                .record(undrained.projects(),
                                        TopicsWorkspaceNames.builder(workspaceId, undrained.workspaceName())
                                                .build()))));
        meter.gaugeBuilder("topics.vectors.backfill.oldest_started_seconds").setUnit("s")
                .setDescription("Age of the oldest backfill not drained yet, per enabled workspace, from its "
                        + "started_at; 0 with none. Published only by the pod that ticked within two tick intervals")
                .buildWithCallback(measurement -> fresh(lastUndrained, UndrainedSnapshot::at)
                        .ifPresent(snapshot -> {
                            var now = clock.instant();
                            snapshot.undrained().forEach((workspaceId, undrained) -> measurement.record(
                                    undrained.oldestStartedAt() == null
                                            ? 0
                                            : seconds(undrained.oldestStartedAt(), now),
                                    TopicsWorkspaceNames.builder(workspaceId, undrained.workspaceName()).build()));
                        }));
        this.written = meter.counterBuilder("topics.vectors.written")
                .setDescription("Vectors written to topic_vectors, by workspace")
                .build();
        this.skipped = meter.counterBuilder("topics.vectors.skipped")
                .setDescription("Traces a tick found but did not write, by reason and workspace; all but "
                        + "trace_deleted are found again by a later tick")
                .build();
        this.embedFailures = meter.counterBuilder("topics.vectors.embed.failures")
                .setDescription("Embed calls that failed, by HTTP status (0 without a response), code, error type and "
                        + "workspace")
                .build();
        this.readFailures = meter.counterBuilder("topics.vectors.read.failures")
                .setDescription("Reads that failed, by phase: find (one workspace's window read), summaries (one "
                        + "summary read), discover (one workspace's project discovery) or backfill (one project's "
                        + "window read), workspace and error type")
                .build();
        this.workspacesNotRead = meter.counterBuilder("topics.vectors.workspaces_not_read")
                .setDescription("Enabled workspaces a tick's workspace read did not read, by workspace: its budget "
                        + "ran out, or the workspace has more projects in backfill than the read can leave out (see "
                        + "topics.vectors.backfill.undrained_projects). Such a workspace has no value in "
                        + "topics.vectors.oldest_unvectored_seconds for that tick")
                .build();
        this.longWindowMissed = meter.counterBuilder("topics.vectors.long_window.missed")
                .setDescription("Vectors the long-window pass wrote for statuses older than the short window, that is, "
                        + "rows the short window missed, by workspace; should stay near zero")
                .build();
        this.embedDuration = meter.histogramBuilder("topics.vectors.embed.duration_seconds").setUnit("s")
                .setDescription("Wall time of one embed call, by outcome (ok or the error code) and workspace")
                .build();
        this.phaseDuration = meter.histogramBuilder("topics.vectors.tick.phase_seconds").setUnit("s")
                .setDescription("Duration of each tick phase: discover, find, embed, backfill")
                .build();
    }

    /**
     * How long the leader lock must last: the tick budget, plus what can run past its end: an embed call started with
     * {@code embedTimeout} left, a window read started just before it, and the insert after the last call. The same
     * shape as {@code AiLabelsTickService.leaderLease()}.
     */
    public Duration leaderLease() {
        var vectors = config.getVectors();
        return vectors.getTickBudget().toJavaDuration()
                .plus(vectors.getEmbedTimeout().toJavaDuration())
                .plus(vectors.getFind().getMaxExecutionTime().toJavaDuration())
                .plus(LEASE_SLACK);
    }

    /**
     * Runs one tick.
     *
     * @throws IllegalStateException when ai-labels has no {@value #TASK} task: no tick can run, so the job counts the
     *         tick as failed rather than as one that ran and found nothing
     */
    public TickResult tick(@NonNull Instant start) {
        var vectors = config.getVectors();
        var deadline = start.plus(vectors.getTickBudget().toJavaDuration());
        var task = registry.find(TASK)
                .orElseThrow(() -> new IllegalStateException("ai-labels has no task '%s'".formatted(TASK)));
        boolean longWindowPass = startLongWindowIfDue();
        var appliesFrom = earliestAppliesFrom(task);
        var lowerBound = lowerBound(appliesFrom, start,
                (longWindowPass ? longWindow(vectors) : vectors.getWindow()).toJavaDuration());
        if (lowerBound.isAfter(start)) {
            if (longWindowPass) {
                finishLongWindow(start, true);
            }
            return TickResult.builder().skipped(Map.of()).longWindowPass(longWindowPass).build();
        }
        var horizon = aiLabelsConfig.getHorizon().toJavaDuration();
        var slack = vectors.getFind().getCreatedAtSlack().toJavaDuration();
        var window = new Window(lowerBound, createdAfter(lowerBound, horizon, slack), start, deadline);
        var backfillLowerBound = lowerBound(appliesFrom, start, longWindow(vectors).toJavaDuration());
        var backfillWindow = new Window(backfillLowerBound, createdAfter(backfillLowerBound, horizon, slack), start,
                deadline);

        var tally = new Tally();
        var workspaceIds = rotated(workspaces.eligibleWorkspaceIds(), tickSequence(start));
        timed("discover", () -> discover(workspaceIds, backfillWindow, tally));
        // Mutable: the fresh phase adds the projects it sees for the first time.
        var undrained = new HashMap<String, List<TopicVectorBackfill>>(backfills.findUndrained(workspaceIds));
        var found = timed("find", () -> find(workspaceIds, window, longWindowPass, undrained, tally));
        // Only the long-window pass can write a status older than the short window: those are the rows it recovers.
        var missedBefore = longWindowPass ? start.minus(vectors.getWindow().toJavaDuration()) : null;
        timed("embed", () -> embedAndWrite(found.candidates(), deadline, missedBefore, tally));
        if (longWindowPass) {
            finishLongWindow(start, found.complete() && tally.cuts == 0);
        }
        var drained = timed("backfill", () -> backfill(workspaceIds, undrained, backfillWindow, tally));
        lastUndrained = undrainedSnapshot(workspaceIds, undrained, drained, tally);
        return TickResult.builder().written(tally.written).skipped(tally.skipped).longWindowPass(longWindowPass)
                .projectsDrained(drained.size()).build();
    }

    /** The long-window pass's window: the backfill window when one is set, else {@code longWindow}. */
    static io.dropwizard.util.Duration longWindow(@NonNull TopicsConfig.VectorsConfig vectors) {
        return vectors.getBackfillWindow().toMilliseconds() > 0 ? vectors.getBackfillWindow() : vectors.getLongWindow();
    }

    /** True when the daily pass is due; it is then held by this tick for one lease. Redis down: not due. */
    private boolean startLongWindowIfDue() {
        return lockService.lockUsingToken(LONG_WINDOW_LOCK, leaderLease())
                .onErrorResume(error -> {
                    log.warn("Topic vectors long-window lock unavailable, running the short window", error);
                    return Mono.just(false);
                })
                .blockOptional()
                .orElse(false);
    }

    /**
     * Drained: the pass is not due again for {@code longWindowInterval}, counted from now, the end of the tick that
     * drained it. A pass is drained when its tick read every workspace, found fewer than the cap in each, and left
     * nothing for want of time, a read, a back-off or an insert.
     *
     * <p>Not drained: the pass is not due until 1.5 tick intervals after this tick's {@code start}, so it runs every
     * second tick. It is counted from the start, not from now, so the pace is the same for a tick that ends at once and
     * for one that uses its whole budget. Ticks start 59 to 61 s apart at the default interval (each pod fires at its
     * own second, and the job's tick lock lasts one interval less a second): the next one starts before the 1.5
     * intervals are over and reads the short window, the one after starts past them and runs the pass. That normal
     * tick in between keeps the short window served when the pass can't drain, for example when one workspace's long
     * read fails every time. A tick that ends past the 1.5 intervals leaves the pass due at once: it held the leader
     * lock, so no tick ran in between.
     */
    private void finishLongWindow(Instant start, boolean drained) {
        var vectors = config.getVectors();
        var notDueFor = drained
                ? vectors.getLongWindowInterval().toJavaDuration()
                : Duration.between(clock.instant(),
                        start.plus(vectors.getTickInterval().toJavaDuration().multipliedBy(3).dividedBy(2)));
        var finish = lockService.unlockUsingToken(LONG_WINDOW_LOCK);
        // Redis takes a TTL in whole milliseconds, above zero.
        if (notDueFor.toMillis() > 0) {
            finish = finish.then(lockService.lockUsingToken(LONG_WINDOW_LOCK, notDueFor)).then();
        }
        try {
            finish.block();
        } catch (RuntimeException exception) {
            log.warn("Topic vectors long-window lock update failed, drained '{}'", drained, exception);
        }
        if (!drained) {
            log.info("Topic vectors long-window pass not drained, running it again after one normal tick");
        }
    }

    /**
     * Discovers the projects of the workspaces due for it: never discovered by this pod, or not for
     * {@code longWindowInterval}. Per workspace, two plain aggregates over the backfill window: the projects with done
     * statuses, and their vector counts. A new project gets a row, an existing one its new counts; a workspace's rows
     * not checked for twice the interval start again, because the tick did not run for it meanwhile. A failed
     * discovery is tried again next tick. A workspace no longer eligible is forgotten, so one enabled again is
     * discovered at its first tick.
     */
    private void discover(List<String> workspaceIds, Window window, Tally tally) {
        discoveredAt.keySet().retainAll(new HashSet<>(workspaceIds));
        var interval = config.getVectors().getLongWindowInterval().toJavaDuration();
        int discovered = 0;
        for (var workspaceId : workspaceIds) {
            var last = discoveredAt.get(workspaceId);
            if (last != null && Duration.between(last, window.start()).compareTo(interval) < 0) {
                continue;
            }
            var left = Duration.between(clock.instant(), window.deadline());
            if (discovered == MAX_DISCOVERIES_PER_TICK || left.compareTo(MIN_READ_TIME) < 0) {
                return;
            }
            discovered++;
            try {
                var projects = dao.findBackfillProjects(workspaceId, TASK, window.lowerBound(),
                        window.createdAfter(), maxExecutionSeconds(left)).collectList().block();
                var tracesWithVectors = dao.countTracesWithVectorsByProject(workspaceId, window.lowerBound(),
                        secondsLeft(window.deadline())).block();
                var rows = projects.stream()
                        .map(project -> TopicVectorBackfill.builder()
                                .workspaceId(workspaceId)
                                .projectId(project.projectId())
                                .startedAt(window.start())
                                .checkedAt(window.start())
                                .lastDoneAt(project.lastDoneAt())
                                .tracesTotal(project.traces())
                                .tracesDone(Math.min(project.traces(),
                                        tracesWithVectors.getOrDefault(project.projectId(), 0L)))
                                .build())
                        .toList();
                backfills.discovered(workspaceId, rows, window.start(),
                        window.start().minus(interval.multipliedBy(2)));
                discoveredAt.put(workspaceId, window.start());
            } catch (RuntimeException exception) {
                rethrowIfInterrupted(exception);
                countReadFailure("discover", workspaceId, tally, exception);
                log.warn("Topic vectors project discovery failed, workspace '{}'; trying again next tick",
                        workspaceId, exception);
            }
        }
    }

    /**
     * Per enabled workspace, from a rotating offset, up to {@code maxTracesPerTick} candidates of its window, of the
     * projects whose backfill has drained: the fresh phase's read, or the daily pass's sliced one. A workspace whose
     * read fails, or isn't reached before the deadline, is read again next tick. Each workspace's name is resolved
     * before its read; one not reached is counted with the name this pod already knows, so the name service can hold
     * the tick past its deadline by at most one lookup.
     */
    private Found find(List<String> workspaceIds, Window window, boolean longWindowPass,
            Map<String, List<TopicVectorBackfill>> undrained, Tally tally) {
        int limit = config.getVectors().getMaxTracesPerTick();
        var sliceSize = longWindowPass ? config.getVectors().getFind().getSliceSize().toJavaDuration() : null;
        var oldest = new LinkedHashMap<String, Oldest>();
        var found = new ArrayList<TopicVectorCandidate>();
        boolean complete = true;
        for (int i = 0; i < workspaceIds.size(); i++) {
            var workspaceId = workspaceIds.get(i);
            var workspaceName = tally.name(workspaceId);
            var left = Duration.between(clock.instant(), window.deadline());
            if (left.compareTo(MIN_READ_TIME) < 0) {
                complete = false;
                var notRead = workspaceIds.subList(i, workspaceIds.size());
                notRead.forEach(id -> workspacesNotRead.add(1,
                        TopicsWorkspaceNames.builder(id, workspaceNames.knownName(id)).build()));
                log.warn("Topic vectors window reads ran out of the tick budget, workspaces not read '{}' of '{}'",
                        notRead.size(), workspaceIds.size());
                break;
            }
            var excluded = undrained.getOrDefault(workspaceId, List.of()).stream()
                    .map(TopicVectorBackfill::projectId).toList();
            if (excluded.size() > MAX_EXCLUDED_PROJECTS) {
                // Its traces are the backfill phase's for now; it does not hold the daily pass.
                workspacesNotRead.add(1, tally.workspace(workspaceId).build());
                if (overExcluded.add(workspaceId)) {
                    log.warn("Topic vectors workspace reads skipped until the backfill brings it under '{}' projects, "
                            + "workspace '{}', projects in backfill '{}'", MAX_EXCLUDED_PROJECTS, workspaceId,
                            excluded.size());
                }
                continue;
            }
            if (overExcluded.remove(workspaceId)) {
                log.info("Topic vectors workspace reads resumed, workspace '{}', projects in backfill '{}'",
                        workspaceId, excluded.size());
            }
            try {
                var read = readWindow(workspaceId, null, excluded, window, sliceSize, limit);
                var served = withoutNewProjects(workspaceId, read.candidates(), window.start(), undrained);
                oldest.put(workspaceId, new Oldest(workspaceName, served.stream()
                        .map(TopicVectorCandidate::statusUpdatedAt).min(Comparator.naturalOrder())
                        .orElse(Instant.MAX)));
                found.addAll(served);
                complete = complete && read.complete();
            } catch (RuntimeException exception) {
                rethrowIfInterrupted(exception);
                complete = false;
                countReadFailure("find", workspaceId, tally, exception);
                log.warn("Topic vectors window read failed, workspace '{}'; reading it again next tick", workspaceId,
                        exception);
            }
        }
        lastOldest = new OldestSnapshot(clock.instant(), Map.copyOf(oldest));
        return new Found(List.copyOf(found), complete);
    }

    /**
     * One window read, of the workspace or of one project. Without a slice size it is one query. With one, the
     * window's traces are read by creation time in half-open slices of that size, counted back from the tick's start,
     * the newest first and without an upper bound, down to the window's {@code createdAfter}: every trace is in exactly
     * one slice, and each query's cost follows its slice, not the window. A window no longer than a slice is one query
     * too. The read stops at {@code limit} candidates or when the tick has no time for another query; it is complete
     * when it read every slice and found fewer than {@code limit}.
     */
    private WindowRead readWindow(String workspaceId, @Nullable UUID projectId, List<UUID> excludedProjectIds,
            Window window, @Nullable Duration sliceSize, int limit) {
        var candidates = new ArrayList<TopicVectorCandidate>();
        Instant createdBefore = null;
        while (true) {
            var left = Duration.between(clock.instant(), window.deadline());
            if (left.compareTo(MIN_READ_TIME) < 0) {
                return new WindowRead(candidates, false);
            }
            var sliceStart = sliceSize == null
                    ? window.createdAfter()
                    : Optional.ofNullable(createdBefore).orElse(window.start()).minus(sliceSize);
            var createdAfter = sliceStart.isAfter(window.createdAfter()) ? sliceStart : window.createdAfter();
            int remaining = limit - candidates.size();
            // The plain read when nothing narrows it: the fresh phase's query stays as it was measured.
            var slice = createdBefore == null && projectId == null && excludedProjectIds.isEmpty()
                    ? dao.findUnvectored(workspaceId, TASK, window.lowerBound(), window.start(), createdAfter,
                            remaining, maxExecutionSeconds(left))
                    : dao.findUnvectored(workspaceId, TASK, window.lowerBound(), window.start(), createdAfter,
                            createdBefore, projectId, excludedProjectIds, remaining, maxExecutionSeconds(left));
            var rows = slice.collectList().block();
            candidates.addAll(rows);
            if (rows.size() >= remaining) {
                return new WindowRead(candidates, false);
            }
            if (!createdAfter.isAfter(window.createdAfter())) {
                return new WindowRead(candidates, true);
            }
            createdBefore = createdAfter;
        }
    }

    /**
     * A workspace read's candidates without those of projects that have no drained row. Such a project is new to the
     * tick: it gets a row, not drained, and its traces are left to the backfill phase of this tick, which reads the
     * project's whole window.
     */
    private List<TopicVectorCandidate> withoutNewProjects(String workspaceId, List<TopicVectorCandidate> candidates,
            Instant start, Map<String, List<TopicVectorBackfill>> undrained) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        var lastDone = candidates.stream().collect(Collectors.toMap(TopicVectorCandidate::projectId,
                TopicVectorCandidate::statusUpdatedAt, (a, b) -> a.isAfter(b) ? a : b, LinkedHashMap::new));
        var known = backfills.findByKeys(workspaceId, lastDone.keySet());
        var started = lastDone.entrySet().stream()
                .filter(entry -> !known.containsKey(entry.getKey()))
                .map(entry -> TopicVectorBackfill.builder()
                        .workspaceId(workspaceId)
                        .projectId(entry.getKey())
                        .startedAt(start)
                        .checkedAt(start)
                        .lastDoneAt(entry.getValue())
                        .build())
                .toList();
        if (!started.isEmpty()) {
            backfills.start(started);
            undrained.computeIfAbsent(workspaceId, id -> new ArrayList<>()).addAll(started);
            log.info("Topic vectors backfill started for new projects, workspace '{}', projects '{}'", workspaceId,
                    started.size());
        }
        return candidates.stream()
                .filter(candidate -> Optional.ofNullable(known.get(candidate.projectId()))
                        .map(TopicVectorBackfill::drainedAt).isPresent())
                .toList();
    }

    /**
     * The backfill phase: the projects not drained yet, one at a time, while the tick has time for an embed call.
     * Workspaces take turns in the tick's visiting order, one project each, so a workspace with many projects does not
     * hold the others; a workspace's projects go in {@link TopicVectorBackfill#backfillOrder}. A project that wrote
     * vectors and still has more than one read's worth takes its workspace's next turn too, so one large project uses
     * the time the tick has, not one {@code maxTracesPerTick}. A workspace an embed call backed off for is left for the
     * next tick. Returns the keys of the projects drained; they and the vectors
     * written are recorded in one write, and a failed write only means the projects are read again next tick.
     */
    private Set<TopicVectorBackfill> backfill(List<String> workspaceIds,
            Map<String, List<TopicVectorBackfill>> undrained, Window window, Tally tally) {
        var order = TopicVectorBackfill.backfillOrder(config.getVectors().getMaxTracesPerTick());
        var queues = new LinkedHashMap<String, ArrayDeque<TopicVectorBackfill>>();
        for (var workspaceId : workspaceIds) {
            var projects = undrained.getOrDefault(workspaceId, List.of());
            if (!projects.isEmpty()) {
                queues.put(workspaceId, projects.stream().sorted(order)
                        .collect(Collectors.toCollection(ArrayDeque::new)));
            }
        }
        var progress = new ArrayList<TopicVectorBackfill.Progress>();
        var drained = new HashSet<TopicVectorBackfill>();
        boolean any = !queues.isEmpty();
        while (any && hasTimeForChunk(window.deadline())) {
            any = false;
            for (var entry : queues.entrySet()) {
                var project = entry.getValue().poll();
                if (project == null || tally.backedOff.contains(entry.getKey())) {
                    continue;
                }
                if (!hasTimeForChunk(window.deadline())) {
                    break;
                }
                any = true;
                var turn = backfillProject(project, window, tally);
                turn.progress().ifPresent(done -> {
                    progress.add(done);
                    if (done.drainedAt() != null) {
                        drained.add(project);
                    }
                });
                if (turn.more()) {
                    entry.getValue().addFirst(project);
                }
            }
        }
        try {
            backfills.record(progress);
        } catch (RuntimeException exception) {
            rethrowIfInterrupted(exception);
            log.warn("Topic vectors backfill progress not recorded, projects '{}'; reading them again next tick",
                    progress.size(), exception);
            return Set.of();
        }
        return drained;
    }

    /**
     * One project's turn: read its window, embed, write. The project is drained when the read was complete and none of
     * its candidates were left for want of time, a read, a back-off or an insert. No progress when there is nothing
     * to record: the read failed, or nothing was written and the project is not drained. More: the read stopped at the
     * cap, every candidate had its turn and vectors were written, so another read now finds the next ones; a read
     * whose candidates all fail is not repeated in the tick.
     */
    private Turn backfillProject(TopicVectorBackfill project, Window window, Tally tally) {
        var workspaceId = project.workspaceId();
        var sliceSize = project.tracesTotal() > ONE_SLICE_MAX_TRACES
                ? config.getVectors().getFind().getSliceSize().toJavaDuration()
                : null;
        WindowRead read;
        try {
            read = readWindow(workspaceId, project.projectId(), List.of(), window, sliceSize,
                    config.getVectors().getMaxTracesPerTick());
        } catch (RuntimeException exception) {
            rethrowIfInterrupted(exception);
            countReadFailure("backfill", workspaceId, tally, exception);
            log.warn("Topic vectors backfill read failed, workspace '{}', project '{}'; reading it again next tick",
                    workspaceId, project.projectId(), exception);
            return new Turn(Optional.empty(), false);
        }
        int cuts = tally.cuts;
        int written = tally.written;
        embedAndWrite(read.candidates(), window.deadline(), null, tally);
        boolean drained = read.complete() && tally.cuts == cuts;
        if (!drained && tally.written == written) {
            return new Turn(Optional.empty(), false);
        }
        return new Turn(Optional.of(TopicVectorBackfill.Progress.builder()
                .workspaceId(workspaceId)
                .projectId(project.projectId())
                .drainedAt(drained ? window.start() : null)
                .written(tally.written - written)
                .build()), !read.complete() && tally.cuts == cuts);
    }

    /** Per workspace, the projects still not drained after this tick. */
    private UndrainedSnapshot undrainedSnapshot(List<String> workspaceIds,
            Map<String, List<TopicVectorBackfill>> undrained, Set<TopicVectorBackfill> drained, Tally tally) {
        var snapshot = new HashMap<String, Undrained>();
        for (var workspaceId : workspaceIds) {
            var left = undrained.getOrDefault(workspaceId, List.of()).stream()
                    .filter(project -> !drained.contains(project)).toList();
            var name = Optional.ofNullable(tally.names.get(workspaceId))
                    .orElseGet(() -> workspaceNames.knownName(workspaceId));
            snapshot.put(workspaceId, new Undrained(name, left.size(), left.stream()
                    .map(TopicVectorBackfill::startedAt).min(Comparator.naturalOrder()).orElse(null)));
        }
        return new UndrainedSnapshot(clock.instant(), Map.copyOf(snapshot));
    }

    /**
     * Per workspace, oldest first: read the summaries, embed them in chunks and write each chunk's vectors. A chunk
     * starts only with at least {@code embedTimeout} left in the tick, so a call never ends on the tick's deadline; the
     * rest is {@code over_budget}. After an embed call fails with a 429, a 5xx, a timeout or a retryable code, the
     * workspace is not called again this tick; other workspaces go on. After the workspace's last insert, one check
     * deletes the vectors of traces deleted meanwhile.
     *
     * <p>Up to {@code embedConcurrency} of a workspace's chunks are embedded at a time. Their vectors are still
     * inserted in chunk order, by this thread alone, and every count is kept by this thread: a call's thread only
     * returns what it made. After a back-off no new call starts; those in flight end and their vectors are inserted.
     */
    private void embedAndWrite(List<TopicVectorCandidate> candidates, Instant deadline, Instant missedBefore,
            Tally tally) {
        var byWorkspace = candidates.stream().collect(Collectors.groupingBy(TopicVectorCandidate::workspaceId,
                LinkedHashMap::new, Collectors.toList()));
        for (var entry : byWorkspace.entrySet()) {
            var workspaceId = entry.getKey();
            var group = entry.getValue();
            if (!hasTimeForChunk(deadline)) {
                tally.cut(workspaceId, OVER_BUDGET, group.size());
                continue;
            }
            Map<TopicTraceKey, TopicSummary> summaries;
            try {
                // In chunks: ClickHouse parses the ids as SQL, and one read of maxTracesPerTick ids could pass
                // max_query_size.
                summaries = Flux.fromIterable(Lists.partition(group, SUMMARY_READ_CHUNK))
                        .concatMap(chunk -> dao.findSummaries(workspaceId, TASK,
                                chunk.stream().map(TopicVectorsTickService::key).toList(), secondsLeft(deadline)))
                        .collectMap(TopicVectorsTickService::key, Function.identity())
                        .block();
            } catch (RuntimeException exception) {
                rethrowIfInterrupted(exception);
                tally.cut(workspaceId, READ_FAILED, group.size());
                countReadFailure("summaries", workspaceId, tally, exception);
                log.warn("Topic vectors summary read failed, workspace '{}', traces '{}'; reading it again next tick",
                        workspaceId, group.size(), exception);
                continue;
            }
            var pending = new ArrayList<Pending>();
            var missing = new ArrayList<TopicVectorCandidate>();
            for (var candidate : group) {
                summaryText(candidate, summaries.get(key(candidate)))
                        .ifPresentOrElse(
                                text -> pending.add(new Pending(candidate, text)),
                                () -> missing.add(candidate));
            }
            if (!missing.isEmpty()) {
                tally.skip(workspaceId, MISSING_SUMMARY, missing.size());
                // They stay at the head of this workspace's queue until a summary arrives or they leave the window.
                log.warn("Topic vectors skip done statuses without a summary, workspace '{}', traces '{}', oldest '{}'",
                        workspaceId, missing.size(), missing.getFirst().traceId());
            }
            var written = new ArrayList<TopicVector>();
            var chunks = Lists.partition(pending, config.getVectors().getEmbedBatchSize());
            // Built here: the calls' threads must not use the tally, not even for its workspace names.
            var attributes = tally.workspace(workspaceId).build();
            for (var embedded : embedInOrder(workspaceId, attributes, chunks, deadline)) {
                embedded.effects().forEach(effect -> effect.accept(tally));
                if (!embedded.called()) {
                    continue;
                }
                var vectors = embedded.vectors();
                try {
                    dao.insert(vectors).block();
                } catch (RuntimeException exception) {
                    tally.cut(workspaceId, INSERT_FAILED, vectors.size());
                    log.warn("Topic vectors insert failed, workspace '{}', rows '{}'; embedding them again next tick",
                            workspaceId, vectors.size(), exception);
                    continue;
                }
                written.addAll(vectors);
            }
            if (written.isEmpty()) {
                continue;
            }
            // Once per workspace, after its last insert, so it covers every vector written above.
            var deleted = deleteVectorsOfDeletedTraces(workspaceId, written, deadline);
            var kept = written.stream().filter(vector -> !deleted.contains(key(vector))).toList();
            tally.skip(workspaceId, TRACE_DELETED, deleted.size());
            tally.written(workspaceId, kept.size());
            if (missedBefore != null) {
                var missed = pending.stream().map(Pending::candidate)
                        .filter(candidate -> candidate.statusUpdatedAt().isBefore(missedBefore))
                        .map(TopicVectorsTickService::key)
                        .collect(Collectors.toSet());
                longWindowMissed.add(kept.stream().filter(vector -> missed.contains(key(vector))).count(),
                        tally.workspace(workspaceId).build());
            }
        }
    }

    /**
     * Deletes the vectors just written whose trace was deleted while they were embedded, and returns their keys. The
     * trace-delete cascade deletes the status rows before the vectors: when its vector delete ran before this insert,
     * the status is gone by now; when it runs after, it deletes the vector itself. Each chunk of keys is checked on
     * its own: a failed check or delete leaves only that chunk's vectors to retention.
     */
    private Set<TopicTraceKey> deleteVectorsOfDeletedTraces(String workspaceId, List<TopicVector> vectors,
            Instant deadline) {
        var written = vectors.stream().map(TopicVectorsTickService::key).distinct().toList();
        var deleted = new HashSet<TopicTraceKey>();
        // In chunks, for the same max_query_size reason as the summary read.
        for (var chunk : Lists.partition(written, SUMMARY_READ_CHUNK)) {
            try {
                var withStatus = dao.findStatusKeys(workspaceId, TASK, chunk, secondsLeft(deadline))
                        .collect(Collectors.toSet())
                        .block();
                var gone = chunk.stream().filter(key -> !withStatus.contains(key))
                        .collect(Collectors.toUnmodifiableSet());
                if (!gone.isEmpty()) {
                    dao.deleteByKeys(workspaceId, gone).block();
                    deleted.addAll(gone);
                }
            } catch (RuntimeException exception) {
                log.warn("Topic vectors deleted-trace check failed, workspace '{}', traces '{}'; retention removes "
                        + "any left", workspaceId, chunk.size(), exception);
            }
        }
        if (!deleted.isEmpty()) {
            log.info("Topic vectors deleted for traces deleted during the tick, workspace '{}', traces '{}'",
                    workspaceId, deleted.size());
        }
        return deleted;
    }

    /**
     * What each chunk's embed calls made, in chunk order. With {@code embedConcurrency} 1, or one chunk, each chunk is
     * embedded on this thread when the caller asks for it, so the next call starts after the previous chunk's insert.
     * Otherwise up to {@code embedConcurrency} chunks are embedded at a time, on other threads, and a new one starts
     * only when the caller has taken an earlier one: never more than that many calls in flight. An interrupt of the
     * caller cancels the calls in flight.
     */
    private Iterable<Embedded> embedInOrder(String workspaceId, Attributes attributes, List<List<Pending>> chunks,
            Instant deadline) {
        var backedOff = new AtomicBoolean();
        int concurrency = config.getVectors().getEmbedConcurrency();
        if (concurrency == 1 || chunks.size() <= 1) {
            return () -> chunks.stream()
                    .map(chunk -> embedSlot(workspaceId, attributes, chunk, deadline, backedOff))
                    .iterator();
        }
        return Flux.fromIterable(chunks)
                .flatMapSequential(chunk -> Mono
                        .fromCallable(() -> embedSlot(workspaceId, attributes, chunk, deadline, backedOff))
                        .subscribeOn(Schedulers.boundedElastic()), concurrency, 1)
                .toIterable(1);
    }

    /**
     * One chunk's turn: no call once the workspace backed off or the tick has no time for one. {@code backedOff} is the
     * workspace's, shared by its chunks in flight.
     */
    private Embedded embedSlot(String workspaceId, Attributes attributes, List<Pending> chunk, Instant deadline,
            AtomicBoolean backedOff) {
        if (backedOff.get()) {
            return Embedded.notCalled(tally -> tally.skip(workspaceId, EMBED_FAILED, chunk.size()));
        }
        if (!hasTimeForChunk(deadline)) {
            return Embedded.notCalled(tally -> tally.cut(workspaceId, OVER_BUDGET, chunk.size()));
        }
        var effects = new ArrayList<Consumer<Tally>>();
        return new Embedded(embedChunk(workspaceId, attributes, chunk, deadline, backedOff, effects), true, effects);
    }

    /**
     * One chunk's embed call. When the service rejects the call with a non-retryable 4xx, an input in it is bad (one
     * opik-inference refuses), so the chunk is halved and each half is tried, down to single inputs: a bad input costs
     * only itself, at about two calls per halving (17 calls for one bad input in 256). The halves are tried one after
     * the other, in the chunk's own turn. Halving stops at a back-off or when the tick has no time for another call.
     * Runs on a call's thread: what it counts goes to {@code effects}, for the tick's thread to apply.
     */
    private List<TopicVector> embedChunk(String workspaceId, Attributes attributes, List<Pending> chunk,
            Instant deadline, AtomicBoolean backedOff, List<Consumer<Tally>> effects) {
        try {
            return embed(workspaceId, attributes, chunk, effects);
        } catch (InferenceException exception) {
            boolean stop = backsOff(exception);
            if (!stop && isRejectedInput(exception) && chunk.size() > 1) {
                log.warn("Topic embed rejected a chunk, workspace '{}', code '{}', status '{}', traces '{}'; trying "
                        + "each half", workspaceId, exception.getCode(), exception.getHttpStatus(), chunk.size());
                var vectors = new ArrayList<TopicVector>();
                int half = chunk.size() / 2;
                for (var part : List.of(chunk.subList(0, half), chunk.subList(half, chunk.size()))) {
                    if (backedOff.get()) {
                        effects.add(tally -> tally.skip(workspaceId, EMBED_FAILED, part.size()));
                        continue;
                    }
                    if (!hasTimeForChunk(deadline)) {
                        effects.add(tally -> tally.cut(workspaceId, OVER_BUDGET, part.size()));
                        continue;
                    }
                    vectors.addAll(embedChunk(workspaceId, attributes, part, deadline, backedOff, effects));
                }
                return vectors;
            }
            if (stop) {
                backedOff.set(true);
            }
            effects.add(tally -> {
                tally.skip(workspaceId, EMBED_FAILED, chunk.size());
                if (stop) {
                    tally.cuts++;
                    tally.backedOff.add(workspaceId);
                }
            });
            log.warn("Topic embed failed, workspace '{}', code '{}', status '{}', traces '{}', back off '{}'",
                    workspaceId, exception.getCode(), exception.getHttpStatus(), chunk.size(), stop, exception);
            return List.of();
        }
    }

    /** A non-retryable 4xx other than 429: the service refused what was sent, so sending less may pass. */
    static boolean isRejectedInput(@NonNull InferenceException exception) {
        int status = exception.getHttpStatus();
        return !exception.isRetryable() && status >= 400 && status < 500 && status != 429;
    }

    /**
     * Shutdown disposes the tick, which interrupts its thread; {@code block()} then throws with the
     * {@link InterruptedException} as the cause. The tick must stop there instead of going on to the next workspace.
     */
    private static void rethrowIfInterrupted(RuntimeException exception) {
        if (Exceptions.unwrap(exception) instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    /** The service's error code as a metric attribute, from the known set only, so the attribute stays bounded. */
    static String metricCode(String code) {
        boolean known = InferenceErrorCode.find(code).isPresent() || InferenceException.OUT_OF_TIME.equals(code)
                || InferenceException.CLIENT_BUSY.equals(code);
        return known ? code : "unknown";
    }

    private static TopicTraceKey key(TopicVectorCandidate candidate) {
        return TopicTraceKey.builder().projectId(candidate.projectId()).traceId(candidate.traceId()).build();
    }

    private static TopicTraceKey key(TopicSummary summary) {
        return TopicTraceKey.builder().projectId(summary.projectId()).traceId(summary.traceId()).build();
    }

    private static TopicTraceKey key(TopicVector vector) {
        return TopicTraceKey.builder().projectId(vector.projectId()).traceId(vector.traceId()).build();
    }

    private void countReadFailure(String phase, String workspaceId, Tally tally, Throwable error) {
        readFailures.add(1, tally.workspace(workspaceId).put(stringKey("phase"), phase)
                .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error)).build());
    }

    /**
     * Whether to stop calling for this workspace for the rest of the tick: any 429 or 5xx, whatever its code, a
     * transport failure or timeout, a retryable code, or no time left for an attempt.
     */
    static boolean backsOff(@NonNull InferenceException exception) {
        int status = exception.getHttpStatus();
        return exception.isRetryable() || exception.isOutOfTime() || status == 429 || status >= 500;
    }

    private boolean hasTimeForChunk(Instant deadline) {
        return Duration.between(clock.instant(), deadline)
                .compareTo(config.getVectors().getEmbedTimeout().toJavaDuration()) >= 0;
    }

    /**
     * One embed call with its full {@code embedTimeout}; vectors that aren't {@value #EMBED_DIMENSIONS} non-zero
     * numbers are dropped and counted. May run on a call's thread: the instruments it records to are thread-safe, and
     * what it counts in the tally goes to {@code effects}.
     */
    private List<TopicVector> embed(String workspaceId, Attributes attributes, List<Pending> chunk,
            List<Consumer<Tally>> effects) {
        var callDeadline = clock.instant().plus(config.getVectors().getEmbedTimeout().toJavaDuration());
        var request = EmbedRequest.builder()
                .orgId(workspaceId)
                // opik-inference's own default for embed: vectors are large and a summary is embedded once, so a cached
                // answer would only take cache space.
                .cache(false)
                .inputs(chunk.stream().map(Pending::text).toList())
                .build();
        long started = System.nanoTime();
        EmbedResponse response;
        try {
            response = client.embed(request, callDeadline).block();
        } catch (InferenceException exception) {
            embedDuration.record((System.nanoTime() - started) / 1e9,
                    attributes.toBuilder().put(stringKey("outcome"), metricCode(exception.getCode())).build());
            embedFailures.add(1, attributes.toBuilder()
                    .put(longKey("http_status"), (long) exception.getHttpStatus())
                    .put(stringKey("code"), metricCode(exception.getCode()))
                    .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(exception))
                    .build());
            throw exception;
        }
        embedDuration.record((System.nanoTime() - started) / 1e9,
                attributes.toBuilder().put(stringKey("outcome"), "ok").build());
        // A malformed response must cost only its own traces, never an exception out of the tick.
        var embeddings = Optional.ofNullable(response).map(EmbedResponse::result).map(EmbedResult::embeddings)
                .orElse(null);
        if (embeddings == null) {
            log.error("Topic vectors embed answered no embeddings for '{}' inputs, workspace '{}'", chunk.size(),
                    workspaceId);
            effects.add(tally -> tally.skip(workspaceId, INVALID_VECTOR, chunk.size()));
            return List.of();
        }
        if (embeddings.size() != chunk.size()) {
            log.error("Topic vectors embed answered '{}' vectors for '{}' inputs, workspace '{}'", embeddings.size(),
                    chunk.size(), workspaceId);
            effects.add(tally -> tally.skip(workspaceId, INVALID_VECTOR, chunk.size()));
            return List.of();
        }
        var vectors = new ArrayList<TopicVector>(chunk.size());
        for (int i = 0; i < chunk.size(); i++) {
            var candidate = chunk.get(i).candidate();
            var embedding = embeddings.get(i);
            var normalized = embedding != null && embedding.size() == EMBED_DIMENSIONS
                    && embedding.stream().allMatch(Objects::nonNull)
                            ? normalize(embedding)
                            : Optional.<List<Float>>empty();
            if (normalized.isEmpty()) {
                effects.add(tally -> tally.skip(workspaceId, INVALID_VECTOR, 1));
                continue;
            }
            vectors.add(TopicVector.builder()
                    .workspaceId(candidate.workspaceId())
                    .projectId(candidate.projectId())
                    .traceId(candidate.traceId())
                    .traceCreatedAt(candidate.traceCreatedAt())
                    .taskVersion(candidate.taskVersion())
                    .embedModel(EMBED_MODEL)
                    .embedDimensions(EMBED_DIMENSIONS)
                    .text(chunk.get(i).text())
                    .vector(normalized.get())
                    .build());
        }
        return vectors;
    }

    /**
     * The window's lower bound: {@code window} before {@code now}, but never before the task first applied, so no run
     * scans statuses from before the task existed.
     */
    static Instant lowerBound(@NonNull Instant appliesFrom, @NonNull Instant now, @NonNull Duration window) {
        var windowStart = now.minus(window);
        return windowStart.isAfter(appliesFrom) ? windowStart : appliesFrom;
    }

    static Instant earliestAppliesFrom(@NonNull AiLabelTask task) {
        return task.versions().stream().map(AiLabelTask.Version::appliesFrom).min(Comparator.naturalOrder())
                .orElseThrow();
    }

    /**
     * The text to embed: the summary exactly as stored, when the trace's latest output row is of the status's task
     * version and its summary is not blank. Anything else waits for the next tick: the label row lags the status on
     * this replica, or the trace is being relabelled.
     *
     * @param summary the trace's latest output row, or {@code null} when the summary read found none
     */
    static Optional<String> summaryText(@NonNull TopicVectorCandidate candidate, @Nullable TopicSummary summary) {
        if (summary == null || summary.taskVersion() != candidate.taskVersion() || isBlankForEmbed(summary.summary())) {
            return Optional.empty();
        }
        return Optional.of(summary.summary());
    }

    /**
     * Blank as opik-inference counts it: Python's {@code str.strip()} leaves nothing. That is Java's whitespace plus
     * the Unicode space separators Java leaves out (the no-break spaces U+00A0, U+2007, U+202F) and U+0085. The
     * service rejects such an input with a non-retryable 4xx. {@code TopicVectorsDAO.NON_BLANK} is the same rule.
     */
    static boolean isBlankForEmbed(@NonNull String text) {
        return text.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85);
    }

    /** Scaled to length 1, so the nightly job's dot product is the cosine; empty for a zero or non-finite vector. */
    static Optional<List<Float>> normalize(@NonNull List<Float> vector) {
        double sum = 0;
        for (var value : vector) {
            sum += (double) value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0 || !Double.isFinite(norm)) {
            return Optional.empty();
        }
        var normalized = new ArrayList<Float>(vector.size());
        for (var value : vector) {
            normalized.add((float) (value / norm));
        }
        return Optional.of(List.copyOf(normalized));
    }

    private void timed(String phase, Runnable action) {
        timed(phase, () -> {
            action.run();
            return null;
        });
    }

    private <T> T timed(String phase, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            phaseDuration.record((System.nanoTime() - started) / 1e9, Attributes.of(stringKey("phase"), phase));
        }
    }

    /**
     * The window read's {@code trace_created_at} bound: ai-labels labels only traces created within its horizon, so a
     * status written at or after {@code lowerBound} belongs to a trace created at or after {@code lowerBound - horizon};
     * {@code slack} covers the time from the sweep to the status write.
     */
    static Instant createdAfter(@NonNull Instant lowerBound, @NonNull Duration horizon, @NonNull Duration slack) {
        return lowerBound.minus(horizon).minus(slack);
    }

    /** The tick's number since the epoch, in tick intervals: the same on every pod for the same minute. */
    private long tickSequence(Instant start) {
        return start.toEpochMilli() / Math.max(1, config.getVectors().getTickInterval().toMilliseconds());
    }

    /** {@code items} starting at {@code sequence} modulo their count, wrapping around. */
    static <T> List<T> rotated(@NonNull List<T> items, long sequence) {
        if (items.isEmpty()) {
            return items;
        }
        int offset = (int) Math.floorMod(sequence, (long) items.size());
        var rotated = new ArrayList<T>(items.size());
        rotated.addAll(items.subList(offset, items.size()));
        rotated.addAll(items.subList(0, offset));
        return rotated;
    }

    /** The snapshot, unless this pod last ticked more than two tick intervals ago. */
    private <T> Optional<T> fresh(@Nullable T snapshot, Function<T, Instant> at) {
        var maxAge = config.getVectors().getTickInterval().toJavaDuration().multipliedBy(2);
        return Optional.ofNullable(snapshot)
                .filter(value -> Duration.between(at.apply(value), clock.instant()).compareTo(maxAge) <= 0);
    }

    /**
     * A window read's {@code max_execution_time}: the configured one, cut to the whole seconds left in the tick. The
     * caller starts a read only with at least {@link #MIN_READ_TIME} left, so it is at least 1.
     */
    private long maxExecutionSeconds(Duration left) {
        return Math.min(config.getVectors().getFind().getMaxExecutionTime().toSeconds(), left.toSeconds());
    }

    /** ClickHouse's {@code max_execution_time} for a query started now: the whole seconds left, at least 1. */
    private long secondsLeft(Instant deadline) {
        return Math.max(1, Duration.between(clock.instant(), deadline).toSeconds());
    }

    private static double seconds(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMillis() / 1_000.0);
    }
}
