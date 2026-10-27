package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
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
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_ID_KEY;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * One topic vectors tick: for every workspace ai-labels is enabled for, find the traces whose latest
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

    /**
     * Present while the long-window pass is not due. A tick that finds it absent sets it for one lease and runs the
     * pass; when the pass drains, the tick sets it again for {@code longWindowInterval}, otherwise it deletes it so the
     * next tick runs the pass again. A leader that dies mid-pass leaves it to expire with its lease.
     */
    static final Lock LONG_WINDOW_LOCK = new Lock("topics:vectors:long_window");
    // An insert after the last embed call, which has no deadline of its own, still ends within this past the rest.
    private static final Duration LEASE_SLACK = Duration.ofSeconds(5);
    // ClickHouse's max_execution_time is in whole seconds: a window read starts only with at least one left.
    private static final Duration MIN_READ_TIME = Duration.ofSeconds(1);

    private final TopicsConfig config;
    private final AiLabelsConfig aiLabelsConfig;
    private final TransactionTemplate template;
    private final TopicVectorsDAO dao;
    private final InferenceClient client;
    private final LockService lockService;
    private final AiLabelTaskRegistry registry;

    /**
     * The oldest done status without a vector, per workspace, as this pod's latest tick found it, and when. Only the
     * leader ticks, so on any other pod it is null or goes stale; a stale one is not published.
     */
    private volatile OldestSnapshot lastOldest;

    private final LongCounter written;
    private final LongCounter skipped;
    private final LongCounter embedFailures;
    private final LongCounter readFailures;
    private final LongCounter longWindowMissed;
    private final DoubleHistogram embedDuration;
    private final DoubleHistogram phaseDuration;

    /** What one tick did: the vectors it wrote, the traces it skipped by reason, and whether it used the long window. */
    @Builder(toBuilder = true)
    record TickResult(int written, @NonNull Map<String, Integer> skipped, boolean longWindowPass) {
        static final TickResult NONE = new TickResult(0, Map.of(), false);

        TickResult {
            skipped = Map.copyOf(skipped);
        }
    }

    private record OldestSnapshot(@NonNull Instant at, @NonNull Map<String, Instant> oldest) {
    }

    /**
     * The window read's outcome: the candidates, each workspace's oldest first, in visiting order, and whether it saw
     * everything in the window: every workspace read and none at its cap.
     */
    private record Found(@NonNull List<TopicVectorCandidate> candidates, boolean complete) {
    }

    /** The counts one tick keeps while it runs, reported both as metrics and in its {@link TickResult}. */
    private final class Tally {
        private final Map<String, Integer> skipped = new HashMap<>();
        private int written;
        // Candidates were left for the next tick for want of time, a read, a back-off or an insert.
        private boolean cut;

        void skip(String reason, int traces) {
            if (traces > 0) {
                skipped.merge(reason, traces, Integer::sum);
                TopicVectorsTickService.this.skipped.add(traces, Attributes.of(stringKey("reason"), reason));
            }
        }

        void cut(String reason, int traces) {
            skip(reason, traces);
            cut = cut || traces > 0;
        }

        void written(int rows) {
            written += rows;
            TopicVectorsTickService.this.written.add(rows);
        }
    }

    private record Pending(TopicVectorCandidate candidate, String text) {
    }

    @Inject
    public TopicVectorsTickService(@NonNull @Config("topics") TopicsConfig config,
            @NonNull @Config("aiLabels") AiLabelsConfig aiLabelsConfig, @NonNull TransactionTemplate template,
            @NonNull TopicVectorsDAO dao, @NonNull InferenceClient client, @NonNull LockService lockService,
            @NonNull AiLabelTaskRegistry registry) {
        this(config, aiLabelsConfig, template, dao, client, lockService, registry,
                GlobalOpenTelemetry.get().getMeter("opik.topics"));
    }

    TopicVectorsTickService(@NonNull TopicsConfig config, @NonNull AiLabelsConfig aiLabelsConfig,
            @NonNull TransactionTemplate template, @NonNull TopicVectorsDAO dao, @NonNull InferenceClient client,
            @NonNull LockService lockService, @NonNull AiLabelTaskRegistry registry, @NonNull Meter meter) {
        this.config = config;
        this.aiLabelsConfig = aiLabelsConfig;
        this.template = template;
        this.dao = dao;
        this.client = client;
        this.lockService = lockService;
        this.registry = registry;

        meter.gaugeBuilder("topics.vectors.oldest_unvectored_seconds").setUnit("s")
                .setDescription("Age of the oldest done trace_summary without a vector, per enabled workspace, as the "
                        + "latest tick's window found it; 0 with none. Published only by the pod that ticked within "
                        + "two tick intervals; a workspace whose read failed has no value")
                .buildWithCallback(measurement -> fresh(Instant.now())
                        .ifPresent(snapshot -> {
                            var now = Instant.now();
                            snapshot.oldest().forEach((workspaceId, statusAt) -> measurement.record(
                                    statusAt.equals(Instant.MAX) ? 0 : seconds(statusAt, now),
                                    Attributes.of(stringKey("workspace_id"), workspaceId)));
                        }));
        this.written = meter.counterBuilder("topics.vectors.written")
                .setDescription("Vectors written to topic_vectors")
                .build();
        this.skipped = meter.counterBuilder("topics.vectors.skipped")
                .setDescription("Traces a tick found but did not write, by reason; all are found again by a later tick")
                .build();
        this.embedFailures = meter.counterBuilder("topics.vectors.embed.failures")
                .setDescription("Embed calls that failed, by HTTP status (0 without a response) and code")
                .build();
        this.readFailures = meter.counterBuilder("topics.vectors.read.failures")
                .setDescription("ClickHouse reads that failed, by phase: find (one workspace's window read) or "
                        + "summaries (one workspace's summary read), workspace and error type")
                .build();
        this.longWindowMissed = meter.counterBuilder("topics.vectors.long_window.missed")
                .setDescription("Vectors the long-window pass wrote for statuses older than the short window, that is, "
                        + "rows the short window missed; should stay near zero")
                .build();
        this.embedDuration = meter.histogramBuilder("topics.vectors.embed.duration_seconds").setUnit("s")
                .setDescription("Wall time of one embed call, by outcome: ok or the error code")
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

    public TickResult tick(@NonNull Instant start) {
        var vectors = config.getVectors();
        var deadline = start.plus(vectors.getTickBudget().toJavaDuration());
        var task = registry.find(TASK);
        if (task.isEmpty()) {
            log.warn("Topic vectors tick skipped, ai-labels has no task '{}'", TASK);
            return TickResult.NONE;
        }
        boolean longWindowPass = startLongWindowIfDue();
        var window = (longWindowPass ? longWindow(vectors) : vectors.getWindow()).toJavaDuration();
        var lowerBound = lowerBound(earliestAppliesFrom(task.get()), start, window);
        if (lowerBound.isAfter(start)) {
            if (longWindowPass) {
                finishLongWindow(true);
            }
            return TickResult.builder().skipped(Map.of()).longWindowPass(longWindowPass).build();
        }
        var createdAfter = createdAfter(lowerBound, aiLabelsConfig.getHorizon().toJavaDuration(),
                vectors.getFind().getCreatedAtSlack().toJavaDuration());

        var found = timed("find", () -> find(start, lowerBound, createdAfter, deadline));
        var tally = new Tally();
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
     * Drained: the pass is not due again for {@code longWindowInterval}. Otherwise it stays due, so the next tick runs
     * it again: until a long-window tick reads every workspace, finds fewer than the cap in each, and leaves nothing for
     * want of time, a read, a back-off or an insert.
     */
    private void finishLongWindow(boolean drained) {
        var release = lockService.unlockUsingToken(LONG_WINDOW_LOCK);
        var finish = drained
                ? release.then(lockService.lockUsingToken(LONG_WINDOW_LOCK,
                        config.getVectors().getLongWindowInterval().toJavaDuration()))
                : release;
        try {
            finish.block();
        } catch (RuntimeException exception) {
            log.warn("Topic vectors long-window lock update failed, drained '{}'", drained, exception);
        }
        if (!drained) {
            log.info("Topic vectors long-window pass not drained, running it again next tick");
        }
    }

    /**
     * Per enabled workspace, from a rotating offset, the oldest {@code maxTracesPerTick} candidates of its window. A
     * workspace whose read fails, or isn't reached before the deadline, is read again next tick.
     */
    private Found find(Instant start, Instant lowerBound, Instant createdAfter, Instant deadline) {
        int limit = config.getVectors().getMaxTracesPerTick();
        var workspaceIds = rotated(template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelWorkspaceDAO.class).enabledWorkspaceIds()), tickSequence(start));
        var oldest = new LinkedHashMap<String, Instant>();
        var found = new ArrayList<TopicVectorCandidate>();
        boolean complete = true;
        for (int i = 0; i < workspaceIds.size(); i++) {
            var workspaceId = workspaceIds.get(i);
            var left = Duration.between(Instant.now(), deadline);
            if (left.compareTo(MIN_READ_TIME) < 0) {
                complete = false;
                log.warn("Topic vectors window reads ran out of the tick budget, workspaces not read '{}' of '{}'",
                        workspaceIds.size() - i, workspaceIds.size());
                break;
            }
            try {
                var candidates = dao.findUnvectored(workspaceId, TASK, lowerBound, start, createdAfter, limit,
                        maxExecutionSeconds(left)).collectList().block();
                oldest.put(workspaceId,
                        candidates.isEmpty() ? Instant.MAX : candidates.getFirst().statusUpdatedAt());
                found.addAll(candidates);
                complete = complete && candidates.size() < limit;
            } catch (RuntimeException exception) {
                complete = false;
                countReadFailure("find", workspaceId, exception);
                log.warn("Topic vectors window read failed, workspace '{}'; reading it again next tick", workspaceId,
                        exception);
            }
        }
        lastOldest = new OldestSnapshot(Instant.now(), Map.copyOf(oldest));
        return new Found(List.copyOf(found), complete);
    }

    /**
     * Per workspace, oldest first: read the summaries, embed them in chunks and write each chunk's vectors. A chunk
     * starts only with at least {@code embedTimeout} left in the tick, so a call never ends on the tick's deadline; the
     * rest is {@code over_budget}. After an embed call fails with a 429, a 5xx, a timeout or a retryable code, the
     * workspace is not called again this tick; other workspaces go on.
     */
    private Void embedAndWrite(List<TopicVectorCandidate> candidates, Instant deadline, Instant missedBefore,
            Tally tally) {
        var byWorkspace = candidates.stream().collect(Collectors.groupingBy(TopicVectorCandidate::workspaceId,
                LinkedHashMap::new, Collectors.toList()));
        for (var entry : byWorkspace.entrySet()) {
            var workspaceId = entry.getKey();
            var group = entry.getValue();
            if (!hasTimeForChunk(deadline)) {
                tally.cut(OVER_BUDGET, group.size());
                continue;
            }
            Map<UUID, TopicSummary> summaries;
            try {
                summaries = dao.findSummaries(workspaceId, TASK,
                        group.stream().map(TopicVectorCandidate::traceId).collect(Collectors.toSet()),
                        secondsLeft(deadline))
                        .collectMap(TopicSummary::traceId, Function.identity())
                        .block();
            } catch (RuntimeException exception) {
                tally.cut(READ_FAILED, group.size());
                countReadFailure("summaries", workspaceId, exception);
                log.warn("Topic vectors summary read failed, workspace '{}', traces '{}'; reading it again next tick",
                        workspaceId, group.size(), exception);
                continue;
            }
            var pending = new ArrayList<Pending>();
            var missing = new ArrayList<TopicVectorCandidate>();
            for (var candidate : group) {
                summaryText(candidate, summaries.get(candidate.traceId())).ifPresentOrElse(
                        text -> pending.add(new Pending(candidate, text)),
                        () -> missing.add(candidate));
            }
            if (!missing.isEmpty()) {
                tally.skip(MISSING_SUMMARY, missing.size());
                // They stay at the head of this workspace's queue until a summary arrives or they leave the window.
                log.warn("Topic vectors found done statuses without a usable summary, workspace '{}', traces '{}', "
                        + "oldest trace '{}', status at '{}'; skipped until one arrives", workspaceId, missing.size(),
                        missing.getFirst().traceId(), missing.getFirst().statusUpdatedAt());
            }
            boolean backOff = false;
            for (var chunk : Lists.partition(pending, config.getVectors().getEmbedBatchSize())) {
                if (backOff) {
                    tally.skip(EMBED_FAILED, chunk.size());
                    continue;
                }
                if (!hasTimeForChunk(deadline)) {
                    tally.cut(OVER_BUDGET, chunk.size());
                    continue;
                }
                List<TopicVector> vectors;
                try {
                    vectors = embed(workspaceId, chunk, tally);
                } catch (InferenceException exception) {
                    tally.skip(EMBED_FAILED, chunk.size());
                    backOff = backsOff(exception);
                    tally.cut = tally.cut || backOff;
                    log.warn("Topic vectors embed failed, workspace '{}', code '{}', status '{}', traces '{}', "
                            + "backing off the workspace for the tick '{}'", workspaceId, exception.getCode(),
                            exception.getHttpStatus(), chunk.size(), backOff, exception);
                    continue;
                }
                try {
                    dao.insert(vectors).block();
                } catch (RuntimeException exception) {
                    tally.cut(INSERT_FAILED, vectors.size());
                    log.warn("Topic vectors insert failed, workspace '{}', rows '{}'; embedding them again next tick",
                            workspaceId, vectors.size(), exception);
                    continue;
                }
                tally.written(vectors.size());
                if (missedBefore != null) {
                    var missed = chunk.stream().map(Pending::candidate)
                            .filter(candidate -> candidate.statusUpdatedAt().isBefore(missedBefore))
                            .map(TopicVectorCandidate::traceId)
                            .collect(Collectors.toSet());
                    longWindowMissed.add(vectors.stream().filter(vector -> missed.contains(vector.traceId())).count());
                }
            }
        }
        return null;
    }

    private void countReadFailure(String phase, String workspaceId, Throwable error) {
        readFailures.add(1, Attributes.of(stringKey("phase"), phase, WORKSPACE_ID_KEY, workspaceId, ERROR_TYPE_KEY,
                ErrorMetricsResolver.errorType(error)));
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
        return Duration.between(Instant.now(), deadline)
                .compareTo(config.getVectors().getEmbedTimeout().toJavaDuration()) >= 0;
    }

    /**
     * One embed call with its full {@code embedTimeout}; vectors that aren't {@value #EMBED_DIMENSIONS} non-zero
     * numbers are dropped and counted.
     */
    private List<TopicVector> embed(String workspaceId, List<Pending> chunk, Tally tally) {
        var callDeadline = Instant.now().plus(config.getVectors().getEmbedTimeout().toJavaDuration());
        var request = EmbedRequest.builder()
                .orgId(workspaceId)
                .workspaceId(workspaceId)
                .cache(false)
                .inputs(chunk.stream().map(Pending::text).toList())
                .build();
        long started = System.nanoTime();
        EmbedResponse response;
        try {
            response = client.embed(request, callDeadline).block();
        } catch (InferenceException exception) {
            embedDuration.record((System.nanoTime() - started) / 1e9,
                    Attributes.of(stringKey("outcome"), exception.getCode()));
            embedFailures.add(1, Attributes.of(longKey("http_status"), (long) exception.getHttpStatus(),
                    stringKey("code"), exception.getCode()));
            throw exception;
        }
        embedDuration.record((System.nanoTime() - started) / 1e9, Attributes.of(stringKey("outcome"), "ok"));
        // A malformed response must cost only its own traces, never an exception out of the tick.
        var embeddings = Optional.ofNullable(response).map(EmbedResponse::result).map(EmbedResult::embeddings)
                .orElse(null);
        if (embeddings == null) {
            log.error("Topic vectors embed answered no embeddings for '{}' inputs, workspace '{}'", chunk.size(),
                    workspaceId);
            tally.skip(INVALID_VECTOR, chunk.size());
            return List.of();
        }
        if (embeddings.size() != chunk.size()) {
            log.error("Topic vectors embed answered '{}' vectors for '{}' inputs, workspace '{}'", embeddings.size(),
                    chunk.size(), workspaceId);
            tally.skip(INVALID_VECTOR, chunk.size());
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
                tally.skip(INVALID_VECTOR, 1);
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
     */
    static Optional<String> summaryText(@NonNull TopicVectorCandidate candidate, TopicSummary summary) {
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
    private static long secondsLeft(Instant deadline) {
        return Math.max(1, Duration.between(Instant.now(), deadline).toSeconds());
    }

    private static double seconds(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMillis() / 1_000.0);
    }
}
