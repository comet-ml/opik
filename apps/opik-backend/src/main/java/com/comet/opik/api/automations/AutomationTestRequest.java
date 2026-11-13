package com.comet.opik.api.automations;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.AssertTrue;
import lombok.Builder;

import java.util.UUID;

/** One trace or thread to run the automation on in shadow mode: outputs returned, nothing written. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AutomationTestRequest(@Nullable UUID traceId, @Nullable String threadId) {

    @JsonIgnore
    @AssertTrue(message = "exactly one of trace_id and thread_id is required") public boolean isOneTarget() {
        return (traceId == null) != (threadId == null || threadId.isBlank());
    }
}
