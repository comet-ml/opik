package com.comet.opik.api.spend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpendUserRow(
        String userUuid,
        String userEmail,
        String userDisplayName,
        String model,
        // Raw cache-tier sums from cc.billing — priced in the FE.
        Long inputTokens,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        Long outputTokens,
        Long totalTokens,
        long requests,
        long skills,
        long mcps,
        long mcpCalls,
        List<String> repositories,
        List<String> flags) {
}
