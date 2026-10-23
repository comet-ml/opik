package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.Map;

/**
 * Body of the synchronous {@code POST /v1/decide}: the text the model reads ({@code state}) and at most
 * {@value #MAX_QUESTIONS} questions about it, keyed by ids the response echoes back. {@code orgId} is the Opik
 * workspace id, the service's tenant key. Callers leave {@code request_id} null: the client sets one per call.
 */
@Builder(toBuilder = true)
public record DecideRequest(
        @NonNull String orgId,
        String workspaceId,
        String requestId,
        @NonNull JsonNode state,
        @NonNull Map<String, Question> questions,
        @NonNull String lane,
        long deadlineMs,
        boolean cache) {

    public static final String REALTIME_LANE = "realtime";
    public static final int MAX_QUESTIONS = BatchDecideItem.MAX_QUESTIONS;
}
