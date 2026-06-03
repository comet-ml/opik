package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.List;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTH_METHOD_NONE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RESPONSE_TYPE_CODE;

@Path("/oauth/register")
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OAuthRegisterResource {

    private static final String RATE_LIMIT_BUCKET = "mcp_oauth_register:%s";

    private final @NonNull OAuthClientService clientService;
    private final @NonNull RateLimitService rateLimitService;
    private final @NonNull OpikConfiguration opikConfig;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(@NonNull ClientRegistrationRequest request,
            @Context HttpServletRequest httpRequest) {

        // DCR is open (no auth) by design — throttle per source IP to bound spam registrations.
        McpOAuthConfig config = opikConfig.getMcpOAuth();
        var limitConfig = new LimitConfig("McpOAuthRegister", "mcp_oauth_register",
                config.getRegistrationRateLimit(), config.getRegistrationRateLimitDuration().toSeconds(), null);
        boolean exceeded = Boolean.TRUE.equals(rateLimitService
                .isLimitExceeded(1, RATE_LIMIT_BUCKET.formatted(clientIp(httpRequest)), limitConfig)
                .block());
        if (exceeded) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new OAuthError("too_many_requests", "registration rate limit exceeded"))
                    .build();
        }

        McpOAuthClient client = clientService.register(request);
        ClientRegistrationResponse body = ClientRegistrationResponse.builder()
                .clientId(client.clientId())
                .clientIdIssuedAt(client.createdAt() == null ? null : client.createdAt().getEpochSecond())
                .clientName(client.name())
                .logoUri(client.logoUri())
                .redirectUris(client.redirectUris())
                .tokenEndpointAuthMethod(AUTH_METHOD_NONE)
                .grantTypes(List.of(GRANT_AUTHORIZATION_CODE, GRANT_REFRESH_TOKEN))
                .responseTypes(List.of(RESPONSE_TYPE_CODE))
                .build();
        // RFC 7591 §3.2.1: the response SHOULD include a Location header pointing
        // at a client-configuration endpoint. We don't expose a public read-back
        // endpoint, so we point at the admin path used for manual lifecycle
        // operations; the URI is informational and not part of the OAuth dance.
        return Response.created(URI.create("/admin/mcp-oauth-clients/" + client.clientId()))
                .entity(body)
                .build();
    }

    // First X-Forwarded-For hop set by the fronting nginx; direct remote address otherwise.
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
