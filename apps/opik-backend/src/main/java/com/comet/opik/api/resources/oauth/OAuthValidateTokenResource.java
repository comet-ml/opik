package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_VALIDATE_TOKEN_RESOURCE_BASE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_TYPE_BEARER;

@Path(OAUTH_VALIDATE_TOKEN_RESOURCE_BASE_PATH)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "MCP OAuth", description = "MCP OAuth 2.1 Authorization Server resources")
public class OAuthValidateTokenResource {

    private final @NonNull McpOAuthService mcpOAuthService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "validateOAuthToken", summary = "Validate OAuth Access Token", description = "Introspects a bearer access token and returns the identity it resolves to", responses = {
            @ApiResponse(responseCode = "200", description = "Validated token identity", content = @Content(schema = @Schema(implementation = ValidatedToken.class))),
            @ApiResponse(responseCode = "401", description = "Missing, malformed, or unknown access token")})
    public Response validate(@HeaderParam(HttpHeaders.AUTHORIZATION) String authHeader) {
        if (!McpOAuthTokenUtils.isMcpOAuthToken(authHeader)) {
            log.info("MCP OAuth validate rejected: no bearer access token presented");
            throw new NotAuthorizedException(TOKEN_TYPE_BEARER);
        }

        String token = McpOAuthTokenUtils.extractBearerToken(authHeader);
        ValidatedToken validated = mcpOAuthService.validateAccessToken(token)
                .orElseThrow(() -> {
                    log.info("MCP OAuth validate rejected: token '{}' is not active",
                            McpOAuthTokenUtils.maskToken(token));
                    return new NotAuthorizedException(TOKEN_TYPE_BEARER);
                });

        log.info("MCP OAuth validate succeeded for token '{}'", McpOAuthTokenUtils.maskToken(token));
        return Response.ok(validated).build();
    }
}
