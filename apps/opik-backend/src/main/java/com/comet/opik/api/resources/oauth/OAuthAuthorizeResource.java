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
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CSRF_COOKIE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_CLIENT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_REQUEST;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_TARGET;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_UNSUPPORTED_RESPONSE_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RESPONSE_TYPE_CODE;
import static java.nio.charset.StandardCharsets.UTF_8;

@Path("/oauth")
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OAuthAuthorizeResource {

    private final @NonNull OAuthClientService clientService;
    private final @NonNull AuthService authService;
    private final @NonNull McpOAuthService mcpOAuthService;
    private final @NonNull OpikConfiguration opikConfig;

    @GET
    @Path("/authorize")
    public Response authorize(
            @QueryParam("client_id") @NotBlank String clientId,
            @QueryParam("redirect_uri") @NotBlank String redirectUri,
            @QueryParam("response_type") String responseType,
            @QueryParam("code_challenge") String codeChallenge,
            @QueryParam("code_challenge_method") String codeChallengeMethod,
            @QueryParam("resource") String resource,
            @QueryParam("state") String state,
            @Context HttpHeaders headers,
            @Context UriInfo uriInfo) {

        McpOAuthConfig config = opikConfig.getMcpOAuth();
        McpOAuthClient client = requireClientWithRedirect(clientId, redirectUri);

        // client and redirect_uri are now trusted, so protocol errors are reported back to the client via redirect.
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
            return redirect(config.getBaseUrl() + "/login?returnTo=" + enc(authorizeUrl));
        }

        String query = "client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=" + enc(responseType)
                + "&code_challenge=" + enc(codeChallenge)
                + "&code_challenge_method=" + enc(codeChallengeMethod)
                + "&resource=" + enc(resource)
                + (StringUtils.isBlank(state) ? "" : "&state=" + enc(state));
        return redirect(config.getBaseUrl() + "/oauth/consent?" + query);
    }

    @GET
    @Path("/authorize/context")
    @Produces(MediaType.APPLICATION_JSON)
    public Response context(
            @QueryParam("client_id") @NotBlank String clientId,
            @QueryParam("redirect_uri") @NotBlank String redirectUri,
            @Context HttpHeaders headers) {

        McpOAuthClient client = requireClientWithRedirect(clientId, redirectUri);

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
    public Response consent(@NotNull @Valid ConsentRequest request, @Context HttpHeaders headers) {

        Cookie csrfCookie = headers.getCookies().get(CSRF_COOKIE);
        if (csrfCookie == null || StringUtils.isBlank(csrfCookie.getValue()) || request.csrf() == null
                || !MessageDigest.isEqual(csrfCookie.getValue().getBytes(UTF_8), request.csrf().getBytes(UTF_8))) {
            throw new ForbiddenException("invalid csrf token");
        }

        McpOAuthConfig config = opikConfig.getMcpOAuth();
        requireClientWithRedirect(request.clientId(), request.redirectUri());
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

        String query = "code=" + enc(code)
                + (StringUtils.isBlank(request.state()) ? "" : "&state=" + enc(request.state()));
        return Response.ok(ConsentResponse.builder()
                .redirectTo(appendQuery(request.redirectUri(), query))
                .build()).build();
    }

    /**
     * Resolves the client and validates the {@code redirect_uri} against its registered URIs.
     * <p>
     * Per RFC 6749 §3.1.2 the redirection endpoint URI MUST NOT include a fragment, otherwise the appended
     * {@code code}/{@code error}/{@code state} query params would land inside the fragment and be unreadable
     * by the client.
     */
    private McpOAuthClient requireClientWithRedirect(String clientId, String redirectUri) {
        Optional<McpOAuthClient> client = clientService.resolve(clientId);
        if (client.isEmpty()) {
            throw new BadRequestException(ERROR_INVALID_CLIENT);
        }
        if (redirectUri.contains("#") || !clientService.matchesRedirectUri(client.get(), redirectUri)) {
            throw new BadRequestException("invalid redirect_uri");
        }
        return client.get();
    }

    private Response errorRedirect(String redirectUri, String error, String state) {
        String query = "error=" + error + (StringUtils.isBlank(state) ? "" : "&state=" + enc(state));
        return redirect(appendQuery(redirectUri, query));
    }

    private static Response redirect(String location) {
        return Response.status(Response.Status.FOUND).location(URI.create(location)).build();
    }

    private static String appendQuery(String base, String query) {
        return base + (base.contains("?") ? "&" : "?") + query;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, UTF_8);
    }

    private static boolean isSecureDeployment(McpOAuthConfig config) {
        return StringUtils.startsWith(config.getBaseUrl(), "https://");
    }
}
