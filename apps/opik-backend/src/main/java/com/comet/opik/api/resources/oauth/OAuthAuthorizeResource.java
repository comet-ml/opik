package com.comet.opik.api.resources.oauth;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.mcpoauth.CreateOAuthCodeCommand;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokens;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.AuthService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.UserWorkspace;
import com.comet.opik.infrastructure.auth.WorkspaceInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
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
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.security.MessageDigest;
import java.util.List;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CSRF_COOKIE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_TARGET;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_RESPONSE_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_CHALLENGE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_CHALLENGE_METHOD;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_ERROR;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REDIRECT_URI;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_RESOURCE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_RESPONSE_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_STATE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RESPONSE_TYPE_CODE;
import static java.nio.charset.StandardCharsets.UTF_8;

@Path("/oauth")
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "MCP OAuth", description = "MCP OAuth 2.1 Authorization Server resources")
public class OAuthAuthorizeResource {

    private final @NonNull OAuthClientService clientService;
    private final @NonNull AuthService authService;
    private final @NonNull McpOAuthService mcpOAuthService;
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

        McpOAuthConfig config = opikConfig.getMcpOAuth();
        clientService.resolveForRedirect(clientId, redirectUri);

        if (!RESPONSE_TYPE_CODE.equals(responseType)) {
            return errorRedirect(redirectUri, ERROR_UNSUPPORTED_RESPONSE_TYPE, state);
        }
        if (StringUtils.isBlank(codeChallenge)
                || !CODE_CHALLENGE_METHOD_S256.equals(codeChallengeMethod)) {
            return errorRedirect(redirectUri, ERROR_INVALID_REQUEST, state);
        }
        if (!config.getMcpResourceUri().equals(resource)) {
            return errorRedirect(redirectUri, ERROR_INVALID_TARGET, state);
        }

        Cookie session = headers.getCookies().get(RequestContext.SESSION_COOKIE);
        try {
            authService.listEligibleWorkspaces(session);
        } catch (ClientErrorException e) {
            String rawQuery = uriInfo.getRequestUri().getRawQuery();
            String authorizeUrl = config.getBaseUrl() + "/oauth/authorize" + (rawQuery == null ? "" : "?" + rawQuery);
            URI loginUri = UriBuilder.fromUri(config.getBaseUrl())
                    .path("/login")
                    .queryParam("returnTo", authorizeUrl)
                    .build();
            return redirect(loginUri);
        }

        UriBuilder consentUri = UriBuilder.fromUri(config.getBaseUrl())
                .path("/oauth/consent")
                .queryParam(PARAM_CLIENT_ID, clientId)
                .queryParam(PARAM_REDIRECT_URI, redirectUri)
                .queryParam(PARAM_RESPONSE_TYPE, responseType)
                .queryParam(PARAM_CODE_CHALLENGE, codeChallenge)
                .queryParam(PARAM_CODE_CHALLENGE_METHOD, codeChallengeMethod)
                .queryParam(PARAM_RESOURCE, resource);
        if (StringUtils.isNotBlank(state)) {
            consentUri.queryParam(PARAM_STATE, state);
        }
        return redirect(consentUri.build());
    }

    @GET
    @Path("/authorize/context")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "getAuthorizeContext", summary = "Get Authorization Consent Context", description = "Get the client details, eligible workspaces, and a CSRF token used to render the consent screen", responses = {
            @ApiResponse(responseCode = "200", description = "Authorization consent context", content = @Content(schema = @Schema(implementation = AuthorizeContext.class)))})
    public Response context(
            @QueryParam(PARAM_CLIENT_ID) @NotBlank String clientId,
            @QueryParam(PARAM_REDIRECT_URI) @NotBlank String redirectUri,
            @Context HttpHeaders headers) {

        McpOAuthClient client = clientService.resolveForRedirect(clientId, redirectUri);

        Cookie session = headers.getCookies().get(RequestContext.SESSION_COOKIE);
        List<WorkspaceInfo> workspaces = authService.listEligibleWorkspaces(session);

        String csrf = McpOAuthTokens.randomToken();
        NewCookie csrfCookie = new NewCookie.Builder(CSRF_COOKIE)
                .value(csrf)
                .path("/")
                .httpOnly(true)
                .secure(isSecureDeployment(opikConfig.getMcpOAuth()))
                .sameSite(NewCookie.SameSite.LAX)
                .build();
        return Response.ok(AuthorizeContext.builder()
                .clientName(client.name())
                .clientLogoUri(client.logoUri())
                .workspaces(workspaces)
                .csrfToken(csrf)
                .build())
                .cookie(csrfCookie)
                .build();
    }

    @POST
    @Path("/authorize")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "consent", summary = "Submit Authorization Consent", description = "Submit the user's consent, issue an authorization code, and return the client redirect target", responses = {
            @ApiResponse(responseCode = "200", description = "Consent response with the client redirect target", content = @Content(schema = @Schema(implementation = ConsentResponse.class)))})
    public Response consent(@NotNull @Valid ConsentRequest request, @Context HttpHeaders headers) {

        Cookie csrfCookie = headers.getCookies().get(CSRF_COOKIE);
        if (csrfCookie == null || StringUtils.isBlank(csrfCookie.getValue()) || request.csrf() == null
                || !MessageDigest.isEqual(csrfCookie.getValue().getBytes(UTF_8), request.csrf().getBytes(UTF_8))) {
            throw new ForbiddenException("invalid csrf token");
        }

        McpOAuthConfig config = opikConfig.getMcpOAuth();
        clientService.resolveForRedirect(request.clientId(), request.redirectUri());
        if (!config.getMcpResourceUri().equals(request.resource())) {
            throw new BadRequestException(ERROR_INVALID_TARGET);
        }

        Cookie session = headers.getCookies().get(RequestContext.SESSION_COOKIE);
        UserWorkspace workspace = authService.authorizeWorkspace(session, request.workspaceName());

        String code = mcpOAuthService.createAuthorizationCode(CreateOAuthCodeCommand.builder()
                .clientId(request.clientId())
                .userName(workspace.userName())
                .workspaceName(workspace.workspaceName())
                .workspaceId(workspace.workspaceId())
                .codeChallenge(request.codeChallenge())
                .redirectUri(request.redirectUri())
                .resource(request.resource())
                .build());

        UriBuilder redirectTo = UriBuilder.fromUri(request.redirectUri()).queryParam(PARAM_CODE, code);
        if (StringUtils.isNotBlank(request.state())) {
            redirectTo.queryParam(PARAM_STATE, request.state());
        }
        return Response.ok(ConsentResponse.builder()
                .redirectTo(redirectTo.build().toString())
                .build()).build();
    }

    private Response errorRedirect(String redirectUri, String error, String state) {
        UriBuilder builder = UriBuilder.fromUri(redirectUri).queryParam(PARAM_ERROR, error);
        if (StringUtils.isNotBlank(state)) {
            builder.queryParam(PARAM_STATE, state);
        }
        return redirect(builder.build());
    }

    private static Response redirect(URI location) {
        return Response.status(Response.Status.FOUND).location(location).build();
    }

    private static boolean isSecureDeployment(McpOAuthConfig config) {
        return StringUtils.startsWith(config.getBaseUrl(), "https://");
    }
}
