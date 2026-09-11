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

    // Java-side defaults on the settings added after the first release, so an externally supplied mcpOAuth block
    // that predates them still validates.
    @Valid @JsonProperty
    @NotNull private Duration refreshTokenAbsoluteTtl = Duration.ofDays(30);

    @Valid @JsonProperty
    @NotNull private Duration codeTtl;

    @Valid @JsonProperty
    @NotNull private Duration refreshRotationGrace;

    @Valid @JsonProperty
    @Positive private int refreshRotationMaxRetries = 10;

    @Valid @JsonProperty
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
     * A refresh token's absolute lifetime must be at least its idle lifetime, otherwise the cap would shorten
     * every token from the first authorization on, and both must be positive.
     */
    @AssertTrue(message = "mcpOAuth.refreshTokenAbsoluteTtl must be positive and not shorter than mcpOAuth.refreshTokenTtl") public boolean isRefreshTokenAbsoluteTtlValid() {
        return refreshTokenTtl != null && refreshTokenAbsoluteTtl != null
                && !refreshTokenTtl.isNegative() && !refreshTokenTtl.isZero()
                && refreshTokenAbsoluteTtl.compareTo(refreshTokenTtl) >= 0;
    }

    @AssertTrue(message = "mcpOAuth.refreshLockLease must be positive") public boolean isRefreshLockLeasePositive() {
        return refreshLockLease != null && !refreshLockLease.isNegative() && !refreshLockLease.isZero();
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
