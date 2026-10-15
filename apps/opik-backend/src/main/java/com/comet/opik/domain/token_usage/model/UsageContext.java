package com.comet.opik.domain.token_usage.model;

import lombok.Builder;

/**
 * What the caller knows about where the usage came from, beyond its keys. Room for more hints later (tags, metadata,
 * the OTel integration); a hint may only choose among one provider's input variants, never set the provider.
 *
 * @param otelRenamedKeys the usage went through OTel ingestion's {@code input_tokens} → {@code prompt_tokens} and
 *                        {@code output_tokens} → {@code completion_tokens} rename, so the instrumentation's own names
 *                        are restored before matching.
 */
@Builder(toBuilder = true)
public record UsageContext(boolean otelRenamedKeys) {

    public static final UsageContext NONE = new UsageContext(false);

    public static final UsageContext OTEL_RENAMED_KEYS = new UsageContext(true);
}
