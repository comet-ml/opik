package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.time.Duration;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.CLIENT_CONFIG_PATH_PREFIX;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_DESC_REGISTRATION_RATE_LIMIT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_TOO_MANY_REQUESTS;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RATE_LIMIT_BUCKET;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RATE_LIMIT_BUCKET_PREFIX;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.X_FORWARDED_FOR_HEADER;

@Path("/oauth/register")
@Timed
@Slf4j
@Tag(name = "MCP OAuth", description = "MCP OAuth 2.1 Authorization Server resources")
public class OAuthRegisterResource {

    private final @NonNull OAuthClientService clientService;
    private final @NonNull RateLimitService rateLimitService;
    private final @NonNull LimitConfig limitConfig;

    @Inject
    public OAuthRegisterResource(@NonNull OAuthClientService clientService,
            @NonNull RateLimitService rateLimitService,
            @NonNull OpikConfiguration opikConfig) {
        this.clientService = clientService;
        this.rateLimitService = rateLimitService;
        McpOAuthConfig config = opikConfig.getMcpOAuth();
        this.limitConfig = new LimitConfig("McpOAuthRegister", RATE_LIMIT_BUCKET_PREFIX,
                config.getRegistrationRateLimit(), config.getRegistrationRateLimitDuration().toSeconds(), null);
    }

    /**
     * DCR is open (no auth) by design — throttle per source IP to bound spam registrations.
     * RFC 7591 §3.2.1: the response SHOULD include a Location header pointing at a client-configuration
     * endpoint. The URI is informational and not part of the OAuth dance.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "registerOAuthClient", summary = "OAuth Dynamic Client Registration Endpoint", description = "OAuth 2.0 Dynamic Client Registration (RFC 7591). Registers a public client for the MCP OAuth flow; throttled per source IP", responses = {
            @ApiResponse(responseCode = "201", description = "Registered client metadata", content = @Content(schema = @Schema(implementation = ClientRegistrationResponse.class))),
            @ApiResponse(responseCode = "429", description = "Registration rate limit exceeded", content = @Content(schema = @Schema(implementation = OAuthError.class)))})
    public Response register(@NonNull @Valid ClientRegistrationRequest request,
            @Context HttpServletRequest httpRequest) {

        log.info("MCP OAuth client registration request '{}'", request.clientName());

        String bucket = RATE_LIMIT_BUCKET.formatted(clientIp(httpRequest));
        boolean exceeded = Boolean.TRUE.equals(rateLimitService
                .isLimitExceeded(1, bucket, limitConfig)
                .block());
        if (exceeded) {
            long retryAfterSeconds = Math.max(
                    Duration.ofMillis(rateLimitService.getRemainingTTL(bucket, limitConfig).block()).toSeconds(), 1);
            log.warn("MCP OAuth client registration rate limit exceeded '{}'", retryAfterSeconds);
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .type(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
                    .entity(OAuthError.builder()
                            .error(ERROR_TOO_MANY_REQUESTS)
                            .errorDescription(ERROR_DESC_REGISTRATION_RATE_LIMIT)
                            .build())
                    .build();
        }

        McpOAuthClient client = clientService.register(request);
        ClientRegistrationResponse body = ClientRegistrationResponseMapper.INSTANCE.toResponse(client);
        log.info("MCP OAuth client registered '{}'", client.id());
        return Response.created(URI.create(CLIENT_CONFIG_PATH_PREFIX + client.id()))
                .entity(body)
                .build();
    }

    /**
     * Real client IP for per-IP throttling. The fronting nginx appends to X-Forwarded-For
     * ($proxy_add_x_forwarded_for), so the right-most hop is the address nginx actually observed and
     * is not client-spoofable; the left-most hops are attacker-controlled and must not key the bucket.
     * Falls back to the direct remote address when the header is absent.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR_HEADER);
        if (StringUtils.isNotBlank(forwarded)) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
