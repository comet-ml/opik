package com.comet.opik.infrastructure.llm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.Map;

/**
 * Configuration for the Vertex AI client.
 * <p>
 * {@code multiRegionApiEndpoints} pins the endpoint for a multi-region location, overriding what the SDK would resolve
 * itself. Single-region locations are deliberately absent and keep the SDK-derived endpoint.
 * <p>
 * Keys must already be in the canonical lower-case form locations are looked up by, so a configured {@code Global:}
 * is rejected at startup rather than silently never matching.
 * <p>
 * Values must be absolute URLs, scheme included: the SDK concatenates the value into a URL and re-parses it, so a bare
 * host lands in the path and the request goes somewhere else entirely, while {@code localhost:8443} parses
 * {@code localhost} as the scheme. Requiring the scheme turns both into a startup failure. A port and trailing slash
 * are accepted, which is what lets the tests point a location at a local stub.
 */
@Builder(toBuilder = true)
public record VertexAIClientConfig(
        @NotBlank String scope,
        @NotEmpty Map<@Pattern(regexp = "[a-z0-9-]+", message = "must be a lower-case location such as 'global'") String, //
                @NotBlank @Pattern(regexp = "https?://[A-Za-z0-9.-]+(:\\d+)?/?", message = "must be an absolute URL such as 'https://aiplatform.googleapis.com', scheme included") String> multiRegionApiEndpoints) {
}
