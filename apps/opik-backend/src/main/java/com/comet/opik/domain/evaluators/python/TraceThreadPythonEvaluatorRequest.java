package com.comet.opik.domain.evaluators.python;

import com.comet.opik.api.SpanForLlm;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Builder(toBuilder = true)
public record TraceThreadPythonEvaluatorRequest(@NotEmpty String code, @NotNull List<ChatMessage> data) {

    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_USER = "user";

    /**
     * One turn of the rendered thread conversation. The {@code spans} field is optional —
     * when null, it's omitted from the JSON (via {@code @JsonInclude(NON_NULL)}) so the
     * wire shape matches today's {@code {role, content}} contract exactly. When populated
     * (LLM-as-judge and Python thread scorer with the agentic-tools feature flag on), it
     * carries the assistant turn's tool calls and other child spans as a nested tree —
     * see {@link SpanForLlm}.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record ChatMessage(@NotEmpty String role, @NotEmpty JsonNode content, List<SpanForLlm> spans) {
    }

    @JsonProperty
    public String type() {
        return "trace_thread";
    }

}
