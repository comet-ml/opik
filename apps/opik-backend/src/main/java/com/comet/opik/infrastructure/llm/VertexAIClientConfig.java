package com.comet.opik.infrastructure.llm;

import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for the Vertex AI client.
 * <p>
 * {@code multiRegionApiEndpoints} optionally pins the endpoint for a location, overriding the one the SDK derives
 * itself. Unset entries, and an absent map, keep the SDK's endpoint, which for {@code global}, {@code eu} and
 * {@code us} is the one this map used to default to.
 * <p>
 * Keys must already be in the canonical lower-case form locations are looked up by, so a configured {@code Global:}
 * is rejected at startup rather than silently never matching.
 * <p>
 * Values may be a bare host or an {@code https://} URL. The SDK concatenates the value into a URL and re-parses it,
 * so a scheme-less value would land in the path and misroute the request silently; the generator prepends the scheme
 * rather than rejecting the bare-host form, which operators may already have configured. Plaintext is not accepted
 * here: the request carries a service-account bearer token.
 * <p>
 * {@code tokenUris} lists the only {@code token_uri} values a service-account key may carry: the credentials refresh by
 * posting to it, and the key file is user-supplied.
 * <p>
 * The credentials cache keeps one set of credentials per service-account key in memory, for
 * {@code credentialsCacheExpireAfterAccess} after its last call. Their OAuth token is shared by every pod through
 * Redis: it is refreshed once its remaining lifetime drops below {@code tokenCacheRefreshFraction} of the whole, under a
 * cross-pod lock whose lease is {@code tokenCacheLockTimeout}.
 */
@Builder(toBuilder = true)
public record VertexAIClientConfig(
        @NotBlank String scope,
        Map<@Pattern(regexp = "[a-z0-9-]+", message = "must be a lower-case location such as 'global'") String, //
                @Pattern(regexp = "(https://)?[A-Za-z0-9.-]+(:\\d+)?/?", message = "must be a host or https URL such as 'https://aiplatform.googleapis.com'") String> multiRegionApiEndpoints,
        @NotEmpty Set<@NotNull URI> tokenUris,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS) @MaxDuration(value = 1, unit = TimeUnit.DAYS) Duration credentialsCacheExpireAfterAccess,
        @DecimalMin("0.0") @DecimalMax("0.99") double tokenCacheRefreshFraction,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS) @MaxDuration(value = 1, unit = TimeUnit.MINUTES) Duration tokenCacheLockTimeout) {
}
