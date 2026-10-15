package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

@Builder(toBuilder = true)
public record BatchGenerateItem(@NonNull String itemId, @NonNull List<Message> messages, @NonNull JsonNode schema,
        int maxTokens, double temperature) implements BatchItem {

    public static final int MIN_MAX_TOKENS = 1;
    public static final int MAX_MAX_TOKENS = 4096;
    public static final double MIN_TEMPERATURE = 0.0;
    public static final double MAX_TEMPERATURE = 2.0;
}
