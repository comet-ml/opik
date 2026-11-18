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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * One topic vectors tick: for every workspace topics and ai-labels are enabled for, find the traces whose latest
 * {@code trace_summary} status is done and that have no vector yet, embed their summaries with {@code /v1/embed} and
 * store the unit-length vectors in {@code topic_vectors}. Runs on the leader only.
 *
 * <p>Per workspace, one read of the done statuses written in {@code window}, up to {@code maxTracesPerTick}, oldest
 * first; then the summaries of those traces, embedded in chunks of {@code embedBatchSize} and written chunk by chunk.
 * After a 429, a 5xx, a timeout or a retryable error the workspace is not called again in the tick; the other
 * workspaces go on.
 *
 * <p>ClickHouse alone says which traces have no vector: every read is the done statuses less the keys already in
 * {@code topic_vectors}, so a repeated, overlapping or late tick changes nothing, and a leader change loses nothing.
 * Work per tick is bounded per workspace by {@code maxTracesPerTick} and in total by the tick budget; what is left is
 * found again by the next tick. Workspaces are visited from an offset that moves every tick, so none is always last.
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
    private final DoubleHistogram embedDuration;
    private final DoubleHistogram phaseDuration;

    @Builder(toBuilder = true)
    record TickResult(int written, @NonNull Map<String, Integer> skipped) {

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

    /** The bounds every window read of a tick has in common. */
    private record Window(@NonNull Instant lowerBound, @NonNull Instant createdAfter, @NonNull Instant start,
            @NonNull Instant deadline) {
    }

    /** One tick's counts, reported both as metrics and in its {@link TickResult}, and its workspace names. */
    private final class Tally {
        private final Map<String, Integer> skipped = new HashMap<>();
        // Looked up once per tick, before the workspace's window read, so no later step waits on the name service.
        private final Map<String, String> names = new HashMap<>();
        private int written;

        String name(String workspaceId) {
            return names.computeIfAbsent(workspaceId, workspaceNames::name);
        }

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
     * What one chunk's turn made: its vectors, whether an embed call was made, and the counts to add to the tally. The
     * tick's thread applies the {@code effects}, so a chunk's turn never touches the tally itself.
     */
    private record Embedded(List<TopicVector> vectors, boolean called, List<Consumer<Tally>> effects) {

        static Embedded notCalled(Consumer<Tally> effect) {
            return new Embedded(List.of(), false, List.of(effect));
        }
    }

    @Inject
    public TopicVectorsTickService(@NonNull @Config("topics") TopicsConfig config,
            @NonNull @Config("aiLabels") AiLabelsConfig aiLabelsConfig, @NonNull TopicWorkspaceService workspaces,
            @NonNull TopicVectorsDAO dao, @NonNull InferenceClient client, @NonNull AiLabelTaskRegistry registry,
            @NonNull TopicsWorkspaceNames workspaceNames) {
        this(config, aiLabelsConfig, workspaces, dao, client, registry, workspaceNames,
                GlobalOpenTelemetry.get().getMeter("opik.topics"), Clock.systemUTC());
    }

    TopicVectorsTickService(@NonNull TopicsConfig config, @NonNull AiLabelsConfig aiLabelsConfig,
            @NonNull TopicWorkspaceService workspaces, @NonNull TopicVectorsDAO dao, @NonNull InferenceClient client,
            @NonNull AiLabelTaskRegistry registry, @NonNull TopicsWorkspaceNames workspaceNames, @NonNull Meter meter,
            @NonNull Clock clock) {
        this.config = config;
        this.aiLabelsConfig = aiLabelsConfig;
        this.workspaces = workspaces;
        this.dao = dao;
        this.client = client;
        this.registry = registry;
        this.workspaceNames = workspaceNames;
        this.clock = clock;

        meter.gaugeBuilder("topics.vectors.oldest_unvectored_seconds").setUnit("s")
                .setDescription("Age of the oldest done trace_summary without a vector, per enabled workspace, as "
                        + "the latest tick's workspace read found it; 0 with none. Published only by the pod that "
                        + "ticked within two tick intervals; a workspace whose read failed or was not reached has no "
                        + "value")
                .buildWithCallback(measurement -> fresh(lastOldest, OldestSnapshot::at)
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
                .setDescription("Reads that failed, by phase: find (one workspace's window read) or summaries (one "
                        + "summary read), workspace and error type")
                .build();
        this.workspacesNotRead = meter.counterBuilder("topics.vectors.workspaces_not_read")
                .setDescription("Enabled workspaces a tick's workspace read did not read because its budget ran out, "
                        + "by workspace. Such a workspace has no value in topics.vectors.oldest_unvectored_seconds "
                        + "for that tick")
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
     * @throws IllegalStateException when ai-labels has no {@value #TASK} task: no tick can run, so the job counts the
     *         tick as failed rather than as one that ran and found nothing
     */
    public TickResult tick(@NonNull Instant start) {
        var vectors = config.getVectors();
        var deadline = start.plus(vectors.getTickBudget().toJavaDuration());
        var task = registry.find(TASK)
                .orElseThrow(() -> new IllegalStateException("ai-labels has no task '%s'".formatted(TASK)));
        var appliesFrom = earliestAppliesFrom(task);
        var lowerBound = lowerBound(appliesFrom, start, vectors.getWindow().toJavaDuration());
        if (lowerBound.isAfter(start)) {
            return TickResult.builder().skipped(Map.of()).build();
        }
        var horizon = aiLabelsConfig.getHorizon().toJavaDuration();
        var slack = vectors.getFind().getCreatedAtSlack().toJavaDuration();
        var window = new Window(lowerBound, createdAfter(lowerBound, horizon, slack), start, deadline);

        var tally = new Tally();
        var workspaceIds = rotated(workspaces.eligibleWorkspaceIds(), tickSequence(start));
        var found = timed("find", () -> find(workspaceIds, window, tally));
        timed("embed", () -> embedAndWrite(found, deadline, tally));
        return TickResult.builder().written(tally.written).skipped(tally.skipped).build();
    }

    /**
     * The window read of each workspace, in visiting order. Each workspace's name is resolved before its read; one not
     * reached is counted with the name this pod already knows, so the name service can hold the tick past its deadline
     * by at most one lookup.
     */
    private List<TopicVectorCandidate> find(List<String> workspaceIds, Window window, Tally tally) {
        int limit = config.getVectors().getMaxTracesPerTick();
        var oldest = new LinkedHashMap<String, Oldest>();
        var found = new ArrayList<TopicVectorCandidate>();
        for (int i = 0; i < workspaceIds.size(); i++) {
            var workspaceId = workspaceIds.get(i);
            var workspaceName = tally.name(workspaceId);
            var left = Duration.between(clock.instant(), window.deadline());
            if (left.compareTo(MIN_READ_TIME) < 0) {
                var notRead = workspaceIds.subList(i, workspaceIds.size());
                notRead.forEach(id -> workspacesNotRead.add(1,
                        TopicsWorkspaceNames.builder(id, workspaceNames.knownName(id)).build()));
                log.warn("Topic vectors window reads ran out of the tick budget, workspaces not read '{}' of '{}'",
                        notRead.size(), workspaceIds.size());
                break;
            }
            try {
                var read = readWindow(workspaceId, window, limit, left);
                oldest.put(workspaceId, new Oldest(workspaceName, read.stream()
                        .map(TopicVectorCandidate::statusUpdatedAt).min(Comparator.naturalOrder())
                        .orElse(Instant.MAX)));
                found.addAll(read);
            } catch (RuntimeException exception) {
                rethrowIfInterrupted(exception);
                countReadFailure("find", workspaceId, tally, exception);
                log.warn("Topic vectors window read failed, workspace '{}'; reading it again next tick", workspaceId,
                        exception);
            }
        }
        lastOldest = new OldestSnapshot(clock.instant(), Map.copyOf(oldest));
        return List.copyOf(found);
    }

    /** One workspace's window read: one query, up to {@code limit} candidates, oldest first. */
    private List<TopicVectorCandidate> readWindow(String workspaceId, Window window, int limit, Duration left) {
        return dao.findUnvectored(TopicVectorsFind.builder()
                .workspaceId(workspaceId)
                .task(TASK)
                .lowerBound(window.lowerBound())
                .upperBound(window.start())
                .createdAfter(window.createdAfter())
                .limit(limit)
                .maxExecutionSeconds(maxExecutionSeconds(left))
                .build()).collectList().block();
    }

    /**
     * Per workspace, oldest first: read the summaries, embed them in chunks and write each chunk's vectors. A chunk
     * starts only with at least {@code embedTimeout} left in the tick, so a call never ends on the tick's deadline; the
     * rest is {@code over_budget}. After an embed call fails with a 429, a 5xx, a timeout or a retryable code, the
     * workspace is not called again this tick; other workspaces go on. After the workspace's last insert, one check
     * deletes the vectors of traces deleted meanwhile.
     */
    private void embedAndWrite(List<TopicVectorCandidate> candidates, Instant deadline, Tally tally) {
        var byWorkspace = candidates.stream().collect(Collectors.groupingBy(TopicVectorCandidate::workspaceId,
                LinkedHashMap::new, Collectors.toList()));
        for (var entry : byWorkspace.entrySet()) {
            var workspaceId = entry.getKey();
            var group = entry.getValue();
            if (!hasTimeForChunk(deadline)) {
                tally.skip(workspaceId, OVER_BUDGET, group.size());
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
                tally.skip(workspaceId, READ_FAILED, group.size());
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
                    rethrowIfInterrupted(exception);
                    tally.skip(workspaceId, INSERT_FAILED, vectors.size());
                    log.warn("Topic vectors insert failed, workspace '{}', rows '{}'; embedding them again next tick",
                            workspaceId, vectors.size(), exception);
                    continue;
                }
                written.addAll(vectors);
            }
            if (written.isEmpty()) {
                continue;
            }
            var deleted = deleteVectorsOfDeletedTraces(workspaceId, written, deadline);
            var kept = written.stream().filter(vector -> !deleted.contains(key(vector))).toList();
            tally.skip(workspaceId, TRACE_DELETED, deleted.size());
            tally.written(workspaceId, kept.size());
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
                rethrowIfInterrupted(exception);
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
     * What each chunk's embed calls made, in chunk order. Each chunk is embedded on this thread when the caller asks
     * for it, so the next call starts after the previous chunk's insert.
     */
    private Iterable<Embedded> embedInOrder(String workspaceId, Attributes attributes, List<List<Pending>> chunks,
            Instant deadline) {
        var backedOff = new AtomicBoolean();
        return () -> chunks.stream()
                .map(chunk -> embedSlot(workspaceId, attributes, chunk, deadline, backedOff))
                .iterator();
    }

    /** {@code backedOff} is the workspace's, shared by its chunks. */
    private Embedded embedSlot(String workspaceId, Attributes attributes, List<Pending> chunk, Instant deadline,
            AtomicBoolean backedOff) {
        if (backedOff.get()) {
            return Embedded.notCalled(tally -> tally.skip(workspaceId, EMBED_FAILED, chunk.size()));
        }
        if (!hasTimeForChunk(deadline)) {
            return Embedded.notCalled(tally -> tally.skip(workspaceId, OVER_BUDGET, chunk.size()));
        }
        var effects = new ArrayList<Consumer<Tally>>();
        return new Embedded(embedChunk(workspaceId, attributes, chunk, deadline, backedOff, effects), true, effects);
    }

    /**
     * One chunk's embed call. When the service rejects the call with a non-retryable 4xx, an input in it is bad (one
     * opik-inference refuses), so the chunk is halved and each half is tried, down to single inputs: a bad input costs
     * only itself, at about two calls per halving (17 calls for one bad input in 256). The halves are tried one after
     * the other, in the chunk's own turn. Halving stops at a back-off or when the tick has no time for another call.
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
                        effects.add(tally -> tally.skip(workspaceId, OVER_BUDGET, part.size()));
                        continue;
                    }
                    vectors.addAll(embedChunk(workspaceId, attributes, part, deadline, backedOff, effects));
                }
                return vectors;
            }
            if (stop) {
                backedOff.set(true);
            }
            effects.add(tally -> tally.skip(workspaceId, EMBED_FAILED, chunk.size()));
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

    /** Whether to stop calling for this workspace for the rest of the tick. */
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
     * numbers are dropped and counted. The instruments it records to are thread-safe.
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

    /** Never before the task first applied, so no run scans statuses from before the task existed. */
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
     * {@code slack} covers the time from the sweep to the status write and a horizon changed since.
     */
    static Instant createdAfter(@NonNull Instant lowerBound, @NonNull Duration horizon, @NonNull Duration slack) {
        return lowerBound.minus(horizon).minus(slack);
    }

    /** The tick's number since the epoch, in tick intervals: the same on every pod for the same minute. */
    private long tickSequence(Instant start) {
        return start.toEpochMilli() / Math.max(1, config.getVectors().getTickInterval().toMilliseconds());
    }

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
