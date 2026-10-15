package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SpanRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.TraceRow;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a chunk of traces into one deterministic, budget-fitted state each (design §7).
 *
 * <p>The state is {@code {name, input, output, metadata, start_time, end_time, duration, error_info, spans}}, with
 * {@code spans} the {@link AiLabelSpan} tree, every object's keys sorted, and absent fields left out. The same trace
 * content always renders to the same bytes, so {@link #RENDERER_VERSION} must be bumped with any change to the output;
 * the golden-file test enforces it.
 *
 * <p>Traces are read in one query per chunk and their spans in another, streamed one trace at a time so memory holds
 * one trace's spans rather than the chunk's. Rendering runs on its own {@code renderThreads} pool. A trace that is gone
 * (deleted, or never found) yields no outcome.
 */
@Slf4j
@Singleton
public class AiLabelRenderer {

    public static final int RENDERER_VERSION = 1;

    private final @NonNull AiLabelRenderDAO renderDAO;
    private final @NonNull AiLabelsConfig config;
    private final @NonNull Scheduler renderScheduler;

    @Inject
    AiLabelRenderer(@NonNull AiLabelRenderDAO renderDAO, @NonNull @Config("aiLabels") AiLabelsConfig config) {
        this.renderDAO = renderDAO;
        this.config = config;
        this.renderScheduler = Schedulers.newParallel("ai-labels-render", config.getRenderThreads(), true);
    }

    public Flux<RenderOutcome> render(@NonNull String workspaceId, @NonNull List<TraceRef> traces) {
        if (traces.isEmpty()) {
            return Flux.empty();
        }
        Map<UUID, TraceRef> refs = traces.stream()
                .collect(Collectors.toMap(TraceRef::traceId, Function.identity(), (first, second) -> first));
        int concurrency = config.getRenderThreads();
        return renderDAO.findTraces(workspaceId, traces)
                .collectMap(TraceRow::id)
                .flatMapMany(traceRows -> Flux.concat(
                        renderDAO.findSpans(workspaceId, traces)
                                .bufferUntilChanged(SpanRow::traceId)
                                .flatMap(spans -> renderOn(refs, traceRows.remove(spans.getFirst().traceId()), spans),
                                        concurrency),
                        // Whatever is left has no spans.
                        Flux.defer(() -> Flux.fromIterable(List.copyOf(traceRows.values())))
                                .flatMap(trace -> renderOn(refs, trace, List.of()), concurrency)));
    }

    /** The canonical JSON of a rendered state: the text its {@code bytes} measure, in UTF-8. */
    public static String canonicalJson(@NonNull JsonNode state) {
        return AiLabelJson.toCanonicalString(state);
    }

    private Mono<RenderOutcome> renderOn(Map<UUID, TraceRef> refs, TraceRow trace, List<SpanRow> spans) {
        if (trace == null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> render(refs.get(trace.id()), trace, spans, config.getRenderBudgetBytes()))
                .subscribeOn(renderScheduler);
    }

    static RenderOutcome render(@NonNull TraceRef ref, @NonNull TraceRow trace, @NonNull List<SpanRow> spans,
            long budgetBytes) {
        var fit = AiLabelStateFitter.fit(toState(trace, AiLabelSpanTree.build(spans)), budgetBytes);
        return switch (fit) {
            case AiLabelStateFitter.Fitted fitted ->
                new RenderOutcome.Rendered(ref, fitted.state(), toInt(fitted.bytes()), fitted.compactionLevel());
            case AiLabelStateFitter.OverBudget overBudget -> {
                log.info("Trace over the render budget after every step, traceId '{}', bytes '{}', budget '{}'",
                        trace.id(), overBudget.bytes(), budgetBytes);
                yield new RenderOutcome.OverBudget(ref, toInt(overBudget.bytes()));
            }
        };
    }

    static ObjectNode toState(TraceRow trace, List<AiLabelSpan> spans) {
        var state = AiLabelJson.NODE_FACTORY.objectNode();
        putText(state, "name", StringUtils.defaultIfBlank(trace.name(), null));
        putNode(state, "input", AiLabelJson.parse(trace.input()));
        putNode(state, "output", AiLabelJson.parse(trace.output()));
        putNode(state, "metadata", AiLabelJson.parse(trace.metadata()));
        putTimes(state, trace.startTime(), trace.endTime(),
                AiLabelSpanTree.durationMillis(trace.startTime(), trace.endTime()));
        putNode(state, "error_info", AiLabelJson.parse(trace.errorInfo()));
        putSpans(state, spans);
        return state;
    }

    private static ObjectNode toNode(AiLabelSpan span) {
        var node = AiLabelJson.NODE_FACTORY.objectNode();
        putText(node, "name", span.name());
        putText(node, "type", span.type() == null ? null : span.type().name());
        putTimes(node, span.startTime(), span.endTime(), span.duration());
        putNode(node, "input", span.input());
        putNode(node, "output", span.output());
        putNode(node, "metadata", span.metadata());
        putText(node, "model", span.model());
        putText(node, "provider", span.provider());
        putNode(node, "error_info", span.errorInfo());
        putSpans(node, span.spans());
        return node;
    }

    private static void putTimes(ObjectNode node, Instant startTime, Instant endTime, Double duration) {
        putText(node, "start_time", startTime == null ? null : startTime.toString());
        putText(node, "end_time", endTime == null ? null : endTime.toString());
        if (duration != null) {
            node.put("duration", duration);
        }
    }

    private static void putSpans(ObjectNode node, List<AiLabelSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }
        ArrayNode array = node.putArray("spans");
        spans.forEach(span -> array.add(toNode(span)));
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
