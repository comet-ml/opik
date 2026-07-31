package com.comet.opik.infrastructure.llm;

import com.google.cloud.vertexai.Transport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.Map;

/**
 * Configuration for the Vertex AI client.
 * <p>
 * {@code multiRegionApiEndpoints} maps a multi-region location to the host that serves it. The SDK derives the host
 * from the location as {@code %s-aiplatform.googleapis.com}, which only holds for single-region locations, so
 * multi-region locations have to be listed here or the client targets a name that does not exist (e.g.
 * {@code global-aiplatform.googleapis.com}). Single-region locations are deliberately absent and keep the SDK default.
 * <p>
 * The map is mandatory and has no counterpart in code: the configuration file is the only place these hosts are
 * defined, so what an operator reads there is always what the client uses.
 * <p>
 * Locations are looked up canonicalised (stripped and lower-cased), hence the pattern on the keys: a configured
 * {@code Global:} would never be matched and would silently fall back to the derived host, so it is rejected at
 * startup instead.
 * <p>
 * The values are hosts, not URLs — the SDK takes a bare host and appends {@code :443} itself on the gRPC transport —
 * so {@code @URL} cannot be used: it rejects every host shipped here. The pattern below is the equivalent constraint
 * for a host, and it catches the realistic mistake of pasting {@code https://aiplatform.googleapis.com}, which would
 * otherwise only surface as a failed completion. A port and a trailing slash are accepted because the SDK accepts
 * them, which is what lets the tests point every location at a local stub.
 */
@Builder(toBuilder = true)
public record VertexAIClientConfig(
        @NotBlank String scope,
        @NotEmpty Map<@Pattern(regexp = "[a-z0-9-]+", message = "must be a lower-case location such as 'global'") String, //
                @NotBlank @Pattern(regexp = "[A-Za-z0-9.-]+(:\\d+)?/?", message = "must be a host such as 'aiplatform.googleapis.com', with no scheme or path") String> multiRegionApiEndpoints,
        @NotNull Transport transport) {
}
