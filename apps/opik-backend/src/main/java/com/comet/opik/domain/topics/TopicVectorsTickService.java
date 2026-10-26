package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.EmbedRequest;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.collect.Lists;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * One topic vectors tick: for every workspace ai-labels is enabled for, find the traces whose latest
 * {@code trace_summary} status is done and that have no vector yet, embed their summaries with {@code /v1/embed} and
 * store the unit-length vectors in {@code topic_vectors}. Runs on the leader only.
 *
 * <p>Nothing is kept between ticks: each reads the done statuses written in a window, less the keys already in
 * {@code topic_vectors}, so a repeated, overlapping or late tick changes nothing, and a leader change loses nothing.
 * Once per {@code longWindowInterval} (a cluster-wide lock held until it expires) the window is the long one, which
 * picks up rows the short window missed. Work per tick is bounded by {@code maxTracesPerTick}, oldest first, and by the
 * tick budget, which every query and embed call stops at; what is left is found again by the next tick.
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

    private static final Lock LONG_WINDOW_LOCK = new Lock("topics:vectors:long_window");
    // A query or embed call started just before the budget ends still finishes within this past it.
    private static final Duration LEASE_SLACK = Duration.ofSeconds(10);

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TopicVectorsDAO dao;
    private final InferenceClient client;
    private final LockService lockService;
    private final AiLabelTaskRegistry registry;

    // The oldest done status without a vector, per workspace, as the latest tick found it; null before the first tick.
    private volatile Map<String, Instant> lastOldest;

    private final LongCounter written;
    private final LongCounter skipped;
    private final LongCounter embedFailures;
    private final LongCounter longWindowMissed;
    private final DoubleHistogram embedDuration;
    private final DoubleHistogram phaseDuration;

    /** What one tick did: the vectors it wrote, the traces it skipped by reason, and whether it used the long window. */
    @Builder(toBuilder = true)
    record TickResult(int written, @NonNull Map<String, Integer> skipped, boolean longWindow) {
        static final TickResult NONE = new TickResult(0, Map.of(), false);

        TickResult {
            skipped = Map.copyOf(skipped);
        }
    }

    /** The counts one tick keeps while it runs, reported both as metrics and in its {@link TickResult}. */
    private final class Tally {
        private final Map<String, Integer> skipped = new HashMap<>();
        private int written;

        void skip(String reason, int traces) {
            if (traces > 0) {
                skipped.merge(reason, traces, Integer::sum);
                TopicVectorsTickService.this.skipped.add(traces, Attributes.of(stringKey("reason"), reason));
            }
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
            @NonNull TransactionTemplate template, @NonNull TopicVectorsDAO dao, @NonNull InferenceClient client,
            @NonNull LockService lockService, @NonNull AiLabelTaskRegistry registry) {
        this.config = config;
        this.template = template;
        this.dao = dao;
        this.client = client;
        this.lockService = lockService;
        this.registry = registry;

        var meter = GlobalOpenTelemetry.get().getMeter("opik.topics");
        meter.gaugeBuilder("topics.vectors.oldest_unvectored_seconds").setUnit("s")
                .setDescription("Age of the oldest done trace_summary without a vector, per enabled workspace, as the "
                        + "latest tick's window found it; 0 with none")
                .buildWithCallback(measurement -> Optional.ofNullable(lastOldest)
                        .ifPresent(oldest -> {
                            var now = Instant.now();
                            oldest.forEach((workspaceId, statusAt) -> measurement.record(
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
        this.longWindowMissed = meter.counterBuilder("topics.vectors.long_window.missed")
                .setDescription("Traces the long-window pass found outside the short window; should stay near zero")
                .build();
        this.embedDuration = meter.histogramBuilder("topics.vectors.embed.duration_seconds").setUnit("s")
                .setDescription("Wall time of one embed call, by outcome: ok or the error code")
                .build();
        this.phaseDuration = meter.histogramBuilder("topics.vectors.tick.phase_seconds").setUnit("s")
                .setDescription("Duration of each tick phase: find, embed")
                .build();
    }

    /** How long the leader lock must last: the tick budget, plus a query or call started just before it ends. */
    public Duration leaderLease() {
        return config.getVectors().getTickBudget().toJavaDuration().plus(LEASE_SLACK);
    }

    public TickResult tick(@NonNull Instant start) {
        var vectors = config.getVectors();
        var deadline = start.plus(vectors.getTickBudget().toJavaDuration());
        var task = registry.find(TASK);
        if (task.isEmpty()) {
            log.warn("Topic vectors tick skipped, ai-labels has no task '{}'", TASK);
            return TickResult.NONE;
        }
        boolean longWindow = lockService.bestEffortLock(LONG_WINDOW_LOCK, Mono.just(true), Mono.empty(),
                vectors.getLongWindowInterval().toJavaDuration(), Duration.ZERO, true)
                .blockOptional()
                .orElse(false);
        var window = (longWindow ? vectors.getLongWindow() : vectors.getWindow()).toJavaDuration();
        var lowerBound = lowerBound(earliestAppliesFrom(task.get()), start, window);
        if (lowerBound.isAfter(start)) {
            return TickResult.builder().skipped(Map.of()).longWindow(longWindow).build();
        }

        var candidates = timed("find", () -> find(lowerBound, start, deadline));
        if (longWindow) {
            var shortLowerBound = start.minus(vectors.getWindow().toJavaDuration());
            longWindowMissed.add(candidates.stream()
                    .filter(candidate -> candidate.statusUpdatedAt().isBefore(shortLowerBound))
                    .count());
        }
        var tally = new Tally();
        timed("embed", () -> embedAndWrite(candidates, deadline, tally));
        return TickResult.builder().written(tally.written).skipped(tally.skipped).longWindow(longWindow).build();
    }

    /**
     * The oldest {@code maxTracesPerTick} candidates across the enabled workspaces, one window read per workspace. A
     * workspace whose read fails, or isn't reached before the deadline, is read again next tick.
     */
    private List<TopicVectorCandidate> find(Instant lowerBound, Instant upperBound, Instant deadline) {
        int limit = config.getVectors().getMaxTracesPerTick();
        var workspaceIds = template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelWorkspaceDAO.class).enabledWorkspaceIds());
        var oldest = new LinkedHashMap<String, Instant>();
        var found = new ArrayList<TopicVectorCandidate>();
        for (var workspaceId : workspaceIds) {
            if (!Instant.now().isBefore(deadline)) {
                break;
            }
            try {
                var candidates = dao.findUnvectored(workspaceId, TASK, lowerBound, upperBound, limit,
                        secondsLeft(deadline)).collectList().block();
                oldest.put(workspaceId,
                        candidates.isEmpty() ? Instant.MAX : candidates.getFirst().statusUpdatedAt());
                found.addAll(candidates);
            } catch (RuntimeException exception) {
                log.warn("Topic vectors window read failed, workspace '{}'; reading it again next tick", workspaceId,
                        exception);
            }
        }
        lastOldest = Map.copyOf(oldest);
        return found.stream()
                .sorted(Comparator.comparing(TopicVectorCandidate::statusUpdatedAt)
                        .thenComparing(TopicVectorCandidate::traceId))
                .limit(limit)
                .toList();
    }

    /**
     * Per workspace, oldest first: read the summaries, embed them in chunks and write each chunk's vectors. Stops
     * starting chunks at the deadline, and stops calling opik-inference for the rest of the tick once a call fails
     * with a retryable error (429, 5xx, timeout).
     */
    private Void embedAndWrite(List<TopicVectorCandidate> candidates, Instant deadline, Tally tally) {
        boolean backOff = false;
        var byWorkspace = candidates.stream().collect(Collectors.groupingBy(TopicVectorCandidate::workspaceId,
                LinkedHashMap::new, Collectors.toList()));
        for (var entry : byWorkspace.entrySet()) {
            var workspaceId = entry.getKey();
            var group = entry.getValue();
            if (backOff) {
                tally.skip(EMBED_FAILED, group.size());
                continue;
            }
            if (!Instant.now().isBefore(deadline)) {
                tally.skip(OVER_BUDGET, group.size());
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
                log.warn("Topic vectors summary read failed, workspace '{}'; reading it again next tick",
                        workspaceId, exception);
                continue;
            }
            var pending = new ArrayList<Pending>();
            for (var candidate : group) {
                summaryText(candidate, summaries.get(candidate.traceId())).ifPresentOrElse(
                        text -> pending.add(new Pending(candidate, text)),
                        () -> tally.skip(MISSING_SUMMARY, 1));
            }
            var chunks = Lists.partition(pending, config.getVectors().getEmbedBatchSize());
            for (int i = 0; i < chunks.size(); i++) {
                var chunk = chunks.get(i);
                if (backOff) {
                    tally.skip(EMBED_FAILED, chunk.size());
                    continue;
                }
                if (!Instant.now().isBefore(deadline)) {
                    tally.skip(OVER_BUDGET, chunk.size());
                    continue;
                }
                List<TopicVector> vectors;
                try {
                    vectors = embed(workspaceId, chunk, deadline, tally);
                } catch (InferenceException exception) {
                    tally.skip(EMBED_FAILED, chunk.size());
                    backOff = exception.isRetryable() || exception.isOutOfTime();
                    log.warn("Topic vectors embed failed, workspace '{}', code '{}', status '{}', traces '{}'",
                            workspaceId, exception.getCode(), exception.getHttpStatus(), chunk.size(), exception);
                    continue;
                }
                try {
                    dao.insert(vectors).block();
                } catch (RuntimeException exception) {
                    tally.skip(EMBED_FAILED, vectors.size());
                    log.error("Topic vectors insert failed, workspace '{}', rows '{}'; embedding them again next tick",
                            workspaceId, vectors.size(), exception);
                    continue;
                }
                tally.written(vectors.size());
            }
        }
        return null;
    }

    /** One embed call; vectors that aren't {@value #EMBED_DIMENSIONS} non-zero numbers are dropped and counted. */
    private List<TopicVector> embed(String workspaceId, List<Pending> chunk, Instant tickDeadline, Tally tally) {
        var timeout = Instant.now().plus(config.getVectors().getEmbedTimeout().toJavaDuration());
        var callDeadline = timeout.isBefore(tickDeadline) ? timeout : tickDeadline;
        var request = EmbedRequest.builder()
                .orgId(workspaceId)
                .workspaceId(workspaceId)
                .cache(false)
                .inputs(chunk.stream().map(Pending::text).toList())
                .build();
        long started = System.nanoTime();
        List<List<Float>> embeddings;
        try {
            embeddings = client.embed(request, callDeadline).block().result().embeddings();
        } catch (InferenceException exception) {
            embedDuration.record((System.nanoTime() - started) / 1e9,
                    Attributes.of(stringKey("outcome"), exception.getCode()));
            embedFailures.add(1, Attributes.of(longKey("http_status"), (long) exception.getHttpStatus(),
                    stringKey("code"), exception.getCode()));
            throw exception;
        }
        embedDuration.record((System.nanoTime() - started) / 1e9, Attributes.of(stringKey("outcome"), "ok"));
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
            var normalized = embedding.size() == EMBED_DIMENSIONS
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

    /** ClickHouse's {@code max_execution_time} for a query started now: the whole seconds left, at least 1. */
    private static long secondsLeft(Instant deadline) {
        return Math.max(1, Duration.between(Instant.now(), deadline).toSeconds());
    }

    private static double seconds(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMillis() / 1_000.0);
    }
}
