package com.comet.opik.domain.token_usage.format;

/**
 * Cache writes split by TTL, as Claude reports them. Without the split, every write counts as a 5-minute write, and so
 * do the writes of a reported total that the split doesn't account for: they happened all the same.
 */
record CacheWrites(long fiveMinutes, long oneHour) {

    // The split under the names clients report Claude's usage with, wherever it's served: Claude's own (Bedrock
    // InvokeModel, OpenAI-compatible gateways), LiteLLM's, a flattened OTel span's, and the SDK's for Bedrock Converse.
    private static final String[] FIVE_MINUTE_WRITES = {"cache_creation.ephemeral_5m_input_tokens",
            "cache_creation_token_details.ephemeral_5m_input_tokens",
            "prompt_tokens_details.cache_creation_token_details.ephemeral_5m_input_tokens",
            "cache_creation_ephemeral_5m_input_tokens", "cache_details.5m.input_tokens"};
    private static final String[] ONE_HOUR_WRITES = {"cache_creation.ephemeral_1h_input_tokens",
            "cache_creation_token_details.ephemeral_1h_input_tokens",
            "prompt_tokens_details.cache_creation_token_details.ephemeral_1h_input_tokens",
            "cache_creation_ephemeral_1h_input_tokens", "cache_details.1h.input_tokens"};

    /** The writes of the total, split by whichever of the names above the usage reports. */
    static CacheWrites of(UsageKeys keys, long total) {
        return of(keys, FIVE_MINUTE_WRITES, ONE_HOUR_WRITES, total);
    }

    static CacheWrites of(UsageKeys keys, String fiveMinutesKey, String oneHourKey, long total) {
        return of(keys, new String[]{fiveMinutesKey}, new String[]{oneHourKey}, total);
    }

    private static CacheWrites of(UsageKeys keys, String[] fiveMinutes, String[] oneHour, long total) {
        if (keys.hasAny(fiveMinutes) || keys.hasAny(oneHour)) {
            long oneHourWrites = keys.first(oneHour);
            return new CacheWrites(Math.max(keys.first(fiveMinutes), total - oneHourWrites), oneHourWrites);
        }
        return new CacheWrites(total, 0);
    }

    long total() {
        return fiveMinutes + oneHour;
    }
}
