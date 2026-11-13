package com.comet.opik.api.automations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The loop as it runs: the objects as nodes, the running automations as the edges between what they
 * read and what they write, and what each produced over the window. Derived from the automations, never
 * stored.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Loop(UUID projectId, int windowDays, List<Node> nodes, List<Edge> edges) {

    /** A production trace stream, a suite, a queue, a prompt or a judge. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Node(String id, String kind, String name, @Nullable UUID objectId) {
    }

    /** One running automation: from the node its trigger reads to the nodes its steps write. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Edge(UUID automationId, String name, @Nullable String recipe, String from, List<String> to,
            Map<String, Object> produced) {
    }
}
