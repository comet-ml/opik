package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.EmbedRequest;
import com.comet.opik.infrastructure.inference.EmbedResponse;
import com.comet.opik.infrastructure.inference.EmbedResult;
import com.comet.opik.infrastructure.inference.InferenceClient;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * store the unit-length vectors in {@code topic_vectors}. Runs on the leader only.
 *
 * <p>Nothing is kept between ticks: each reads the done statuses written in a window, less the keys already in
 * {@code topic_vectors}, so a repeated, overlapping or late tick changes nothing, and a leader change loses nothing.
 * Once per {@code longWindowInterval} the window is the long one (or the backfill window), which picks up rows the short
 * window missed; the pass stays on, tick after tick, until one of its ticks drains it. Work per tick is bounded per
 * workspace by {@code maxTracesPerTick}, oldest first, and in total by the tick budget; what is left is found again by
 * the next tick. Workspaces are visited from an offset that moves every tick, so none is always last.
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
     * Present while the long-window pass is not due. A tick that finds it absent sets it for one lease and runs the
     * pass; when the pass drains, the tick sets it again for {@code longWindowInterval}, otherwise it deletes it so the
     * next tick runs the pass again. A leader that dies mid-pass leaves it to expire with its lease.
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

    private final LongCounter written;
    private final LongCounter skipped;
    private final LongCounter embedFailures;
    private final LongCounter readFailures;
    private final LongCounter workspacesNotRead;
    private final LongCounter longWindowMissed;
    private final DoubleHistogram embedDuration;
    private final DoubleHistogram phaseDuration;

    /** What one tick did: the vectors it wrote, the traces it skipped by reason, and whether it used the long window. */
    @Builder(toBuilder = true)
    record TickResult(int written, @NonNull Map<String, Integer> skipped, boolean longWindowPass) {
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

    /**
     * The window read's outcome: the candidates, each workspace's oldest first, in visiting order, and whether it saw
     * everything in the window: every workspace read and none at its cap.
     */
    private record Found(@NonNull List<TopicVectorCandidate> candidates, boolean complete) {
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
        // Candidates were left for the next tick for want of time, a read, a back-off or an insert.
        private boolean cut;

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
            cut = cut || traces > 0;
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

    @Inject
    public TopicVectorsTickService(@NonNull @Config("topics") TopicsConfig config,
            @NonNull @Config("aiLabels") AiLabelsConfig aiLabelsConfig, @NonNull TopicWorkspaceService workspaces,
            @NonNull TopicVectorsDAO dao, @NonNull InferenceClient client, @NonNull LockService lockService,
            @NonNull AiLabelTaskRegistry registry, @NonNull TopicsWorkspaceNames workspaceNames) {
        this(config, aiLabelsConfig, workspaces, dao, client, lockService, registry, workspaceNames,
                GlobalOpenTelemetry.get().getMeter("opik.topics"), Clock.systemUTC());
    }

    TopicVectorsTickService(@NonNull TopicsConfig config, @NonNull AiLabelsConfig aiLabelsConfig,
            @NonNull TopicWorkspaceService workspaces, @NonNull TopicVectorsDAO dao, @NonNull InferenceClient client,
            @NonNull LockService lockService, @NonNull AiLabelTaskRegistry registry,
            @NonNull TopicsWorkspaceNames workspaceNames, @NonNull Meter meter, @NonNull Clock clock) {
        this.config = config;
        this.aiLabelsConfig = aiLabelsConfig;
        this.workspaces = workspaces;
        this.dao = dao;
        this.client = client;
        this.lockService = lockService;
        this.registry = registry;
        this.workspaceNames = workspaceNames;
        this.clock = clock;

        meter.gaugeBuilder("topics.vectors.oldest_unvectored_seconds").setUnit("s")
                .setDescription("Age of the oldest done trace_summary without a vector, per enabled workspace, as the "
                        + "latest tick's window found it; 0 with none. Published only by the pod that ticked within "
                        + "two tick intervals; a workspace whose read failed or was not reached has no value")
                .buildWithCallback(measurement -> fresh(clock.instant())
                        .ifPresent(snapshot -> {
                            var now = clock.instant();
                            snapshot.oldest().forEach((workspaceId, oldest) -> measurement.record(
                                    oldest.statusAt().equals(Instant.MAX) ? 0 : seconds(oldest.statusAt(), now),
                                    TopicsWorkspaceNames.builder(workspaceId, oldest.workspaceName()).build()));
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
                .setDescription("ClickHouse reads that failed, by phase: find (one workspace's window read) or "
                        + "summaries (one workspace's summary read), workspace and error type")
                .build();
        this.workspacesNotRead = meter.counterBuilder("topics.vectors.workspaces_not_read")
                .setDescription("Enabled workspaces a tick did not read because its budget ran out, by workspace; "
                        + "such a workspace has no value in topics.vectors.oldest_unvectored_seconds for that tick")
                .build();
        this.longWindowMissed = meter.counterBuilder("topics.vectors.long_window.missed")
                .setDescription("Vectors the long-window pass wrote for statuses older than the short window, that is, "
                        + "rows the short window missed, by workspace; should stay near zero")
                .build();
        this.embedDuration = meter.histogramBuilder("topics.vectors.embed.duration_seconds").setUnit("s")
                .setDescription("Wall time of one embed call, by outcome (ok or the error code) and workspace")
                .build();
        this.phaseDuration = meter.histogramBuilder("topics.vectors.tick.phase_seconds").setUnit("s")
                .setDescription("Duration of each tick phase: find, embed")
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
        var window = (longWindowPass ? longWindow(vectors) : vectors.getWindow()).toJavaDuration();
        var lowerBound = lowerBound(earliestAppliesFrom(task), start, window);
        if (lowerBound.isAfter(start)) {
            if (longWindowPass) {
                finishLongWindow(true);
            }
            return TickResult.builder().skipped(Map.of()).longWindowPass(longWindowPass).build();
        }
        var createdAfter = createdAfter(lowerBound, aiLabelsConfig.getHorizon().toJavaDuration(),
                vectors.getFind().getCreatedAtSlack().toJavaDuration());

        var tally = new Tally();
        var found = timed("find", () -> find(start, lowerBound, createdAfter, deadline, tally));
        // Only the long-window pass can write a status older than the short window: those are the rows it recovers.
        var missedBefore = longWindowPass ? start.minus(vectors.getWindow().toJavaDuration()) : null;
        timed("embed", () -> embedAndWrite(found.candidates(), deadline, missedBefore, tally));
        if (longWindowPass) {
            finishLongWindow(found.complete() && !tally.cut);
        }
        return TickResult.builder().written(tally.written).skipped(tally.skipped).longWindowPass(longWindowPass)
                .build();
    }

    /** The long-window pass's window: the backfill window when one is set, else {@code longWindow}. */
    static io.dropwizard.util.Duration longWindow(@NonNull TopicsConfig.VectorsConfig vectors) {
        return vectors.getBackfillWindow().toMilliseconds() > 0 ? vectors.getBackfillWindow() : vectors.getLongWindow();
    }

    /** True when the long-window pass is due; it is then held by this tick for one lease. Redis down: not due. */
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
     * Drained: the pass is not due again for {@code longWindowInterval}. Otherwise it is due again after one normal
     * tick: until a long-window tick reads every workspace, finds fewer than the cap in each, and leaves nothing for
     * want of time, a read, a back-off or an insert. The normal tick in between keeps the short window served when the
     * pass can't drain, for example when one workspace's long read fails every time.
     */
    private void finishLongWindow(boolean drained) {
        var vectors = config.getVectors();
        var notDueFor = drained
                ? vectors.getLongWindowInterval().toJavaDuration()
                : vectors.getTickInterval().toJavaDuration().multipliedBy(3).dividedBy(2);
        var finish = lockService.unlockUsingToken(LONG_WINDOW_LOCK)
                .then(lockService.lockUsingToken(LONG_WINDOW_LOCK, notDueFor));
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
     * Per enabled workspace, from a rotating offset, the oldest {@code maxTracesPerTick} candidates of its window. A
     * workspace whose read fails, or isn't reached before the deadline, is read again next tick. Each workspace's name
     * is resolved before its read; one not reached is counted with the name this pod already knows, so the name
     * service can hold the tick past its deadline by at most one lookup.
     */
    private Found find(Instant start, Instant lowerBound, Instant createdAfter, Instant deadline, Tally tally) {
        int limit = config.getVectors().getMaxTracesPerTick();
        var workspaceIds = rotated(workspaces.eligibleWorkspaceIds(), tickSequence(start));
        var oldest = new LinkedHashMap<String, Oldest>();
        var found = new ArrayList<TopicVectorCandidate>();
        boolean complete = true;
        for (int i = 0; i < workspaceIds.size(); i++) {
            var workspaceId = workspaceIds.get(i);
            var workspaceName = tally.name(workspaceId);
            var left = Duration.between(clock.instant(), deadline);
            if (left.compareTo(MIN_READ_TIME) < 0) {
                complete = false;
                var notRead = workspaceIds.subList(i, workspaceIds.size());
                notRead.forEach(id -> workspacesNotRead.add(1,
                        TopicsWorkspaceNames.builder(id, workspaceNames.knownName(id)).build()));
                log.warn("Topic vectors window reads ran out of the tick budget, workspaces not read '{}' of '{}'",
                        notRead.size(), workspaceIds.size());
                break;
            }
            try {
                var candidates = dao.findUnvectored(workspaceId, TASK, lowerBound, start, createdAfter, limit,
                        maxExecutionSeconds(left)).collectList().block();
                oldest.put(workspaceId, new Oldest(workspaceName,
                        candidates.isEmpty() ? Instant.MAX : candidates.getFirst().statusUpdatedAt()));
                found.addAll(candidates);
                complete = complete && candidates.size() < limit;
            } catch (RuntimeException exception) {
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
     * Per workspace, oldest first: read the summaries, embed them in chunks and write each chunk's vectors. A chunk
     * starts only with at least {@code embedTimeout} left in the tick, so a call never ends on the tick's deadline; the
     * rest is {@code over_budget}. After an embed call fails with a 429, a 5xx, a timeout or a retryable code, the
     * workspace is not called again this tick; other workspaces go on. After the workspace's last insert, one check
     * deletes the vectors of traces deleted meanwhile.
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
            boolean backOff = false;
            var written = new ArrayList<TopicVector>();
            for (var chunk : Lists.partition(pending, config.getVectors().getEmbedBatchSize())) {
                if (backOff) {
                    tally.skip(workspaceId, EMBED_FAILED, chunk.size());
                    continue;
                }
                if (!hasTimeForChunk(deadline)) {
                    tally.cut(workspaceId, OVER_BUDGET, chunk.size());
                    continue;
                }
                List<TopicVector> vectors;
                try {
                    vectors = embed(workspaceId, chunk, tally);
                } catch (InferenceException exception) {
                    tally.skip(workspaceId, EMBED_FAILED, chunk.size());
                    backOff = backsOff(exception);
                    tally.cut = tally.cut || backOff;
                    log.warn("Topic embed failed, workspace '{}', code '{}', status '{}', traces '{}', back off '{}'",
                            workspaceId, exception.getCode(), exception.getHttpStatus(), chunk.size(), backOff,
                            exception);
                    continue;
                }
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
     * numbers are dropped and counted.
     */
    private List<TopicVector> embed(String workspaceId, List<Pending> chunk, Tally tally) {
        var callDeadline = clock.instant().plus(config.getVectors().getEmbedTimeout().toJavaDuration());
        var request = EmbedRequest.builder()
                .orgId(workspaceId)
                .cache(false)
                .inputs(chunk.stream().map(Pending::text).toList())
                .build();
        long started = System.nanoTime();
        EmbedResponse response;
        try {
            response = client.embed(request, callDeadline).block();
        } catch (InferenceException exception) {
            embedDuration.record((System.nanoTime() - started) / 1e9,
                    tally.workspace(workspaceId).put(stringKey("outcome"), exception.getCode()).build());
            embedFailures.add(1, tally.workspace(workspaceId)
                    .put(longKey("http_status"), (long) exception.getHttpStatus())
                    .put(stringKey("code"), exception.getCode())
                    .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(exception))
                    .build());
            throw exception;
        }
        embedDuration.record((System.nanoTime() - started) / 1e9,
                tally.workspace(workspaceId).put(stringKey("outcome"), "ok").build());
        // A malformed response must cost only its own traces, never an exception out of the tick.
        var embeddings = Optional.ofNullable(response).map(EmbedResponse::result).map(EmbedResult::embeddings)
                .orElse(null);
        if (embeddings == null) {
            log.error("Topic vectors embed answered no embeddings for '{}' inputs, workspace '{}'", chunk.size(),
                    workspaceId);
            tally.skip(workspaceId, INVALID_VECTOR, chunk.size());
            return List.of();
        }
        if (embeddings.size() != chunk.size()) {
            log.error("Topic vectors embed answered '{}' vectors for '{}' inputs, workspace '{}'", embeddings.size(),
                    chunk.size(), workspaceId);
            tally.skip(workspaceId, INVALID_VECTOR, chunk.size());
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
                tally.skip(workspaceId, INVALID_VECTOR, 1);
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
        if (summary == null || summary.taskVersion() != candidate.taskVersion() || summary.summary().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(summary.summary());
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
    private Optional<OldestSnapshot> fresh(Instant now) {
        var maxAge = config.getVectors().getTickInterval().toJavaDuration().multipliedBy(2);
        return Optional.ofNullable(lastOldest)
                .filter(snapshot -> Duration.between(snapshot.at(), now).compareTo(maxAge) <= 0);
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
