package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class McpOAuthConfig {

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @JsonProperty
    private String baseUrl;

    @Valid @JsonProperty
    private String mcpResourceUri;

    @Valid @JsonProperty
    @NotNull private Duration accessTokenTtl;

    @Valid @JsonProperty
    @NotNull private Duration refreshTokenTtl;

    // Longest a refresh-token family may be configured to live, idle or absolute. A grant that never has to be
    // re-authorized is a standing credential; a year is already generous for a connector.
    private static final Duration MAX_REFRESH_LIFETIME = Duration.ofDays(365);

    // Java-side defaults on the settings added after the first release, so an externally supplied mcpOAuth block
    // that predates them still validates.
    @Valid @JsonProperty
    @Builder.Default
    @NotNull private Duration refreshTokenAbsoluteTtl = Duration.ofDays(30);

    @Valid @JsonProperty
    @NotNull private Duration codeTtl;

    @Valid @JsonProperty
    @NotNull private Duration refreshRotationGrace;

    @Valid @JsonProperty
    @Builder.Default
    @Positive private int refreshRotationMaxRetries = 10;

    @Valid @JsonProperty
    @Builder.Default
    @NotNull private Duration refreshLockLease = Duration.ofSeconds(10);

    @Valid @JsonProperty
    @NotNull private Duration scrubLockTimeout;

    @Valid @JsonProperty
    @NotNull private Duration scrubLockWaitTime;

    public String getIssuer() {
        return StringUtils.stripEnd(baseUrl, "/");
    }

    public String getMcpResourceUri() {
        return StringUtils.isNotBlank(mcpResourceUri) ? mcpResourceUri : getIssuer() + "/api/v1/mcp";
    }

    /**
     * The absolute lifetime a token family actually gets: never shorter than the idle lifetime, otherwise the cap
     * would shorten every refresh token from the first authorization on. Derived rather than validated so that
     * raising {@code refreshTokenTtl} alone (e.g. via {@code MCP_OAUTH_REFRESH_TOKEN_TTL}) keeps a deployment
     * bootable instead of tripping over the default absolute value.
     */
    public Duration effectiveRefreshTokenAbsoluteTtl() {
        return refreshTokenAbsoluteTtl.compareTo(refreshTokenTtl) >= 0 ? refreshTokenAbsoluteTtl : refreshTokenTtl;
    }

    @AssertTrue(message = "mcpOAuth.refreshTokenTtl must be positive and at most 365 days") public boolean isRefreshTokenTtlPositive() {
        return isPositive(refreshTokenTtl) && refreshTokenTtl.compareTo(MAX_REFRESH_LIFETIME) <= 0;
    }

    @AssertTrue(message = "mcpOAuth.refreshTokenAbsoluteTtl must be positive and at most 365 days") public boolean isRefreshTokenAbsoluteTtlPositive() {
        return isPositive(refreshTokenAbsoluteTtl) && refreshTokenAbsoluteTtl.compareTo(MAX_REFRESH_LIFETIME) <= 0;
    }

    @AssertTrue(message = "mcpOAuth.refreshLockLease must be positive") public boolean isRefreshLockLeasePositive() {
        return isPositive(refreshLockLease);
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    /**
     * Without an absolute http/https baseUrl the AS would advertise relative
     * or non-fetchable URLs in /.well-known/oauth-authorization-server, breaking RFC 8414 discovery for every MCP host.
     */
    @AssertTrue(message = "mcpOAuth.baseUrl must be an absolute http(s) URL when mcpOAuth.enabled=true")
    public boolean isBaseUrlValidWhenEnabled() {
        if (!enabled) {
            return true;
        }
        if (StringUtils.isBlank(baseUrl)) {
            return false;
        }
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && ("http".equals(scheme) || "https".equals(scheme));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
