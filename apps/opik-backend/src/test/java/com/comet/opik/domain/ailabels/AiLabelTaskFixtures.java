package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.inference.Question;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

@UtilityClass
class AiLabelTaskFixtures {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final Instant APPLIES_FROM = Instant.parse("2026-10-01T00:00:00Z");
    static final String WORKSPACE_ID = "workspace-1";

    /** A decide task with {@code questions} noul questions {@code q00}, {@code q01}, … */
    static AiLabelTask decide(String name, boolean cache, int questions) {
        var definitions = new LinkedHashMap<String, Question>();
        for (int index = 0; index < questions; index++) {
            definitions.put("q%02d".formatted(index), Question.NoulQuestion.builder()
                    .instructions(NODES.textNode("Question %d?".formatted(index)))
                    .build());
        }
        return AiLabelTask.builder()
                .name(name)
                .endpoint(AiLabelEndpoint.DECIDE)
                .cache(cache)
                .version(1)
                .appliesFrom(APPLIES_FROM)
                .questions(definitions)
                .build();
    }

    static AiLabelTask generate(String name, boolean cache) {
        var schema = NODES.objectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("summary").put("type", "string");
        schema.putArray("required").add("summary");
        return AiLabelTask.builder()
                .name(name)
                .endpoint(AiLabelEndpoint.GENERATE)
                .cache(cache)
                .version(3)
                .appliesFrom(APPLIES_FROM)
                .prompt(AiLabelTask.Prompt.builder()
                        .system("You summarize agent traces.")
                        .user("Summarize this trace:\n{{state}}\nBe brief.")
                        .build())
                .schema(schema)
                .maxTokens(128)
                .temperature(0.5)
                .build();
    }

    static JsonNode state() {
        var state = NODES.objectNode();
        state.put("name", "support-agent");
        state.putObject("output").put("answer", "Refund issued — Monday");
        state.putObject("input").put("question", "Where is my refund?");
        state.putArray("spans").addObject().put("type", "llm").put("name", "gpt");
        return state;
    }

    static RenderOutcome.Rendered rendered(UUID traceId) {
        var trace = new TraceRef(UUID.fromString("0192f0c4-0000-7000-8000-000000000001"), traceId,
                Instant.parse("2026-10-15T12:00:00.123456Z"));
        return new RenderOutcome.Rendered(trace, state(), 210, "spans_io_pruned:2");
    }
}
