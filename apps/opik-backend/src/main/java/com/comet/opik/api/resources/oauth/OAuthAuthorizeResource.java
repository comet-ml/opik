package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.security.MessageDigest;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.CSRF_COOKIE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_CHALLENGE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_CHALLENGE_METHOD;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REDIRECT_URI;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_RESOURCE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_RESPONSE_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_STATE;
import static java.nio.charset.StandardCharsets.UTF_8;

@Path("/oauth")
@Timed
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "MCP OAuth", description = "MCP OAuth 2.1 Authorization Server resources")
public class OAuthAuthorizeResource {

    private final @NonNull OAuthAuthorizationService authorizationService;
    private final @NonNull OpikConfiguration opikConfig;

    @GET
    @Path("/authorize")
    @Operation(operationId = "authorize", summary = "OAuth Authorization Endpoint", description = "OAuth 2.1 authorization endpoint (RFC 6749 §3.1). Validates the client and PKCE parameters, then redirects to the login or consent page", responses = {
            @ApiResponse(responseCode = "302", description = "Redirect to login, consent, or client redirect_uri with an error")})
    public Response authorize(
            @QueryParam(PARAM_CLIENT_ID) @NotBlank String clientId,
            @QueryParam(PARAM_REDIRECT_URI) @NotBlank String redirectUri,
            @QueryParam(PARAM_RESPONSE_TYPE) String responseType,
            @QueryParam(PARAM_CODE_CHALLENGE) String codeChallenge,
            @QueryParam(PARAM_CODE_CHALLENGE_METHOD) String codeChallengeMethod,
            @QueryParam(PARAM_RESOURCE) String resource,
            @QueryParam(PARAM_STATE) String state,
            @Context HttpHeaders headers,
            @Context UriInfo uriInfo) {

        Cookie session = headers.getCookies().get(RequestContext.SESSION_COOKIE);
        AuthorizeRequest request = AuthorizeRequest.builder()
                .clientId(clientId)
                .redirectUri(redirectUri)
                .responseType(responseType)
                .codeChallenge(codeChallenge)
                .codeChallengeMethod(codeChallengeMethod)
                .resource(resource)
                .state(state)
                .rawQuery(uriInfo.getRequestUri().getRawQuery())
                .build();

        return Response.status(Response.Status.FOUND)
                .location(authorizationService.resolveAuthorizeRedirect(request, session))
                .build();
    }

    @GET
    @Path("/authorize/context")
    @Operation(operationId = "getAuthorizeContext", summary = "Get Authorization Consent Context", description = "Get the client details, eligible workspaces, and a CSRF token used to render the consent screen", responses = {
            @ApiResponse(responseCode = "200", description = "Authorization consent context", content = @Content(schema = @Schema(implementation = AuthorizeContext.class)))})
    public Response context(
            @QueryParam(PARAM_CLIENT_ID) @NotBlank String clientId,
            @QueryParam(PARAM_REDIRECT_URI) @NotBlank String redirectUri,
            @Context HttpHeaders headers) {

        Cookie session = headers.getCookies().get(RequestContext.SESSION_COOKIE);
        AuthorizeContext context = authorizationService.buildConsentContext(clientId, redirectUri, session);

        NewCookie csrfCookie = new NewCookie.Builder(CSRF_COOKIE)
                .value(context.csrfToken())
                .path("/")
                .httpOnly(true)
                .secure(isSecureDeployment(opikConfig.getMcpOAuth()))
                .sameSite(NewCookie.SameSite.LAX)
                .build();
        return Response.ok(context)
                .cookie(csrfCookie)
                .build();
    }

    @POST
    @Path("/authorize")
    @Operation(operationId = "consent", summary = "Submit Authorization Consent", description = "Submit the user's consent, issue an authorization code, and return the client redirect target", responses = {
            @ApiResponse(responseCode = "200", description = "Consent response with the client redirect target", content = @Content(schema = @Schema(implementation = ConsentResponse.class)))})
    public Response consent(@NotNull @Valid ConsentRequest request, @Context HttpHeaders headers) {

        Cookie csrfCookie = headers.getCookies().get(CSRF_COOKIE);
        if (csrfCookie == null || StringUtils.isBlank(csrfCookie.getValue()) || request.csrf() == null
                || !MessageDigest.isEqual(csrfCookie.getValue().getBytes(UTF_8), request.csrf().getBytes(UTF_8))) {
            throw new ForbiddenException("invalid csrf token");
        }

        Cookie session = headers.getCookies().get(RequestContext.SESSION_COOKIE);
        return Response.ok(authorizationService.issueAuthorizationCode(request, session)).build();
    }

    private static boolean isSecureDeployment(McpOAuthConfig config) {
        return StringUtils.startsWith(config.getBaseUrl(), "https://");
    }
}
