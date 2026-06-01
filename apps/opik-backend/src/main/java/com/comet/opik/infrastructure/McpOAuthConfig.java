package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;

@Data
public class McpOAuthConfig {

    @Valid @JsonProperty
    private boolean enabled = false;

    @Valid @JsonProperty
    private String baseUrl;

    // The OAuth MCP instance's canonical resource URI (RFC 8707 audience). Defaults to baseUrl + /api/v1/mcp.
    @Valid @JsonProperty
    private String mcpResourceUri;

    @Valid @JsonProperty
    @NotNull private Duration accessTokenTtl = Duration.ofHours(1);

    @Valid @JsonProperty
    @NotNull private Duration refreshTokenTtl = Duration.ofDays(7);

    @Valid @JsonProperty
    @NotNull private Duration codeTtl = Duration.ofSeconds(60);

    // Grace window during which a just-rotated refresh token is still accepted
    @Valid @JsonProperty
    @NotNull private Duration refreshRotationGrace = Duration.ofSeconds(30);

    public String getIssuer() {
        return StringUtils.stripEnd(baseUrl, "/");
    }

    public String getMcpResourceUri() {
        return StringUtils.isNotBlank(mcpResourceUri) ? mcpResourceUri : getIssuer() + "/api/v1/mcp";
    }

    // Without an absolute baseUrl the AS would advertise relative URLs in
    // /.well-known/oauth-authorization-server, breaking RFC 8414 discovery
    // for every MCP host. Validated at startup so the misconfiguration
    // surfaces as a refusal-to-boot rather than a silently broken AS.
    @AssertTrue(message = "mcpOAuth.baseUrl must be set to an absolute URL when mcpOAuth.enabled=true") public boolean isBaseUrlValidWhenEnabled() {
        return !enabled || StringUtils.isNotBlank(baseUrl);
    }
}
