package com.comet.opik.utils;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

/**
 * The full and slim JSON strings of a payload node.
 *
 * @param json the full JSON, as {@link TruncationUtils#toJsonString(JsonNode)} renders it
 * @param slim the slim JSON, as {@link TruncationUtils#slimJsonSerializable(JsonNode)} writes it
 */
@Builder(toBuilder = true)
public record JsonWithSlim(@NonNull String json, @NonNull String slim) {

    public static final JsonWithSlim EMPTY = JsonWithSlim.builder().json("").slim("").build();
}
