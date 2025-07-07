package com.comet.opik.domain.evaluators.python;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Builder(toBuilder = true)
public record TraceThreadPythonEvaluatorRequest(@NotEmpty String code, @NotNull List<ChatMessage> data) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Builder(toBuilder = true)
    public record ChatMessage(@NotEmpty String role, @NotEmpty String content) {
    }

}
