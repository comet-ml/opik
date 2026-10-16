package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.inference.InferenceContract;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 08: the shipped v1 task builds a byte-exact, contract-valid item. */
class TraceSummaryTaskTest {

    private static final UUID TRACE_ID = UUID.fromString("0192f0c4-4d1e-7a6b-9c3d-2e1f0a9b8c7d");

    private final AiLabelTaskRegistry registry = new AiLabelTaskRegistry(new AiLabelsConfig());
    private final AiLabelItemBuilder builder = new AiLabelItemBuilder();

    @Test
    void goldenTraceProducesTheByteExactGenerateItem() {
        var task = registry.get("trace_summary");
        var trace = new TraceRef(UUID.fromString("0192f0c4-0000-7000-8000-000000000001"), TRACE_ID,
                Instant.parse("2026-11-02T08:00:00Z"));
        var state = JsonUtils.getJsonNodeFromString(resource("trace_summary-state.json"));
        var rendered = new RenderOutcome.Rendered(trace, state, InferenceJson.canonical(state).length(), "full");

        var items = builder.build("workspace-1", rendered, List.of(task));

        assertThat(items).hasSize(1);
        var item = items.getFirst();
        assertThat(item.engineCalls()).isEqualTo(1);
        assertThat(item.plan().jobKey()).isEqualTo(new AiLabelItemPlan.JobKey(AiLabelEndpoint.GENERATE, false));
        var bytes = InferenceJson.writeBytes(item.payload());
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(resource("trace_summary-item.json"));
        assertThat(item.bytes()).isEqualTo(bytes.length);
        var request = InferenceJson.MAPPER
                .valueToTree(builder.generateRequest("workspace-1", false, items));
        assertThat(InferenceContract.validateEndpoint(InferenceContract.BATCH_GENERATE, "request", request))
                .isEmpty();
    }

    private static String resource(String name) {
        try (var input = TraceSummaryTaskTest.class.getClassLoader()
                .getResourceAsStream("ai-labels/golden/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
