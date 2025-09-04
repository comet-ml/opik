package com.comet.opik.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

@AllArgsConstructor
@Getter
public enum OpenTelemetryMappingRule {

    AGENT_NAME("agent_name", false, "Logfire", Outcome.METADATA),
    ALL_MESSAGES("all_messages", false, "Logfire", Outcome.INPUT),

    CODE("code.", true, "Pydantic", Outcome.METADATA),

    GEN_AI_PROMPT("gen_ai.prompt", false, "GenAI", Outcome.INPUT, SpanType.llm),
    GEN_AI_COMPLETION("gen_ai.completion", false, "GenAI", Outcome.OUTPUT),
    GEN_AI_REQUEST_MODEL("gen_ai.request_model", false, "GenAI", Outcome.MODEL, SpanType.llm),
    GEN_AI_RESPONSE_MODEL("gen_ai.response_model", false, "GenAI", Outcome.MODEL, SpanType.llm),
    GEN_AI_REQUEST_MODEL_2("gen_ai.request.model", false, "GenAI", Outcome.MODEL, SpanType.llm),
    GEN_AI_RESPONSE_MODEL_2("gen_ai.response.model", false, "GenAI", Outcome.MODEL, SpanType.llm),
    GEN_AI_PROVIDER("gen_ai.system", false, "GenAI", Outcome.PROVIDER, SpanType.llm),
    GEN_AI_USAGE("gen_ai.usage.", true, "GenAI", Outcome.USAGE, SpanType.llm),
    GEN_AI_REQUEST("gen_ai.request.", true, "GenAI", Outcome.INPUT),
    GEN_AI_RESPONSE("gen_ai.response", true, "GenAI", Outcome.OUTPUT),

    LLM_INVOCATION_PARAMETERS("llm.invocation_parameters.*", true, "OpenInference", Outcome.INPUT, SpanType.llm),
    LLM_MODEL_NAME("llm.model_name", false, "OpenInference", Outcome.MODEL, SpanType.llm),
    LLM_TOKEN_COUNT("llm.token_count.", true, "OpenInference", Outcome.USAGE, SpanType.llm),

    LOGFIRE_MSG("logfire.msg", false, "Pydantic", Outcome.METADATA),
    LOGFIRE_MSG_TEMPLATE("logfire.msg_template", false, "Pydantic", Outcome.DROP),
    LOGFIRE_JSON_SCHEMA("logfire.json_schema", false, "Pydantic", Outcome.DROP),
    LOGFIRE_SPAN_TYPE("logfire.span_type", false, "Logfire", Outcome.DROP), // always general

    MODEL_NAME("model_name", false, "Logfire", Outcome.MODEL, SpanType.llm),

    INPUT("input", true, "General", Outcome.INPUT),
    OUTPUT("output", true, "General", Outcome.OUTPUT),

    PARAMS("params", false, "Logfire", Outcome.INPUT),
    PROMPT("prompt", false, "Logfire", Outcome.INPUT, SpanType.llm),

    RESPONSE("response", false, "Logfire", Outcome.OUTPUT),
    RESULT("result", false, "Logfire", Outcome.OUTPUT),

    TOOLS("tools", false, "Logfire", Outcome.INPUT),
    TOOL_RESPONSES("tool_responses", false, "Logfire", Outcome.OUTPUT),

    USAGE("usage", false, "Logfire", Outcome.USAGE, SpanType.llm),

    SMOLAGENTS("smolagents.", true, "Smolagents", Outcome.METADATA);

    private final String rule;
    private final boolean isPrefix; // either prefix or full rule
    private final String source;
    private final Outcome outcome;
    private final SpanType spanType;

    OpenTelemetryMappingRule(String rule, boolean isPrefix, String source, Outcome outcome) {
        this(rule, isPrefix, source, outcome, null);
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

    // list of 'non-real' integrations, like Pydantic uses logfire under the hood,
    // so part of the calls are tagged as 'logfire', but we want to see Pydantic
    private static Set<String> INTEGRATIONS_TO_IGNORE = Set.of("logfire");
    public static boolean isValidInstrumentation(String name) {
        return !INTEGRATIONS_TO_IGNORE.contains(name.toLowerCase());
    }

    enum Outcome {
        INPUT,
        OUTPUT,
        METADATA,
        MODEL,
        PROVIDER,
        USAGE,
        DROP;
    }
}
