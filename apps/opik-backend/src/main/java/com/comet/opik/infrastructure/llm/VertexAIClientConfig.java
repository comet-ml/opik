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
 * Values may be a bare host or an absolute URL. The SDK concatenates the value into a URL and re-parses it, so a
 * scheme-less value would land in the path and misroute the request silently; the generator prepends {@code https://}
 * when one is missing rather than rejecting the older bare-host form, which operators may already have configured.
 * A port and trailing slash are accepted, which is what lets the tests point a location at a local stub.
 */
@Builder(toBuilder = true)
public record VertexAIClientConfig(
        @NotBlank String scope,
        @NotEmpty Map<@Pattern(regexp = "[a-z0-9-]+", message = "must be a lower-case location such as 'global'") String, //
                @NotBlank @Pattern(regexp = "(https?://)?[A-Za-z0-9.-]+(:\\d+)?/?", message = "must be a host or absolute URL such as 'https://aiplatform.googleapis.com'") String> multiRegionApiEndpoints) {
}
