package com.comet.opik.domain.ailabels;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SelectedSpan;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SpanRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.ThreadTurnRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.TraceRow;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a chunk of traces into deterministic, budget-fitted states (design §7, spec 09).
 *
 * <p>The state is {@code {name, input, output, spans}}, with {@code spans} the {@link AiLabelSpan} tree, every object's
 * keys sorted, and absent fields left out. A span's {@code input} or {@code output} equal to its parent's (the
 * trace's, for a top-level span) is left out too: wrapper spans repeat it. For tasks with {@code {{history}}}, the
 * state is fitted together with the history: the trace's earlier turns in its thread, oldest first, each
 * {@code {input, output}} as stored text. The same trace content always renders to the same bytes, so
 * {@link #RENDERER_VERSION} must be bumped with any change to the output; the golden-file test enforces it.
 *
 * <p>Traces are read in one query per chunk, and their threads' earlier turns in a second when a task needs them.
 * Spans are read in two steps: their sizes for the whole chunk, then their content in batches of traces bounded by
 * {@code renderReadBytes}, streamed one trace at a time so memory holds one trace's spans rather than the chunk's.
 * Rendering runs on its own {@code renderThreads} pool. A trace that is gone (deleted, or never found) yields no
 * outcome.
 */
@Slf4j
@Singleton
public class AiLabelRenderer {

    public static final int RENDERER_VERSION = 1;

    private static final Map<SpanType, String> SUMMARY_KEYS = Map.of(
            SpanType.llm, "llm_calls",
            SpanType.tool, "tool_calls",
            SpanType.guardrail, "guardrails",
            SpanType.general, "other_spans");

    private static final String INPUT = "input";
    private static final String OUTPUT = "output";

    // About 0.5 MB of keys in the read's query text.
    private static final int MAX_SPANS_PER_READ = 10_000;

    private final @NonNull AiLabelRenderDAO renderDAO;
    private final @NonNull AiLabelsConfig config;
    private final @NonNull InstantToUUIDMapper uuidMapper;
    private final @NonNull Scheduler renderScheduler;
    private final @NonNull LongCounter historyErrors;

    @Inject
    AiLabelRenderer(@NonNull AiLabelRenderDAO renderDAO, @NonNull @Config("aiLabels") AiLabelsConfig config,
            @NonNull InstantToUUIDMapper uuidMapper) {
        this.renderDAO = renderDAO;
        this.config = config;
        this.uuidMapper = uuidMapper;
        this.renderScheduler = Schedulers.newParallel("ai-labels-render", config.getRenderThreads(), true);
        this.historyErrors = GlobalOpenTelemetry.get().getMeter("opik.ai_labels")
                .counterBuilder("ai_labels.render.history_errors")
                .setDescription("Failed reads of earlier thread turns; the chunk's render fails and is retried")
                .build();
    }

    /**
     * Renders each trace once per kind of task in {@code tasks}: its state alone for the tasks without
     * {@code {{history}}} ({@link RenderOutcome#withHistory()} false), and its state fitted with its history for the
     * tasks with it (true).
     */
    public Flux<RenderOutcome> render(@NonNull String workspaceId, @NonNull List<TraceRef> traces,
            @NonNull Collection<AiLabelTask> tasks) {
        if (traces.isEmpty() || tasks.isEmpty()) {
            return Flux.empty();
        }
        boolean alone = tasks.stream().anyMatch(task -> !task.usesHistory());
        boolean withHistory = tasks.stream().anyMatch(AiLabelTask::usesHistory);
        Map<UUID, TraceRef> refs = traces.stream()
                .collect(Collectors.toMap(TraceRef::traceId, Function.identity(), (first, second) -> first));
        int concurrency = config.getRenderThreads();
        return renderDAO.findTraces(workspaceId, traces)
                .collectMap(TraceRow::id)
                .flatMapMany(traceRows -> histories(workspaceId, traceRows.values(), withHistory)
                        .flatMapMany(histories -> Flux.concat(
                                renderDAO.findSelectedSpans(workspaceId, traces)
                                        .collectList()
                                        .flatMapIterable(selected -> readBatches(refs, selected))
                                        // One batch at a time: what bounds the read's memory.
                                        .concatMap(batch -> renderDAO.findSpans(workspaceId, batch.traces(),
                                                batch.spans()))
                                        .bufferUntilChanged(SpanRow::traceId)
                                        .flatMap(spans -> renderOn(refs, traceRows.remove(spans.getFirst().traceId()),
                                                spans, alone, withHistory, histories), concurrency),
                                // Whatever is left has no spans.
                                Flux.defer(() -> Flux.fromIterable(List.copyOf(traceRows.values())))
                                        .flatMap(trace -> renderOn(refs, trace, List.of(), alone, withHistory,
                                                histories), concurrency))));
    }

    private record ReadBatch(List<TraceRef> traces, List<SelectedSpan> spans) {
    }

    /**
     * The selected spans, in batches whose input and output add up to at most {@code renderReadBytes}, and at most
     * {@link #MAX_SPANS_PER_READ} spans, whose keys the read sends; a trace over either alone is a batch of its own.
     * A trace's spans are never split. Batches follow the spans sort key, so each one reads a contiguous range: traces
     * scattered across the table share their granules with others' spans, which ClickHouse reads too (4x the bytes on
     * ollie).
     */
    private List<ReadBatch> readBatches(Map<UUID, TraceRef> refs, List<SelectedSpan> selected) {
        var spansByTrace = selected.stream().collect(Collectors.groupingBy(SelectedSpan::traceId));
        var inKeyOrder = spansByTrace.keySet().stream()
                .map(refs::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((TraceRef ref) -> ref.projectId().toString())
                        .thenComparing(ref -> ref.traceId().toString()))
                .toList();
        var batches = new ArrayList<ReadBatch>();
        var traces = new ArrayList<TraceRef>();
        var spans = new ArrayList<SelectedSpan>();
        long batchBytes = 0;
        for (var ref : inKeyOrder) {
            var traceSpans = spansByTrace.get(ref.traceId());
            long bytes = traceSpans.stream().mapToLong(SelectedSpan::bytes).sum();
            if (!traces.isEmpty() && (batchBytes + bytes > config.getRenderReadBytes()
                    || spans.size() + traceSpans.size() > MAX_SPANS_PER_READ)) {
                batches.add(new ReadBatch(traces, spans));
                traces = new ArrayList<>();
                spans = new ArrayList<>();
                batchBytes = 0;
            }
            traces.add(ref);
            spans.addAll(traceSpans);
            batchBytes += bytes;
        }
        if (!traces.isEmpty()) {
            batches.add(new ReadBatch(traces, spans));
        }
        return batches;
    }

    /** The canonical JSON of a rendered state or history: the text its {@code bytes} measure, in UTF-8. */
    public static String canonicalJson(@NonNull JsonNode state) {
        return AiLabelJson.toCanonicalString(state);
    }

    /**
     * The chunk's earlier turns, read once for all its threads: from {@code lookback} before its oldest threaded
     * trace's id time up to its newest threaded trace.
     */
    private Mono<Map<UUID, List<ThreadTurnRow>>> histories(String workspaceId, Collection<TraceRow> traces,
            boolean withHistory) {
        var threaded = traces.stream().filter(trace -> trace.threadId() != null).toList();
        if (!withHistory || threaded.isEmpty()) {
            return Mono.just(Map.of());
        }
        var history = config.getHistory();
        var lookback = history.getLookback().toJavaDuration();
        var oldest = threaded.stream().map(trace -> idTime(trace.id())).min(Comparator.naturalOrder()).orElseThrow();
        var newest = threaded.stream().map(TraceRow::id).max(Comparator.comparing(UUID::toString)).orElseThrow();
        return renderDAO.findThreadTurns(workspaceId,
                threaded.stream().map(TraceRow::projectId).collect(Collectors.toSet()),
                threaded.stream().map(TraceRow::threadId).collect(Collectors.toSet()),
                uuidMapper.toLowerBound(oldest.minus(lookback)), newest)
                .map(turn -> turn.toBuilder()
                        .input(capMiddle(turn.input(), history.getMaxFieldBytes()))
                        .output(capMiddle(turn.output(), history.getMaxFieldBytes()))
                        .build())
                .collectList()
                .map(turns -> selectHistories(threaded, turns, history.getTurns(), lookback))
                .doOnError(exception -> historyErrors.add(1));
    }

    private record ThreadKey(UUID projectId, String threadId) {
    }

    /**
     * Each trace's earlier turns, oldest first: of the turns in its project and thread with a smaller id and an id
     * time within {@code lookback} of its own, the {@code limit} with the largest ids. Ids compare as strings, which
     * orders UUIDv7 by time; {@link UUID#compareTo} compares signed halves and doesn't. The traces of one chunk are
     * among each other's turns, so they see each other.
     */
    private Map<UUID, List<ThreadTurnRow>> selectHistories(List<TraceRow> traces, List<ThreadTurnRow> turns,
            int limit, Duration lookback) {
        var byThread = new HashMap<ThreadKey, TreeMap<String, ThreadTurnRow>>();
        turns.forEach(turn -> byThread
                .computeIfAbsent(new ThreadKey(turn.projectId(), turn.threadId()), key -> new TreeMap<>())
                .put(turn.id().toString(), turn));
        var histories = new HashMap<UUID, List<ThreadTurnRow>>();
        for (var trace : traces) {
            var thread = byThread.get(new ThreadKey(trace.projectId(), trace.threadId()));
            if (thread == null) {
                continue;
            }
            var lowerBound = uuidMapper.toLowerBound(idTime(trace.id()).minus(lookback)).toString();
            var newestFirst = thread.subMap(lowerBound, true, trace.id().toString(), false)
                    .descendingMap().values().stream()
                    .limit(limit)
                    .toList();
            histories.put(trace.id(), newestFirst.reversed());
        }
        return histories;
    }

    private Flux<RenderOutcome> renderOn(Map<UUID, TraceRef> refs, TraceRow trace, List<SpanRow> spans, boolean alone,
            boolean withHistory, Map<UUID, List<ThreadTurnRow>> histories) {
        if (trace == null) {
            return Flux.empty();
        }
        var ref = refs.get(trace.id());
        long budget = config.getRenderBudgetBytes();
        return Mono.fromCallable(() -> {
            var tree = AiLabelSpanTree.build(spans);
            var outcomes = new ArrayList<RenderOutcome>(2);
            if (alone) {
                outcomes.add(fit(ref, trace, tree, null, budget));
            }
            if (withHistory) {
                outcomes.add(fit(ref, trace, tree, histories.getOrDefault(trace.id(), List.of()), budget));
            }
            return outcomes;
        })
                .flatMapIterable(Function.identity())
                .subscribeOn(renderScheduler);
    }

    /** {@code history} is oldest first, or null to render the state alone. */
    static RenderOutcome render(@NonNull TraceRef ref, @NonNull TraceRow trace, @NonNull List<SpanRow> spans,
            List<ThreadTurnRow> history, long budgetBytes) {
        return fit(ref, trace, AiLabelSpanTree.build(spans), history, budgetBytes);
    }

    private static RenderOutcome fit(TraceRef ref, TraceRow trace, List<AiLabelSpan> tree,
            List<ThreadTurnRow> history, long budgetBytes) {
        var fit = AiLabelStateFitter.fit(toState(trace, tree), history == null ? null : toHistory(history),
                spansSummary(tree), budgetBytes);
        return switch (fit) {
            case AiLabelStateFitter.Fitted fitted -> new RenderOutcome.Rendered(ref, fitted.state(), fitted.history(),
                    toInt(fitted.bytes()), fitted.compactionLevel());
            case AiLabelStateFitter.OverBudget overBudget -> {
                // Counted as compaction level over_budget; one line per trace is too many at INFO.
                log.debug("Trace over the render budget after every step, traceId '{}', withHistory '{}', "
                        + "bytes '{}', budget '{}'", trace.id(), history != null, overBudget.bytes(), budgetBytes);
                yield new RenderOutcome.OverBudget(ref, history != null, toInt(overBudget.bytes()));
            }
        };
    }

    static ObjectNode toState(TraceRow trace, List<AiLabelSpan> spans) {
        var state = AiLabelJson.NODE_FACTORY.objectNode();
        var input = AiLabelJson.parse(trace.input());
        var output = AiLabelJson.parse(trace.output());
        putText(state, "name", StringUtils.defaultIfBlank(trace.name(), null));
        putNode(state, INPUT, input);
        putNode(state, OUTPUT, output);
        putSpans(state, spans, input, output);
        return state;
    }

    static ArrayNode toHistory(List<ThreadTurnRow> turns) {
        var history = AiLabelJson.NODE_FACTORY.arrayNode();
        for (var turn : turns) {
            var node = history.addObject();
            putText(node, INPUT, StringUtils.defaultIfBlank(turn.input(), null));
            putText(node, OUTPUT, StringUtils.defaultIfBlank(turn.output(), null));
        }
        return history;
    }

    /** What stands in for the span tree once it's pruned: how many spans of each kind it had. */
    static ObjectNode spansSummary(List<AiLabelSpan> spans) {
        var counts = new EnumMap<SpanType, Integer>(SpanType.class);
        countByType(spans, counts);
        var summary = AiLabelJson.NODE_FACTORY.objectNode();
        counts.forEach((type, count) -> summary.put(SUMMARY_KEYS.get(type), count));
        return summary;
    }

    private static void countByType(List<AiLabelSpan> spans, Map<SpanType, Integer> counts) {
        if (spans == null) {
            return;
        }
        for (var span : spans) {
            counts.merge(Objects.requireNonNullElse(span.type(), SpanType.general), 1, Integer::sum);
            countByType(span.spans(), counts);
        }
    }

    /**
     * Cuts {@code text} over {@code maxBytes} UTF-8 bytes in the middle, around a {@code …[N chars truncated]…} marker:
     * of what the marker leaves, it keeps up to 70% from the start and the rest from the end, whole code points only.
     * The result fits {@code maxBytes} unless the marker alone doesn't.
     */
    static String capMiddle(String text, int maxBytes) {
        if (text == null || utf8Length(text) <= maxBytes) {
            return text;
        }
        // N is at most the text's length in chars, so its digits are reserved at that length.
        long markerBytes = utf8Length(AiLabelStateFitter.MARKER_OPEN) + utf8Length(AiLabelStateFitter.MARKER_CLOSE)
                + String.valueOf(text.length()).length();
        int contentBytes = (int) Math.max(0, maxBytes - markerBytes);
        int headBytes = contentBytes * 7 / 10;
        int headEnd = 0;
        for (int bytes = 0; headEnd < text.length();) {
            int codePoint = text.codePointAt(headEnd);
            bytes += utf8Length(codePoint);
            if (bytes > headBytes) {
                break;
            }
            headEnd += Character.charCount(codePoint);
        }
        int tailStart = text.length();
        for (int bytes = 0; tailStart > headEnd;) {
            int codePoint = text.codePointBefore(tailStart);
            bytes += utf8Length(codePoint);
            if (bytes > contentBytes - headBytes) {
                break;
            }
            tailStart -= Character.charCount(codePoint);
        }
        return text.substring(0, headEnd) + AiLabelStateFitter.MARKER_OPEN + text.codePointCount(headEnd, tailStart)
                + AiLabelStateFitter.MARKER_CLOSE + text.substring(tailStart);
    }

    private static long utf8Length(String text) {
        long bytes = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            bytes += utf8Length(codePoint);
            index += Character.charCount(codePoint);
        }
        return bytes;
    }

    /** A lone surrogate counts as the replacement character it's encoded as. */
    private static int utf8Length(int codePoint) {
        return codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    }

    /** The millisecond a UUIDv7 encodes in its top 48 bits. */
    private static Instant idTime(UUID id) {
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }

    private static void putSpans(ObjectNode node, List<AiLabelSpan> spans, JsonNode parentInput,
            JsonNode parentOutput) {
        if (spans == null || spans.isEmpty()) {
            return;
        }
        ArrayNode array = node.putArray("spans");
        spans.forEach(span -> array.add(toNode(span, parentInput, parentOutput)));
    }

    private static ObjectNode toNode(AiLabelSpan span, JsonNode parentInput, JsonNode parentOutput) {
        var node = AiLabelJson.NODE_FACTORY.objectNode();
        putText(node, "name", span.name());
        if (!Objects.equals(span.input(), parentInput)) {
            putNode(node, INPUT, span.input());
        }
        if (!Objects.equals(span.output(), parentOutput)) {
            putNode(node, OUTPUT, span.output());
        }
        putSpans(node, span.spans(), span.input(), span.output());
        return node;
    }

    private static void putText(ObjectNode node, String key, String value) {
        if (value != null) {
            node.set(key, AiLabelJson.NODE_FACTORY.textNode(value));
        }
    }

    private static void putNode(ObjectNode node, String key, JsonNode value) {
        if (value != null) {
            node.set(key, value);
        }
    }

    private static int toInt(long bytes) {
        return (int) Math.min(bytes, Integer.MAX_VALUE);
    }
}
