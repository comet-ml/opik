package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

@Data
public class McpOAuthConfig {

    @Valid @JsonProperty
    private boolean enabled;

    // Empty unless enabled; validated by isBaseUrlValidWhenEnabled() rather than @NotBlank.
    @Valid @JsonProperty
    private String baseUrl;

    // The OAuth MCP instance's canonical resource URI (RFC 8707 audience). Defaults to baseUrl + /api/v1/mcp.
    @Valid @JsonProperty
    private String mcpResourceUri;

    @Valid @JsonProperty
    @NotNull private Duration accessTokenTtl;

    @Valid @JsonProperty
    @NotNull private Duration refreshTokenTtl;

    @Valid @JsonProperty
    @NotNull private Duration codeTtl;

    // Grace window during which a just-rotated refresh token is still accepted
    @Valid @JsonProperty
    @NotNull private Duration refreshRotationGrace;

    // DCR (RFC 7591) is deliberately unauthenticated, so cap registrations per source IP per window.
    @Valid @JsonProperty
    @Min(1) private long registrationRateLimit;

    @Valid @JsonProperty
    @NotNull private Duration registrationRateLimitDuration;

    public String getIssuer() {
        return StringUtils.stripEnd(baseUrl, "/");
    }

    public String getMcpResourceUri() {
        return StringUtils.isNotBlank(mcpResourceUri) ? mcpResourceUri : getIssuer() + "/api/v1/mcp";
    }

    // Without an absolute http/https baseUrl the AS would advertise relative
    // or non-fetchable URLs in /.well-known/oauth-authorization-server,
    // breaking RFC 8414 discovery for every MCP host. Validated at startup so
    // the misconfiguration surfaces as a refusal-to-boot rather than a
    // silently broken AS.
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
