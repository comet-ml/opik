package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TraceThread(
        String id,
        UUID projectId,
        @JsonIgnore String workspaceId,
        UUID threadModelId,
        Instant startTime,
        Instant endTime,
        Double duration,
        @Schema(implementation = JsonListString.class) JsonNode firstMessage,
        @Schema(implementation = JsonListString.class) JsonNode lastMessage,
        List<FeedbackScore> feedbackScores,
        TraceThreadStatus status,
        long numberOfMessages,
        BigDecimal totalEstimatedCost,
        Map<String, Long> usage,
        List<Comment> comments,
        Set<String> tags,
        Instant lastUpdatedAt,
        String lastUpdatedBy,
        String createdBy,
        Instant createdAt) {

    @Builder(toBuilder = true)
    public record TraceThreadPage(
            int page,
            int size,
            long total,
            List<TraceThread> content,
            List<String> sortableBy) implements com.comet.opik.api.Page<TraceThread> {

        public static TraceThreadPage empty(int page, List<String> sortableBy) {
            return new TraceThreadPage(page, 0, 0, List.of(), sortableBy);
        }
    }

}
