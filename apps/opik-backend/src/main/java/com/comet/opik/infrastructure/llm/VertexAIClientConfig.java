package com.comet.opik.infrastructure.llm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.Map;

/**
 * Configuration for the Vertex AI client.
 * <p>
 * {@code multiRegionApiEndpoints} maps a multi-region location to the endpoint that serves it. The SDK derives the
 * endpoint from the location as {@code %s-aiplatform.googleapis.com}, which only holds for single-region locations, so
 * multi-region locations have to be listed here or the client targets a name that does not exist (e.g.
 * {@code global-aiplatform.googleapis.com}). Single-region locations are deliberately absent and keep the SDK default.
 * <p>
 * The map is mandatory and has no counterpart in code: the configuration file is the only place these endpoints are
 * defined, so what an operator reads there is always what the client uses.
 * <p>
 * Locations are looked up canonicalised (stripped and lower-cased), hence the pattern on the keys: a configured
 * {@code Global:} would never be matched and would silently fall back to the derived endpoint, so it is rejected at
 * startup instead.
 * <p>
 * The values must be absolute {@code http(s)} URLs, scheme included. The SDK concatenates this value with the API
 * version and path and re-parses the result, so a bare host lands in the path component instead of the authority and
 * the request is silently sent somewhere else entirely; {@code localhost:8443} is worse still, parsing {@code localhost}
 * as the scheme. Requiring the scheme turns both into a startup failure. A port and a trailing slash are accepted,
 * which is what lets the tests point every location at a local stub.
 */
@Builder(toBuilder = true)
public record VertexAIClientConfig(
        @NotBlank String scope,
        @NotEmpty Map<@Pattern(regexp = "[a-z0-9-]+", message = "must be a lower-case location such as 'global'") String, //
                @NotBlank @Pattern(regexp = "https?://[A-Za-z0-9.-]+(:\\d+)?/?", message = "must be an absolute URL such as 'https://aiplatform.googleapis.com', scheme included") String> multiRegionApiEndpoints) {
}
