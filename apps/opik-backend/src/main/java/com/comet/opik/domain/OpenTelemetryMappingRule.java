package com.comet.opik.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

@AllArgsConstructor
@Getter
public enum OpenTelemetryMappingRule {

    AGENT_NAME("agent_name", false, "Logfire", Outcome.METADATA),
    ALL_MESSAGES("all_messages", false, "Logfire", Outcome.INPUT),

    CODE("code.", true, "Pydantic", Outcome.METADATA),

    GEN_AI_USAGE("gen_ai.usage.", true, "GenAI", Outcome.USAGE),

    LOGFIRE_MSG("logfire.msg", false, "Pydantic", Outcome.METADATA),
    LOGFIRE_MSG_TEMPLATE("logfire.msg_template", false, "Pydantic", Outcome.DROP),
    LOGFIRE_JSON_SCHEMA("logfire.json_schema", false, "Pydantic", Outcome.DROP),
    LOGFIRE_SPAN_TYPE("logfire.span_type", false, "Logfire", Outcome.DROP), // always general

    MODEL_NAME("model_name", false, "Logfire", Outcome.MODEL),

    PARAMS("params", false, "Logfire", Outcome.INPUT),
    PROMPT("prompt", false, "Logfire", Outcome.INPUT),

    RESPONSE("response", false, "Logfire", Outcome.OUTPUT),
    RESULT("result", false, "Logfire", Outcome.OUTPUT),

    TOOLS("tools", false, "Logfire", Outcome.INPUT),
    TOOL_RESPONSES("tool_responses", false, "Logfire", Outcome.OUTPUT),

    USAGE("usage", false, "Logfire", Outcome.USAGE),

    SMOLAGENTS("smolagents.", true, "Smolagents", Outcome.METADATA);

    private final String rule;
    private final boolean isPrefix; // either prefix or full rule
    private final String source;
    private final Outcome outcome;
    private final SpanType spanType;

    OpenTelemetryMappingRule(String rule, boolean isPrefix, String source, Outcome outcome) {
        this(rule, isPrefix, source, outcome, SpanType.general);
    }

    public static Optional<OpenTelemetryMappingRule> findRule(String key) {
        return Arrays.stream(OpenTelemetryMappingRule.values())
                .filter(rule -> {
                    if (rule.isPrefix)
                        return key.startsWith(rule.rule);
                    else
                        return rule.rule.equals(key);
                })
                .findFirst();
    }

    enum Outcome {
        INPUT,
        OUTPUT,
        METADATA,
        MODEL,
        USAGE,
        DROP;
    }
}
