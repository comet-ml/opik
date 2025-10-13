package com.comet.opik.infrastructure.daytona;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record DaytonaCreateSessionRequest(
        @JsonProperty("SessionId") String sessionId) {
}
