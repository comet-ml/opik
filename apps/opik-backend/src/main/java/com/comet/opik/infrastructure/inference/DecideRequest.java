package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

import java.util.Map;

/**
 * Body of {@code POST /v1/decide}: the text the model reads ({@code state}) and the questions to answer about it,
 * keyed by a caller-chosen id that the response echoes back.
 *
 * @param orgId the Opik workspace id. The inference service calls its tenant key {@code org_id}; it is not a Comet
 *        organization. The backend knows only the workspace of the caller, and the service keys its per-tenant
 *        cache, rate limit and ledger on this value (design OPIK-8803, section 07).
 * @param state the text every question is answered about; at most 16,384 characters
 * @param questions the questions, keyed by an id the response echoes back
 * @param lane {@code realtime} for synchronous checks
 * @param deadlineMs how long the service may spend on the call
 * @param cache whether the service may answer an identical request from its per-tenant cache
 */
@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DecideRequest(
        @NonNull String orgId,
        @NonNull String state,
        @NonNull Map<String, DecideQuestion> questions,
        @NonNull String lane,
        long deadlineMs,
        boolean cache) {
}
