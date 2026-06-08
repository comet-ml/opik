package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.BEARER_PREFIX;

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AuthFilter implements ContainerRequestFilter {

    private final AuthService authService;
    private final McpOAuthService mcpOAuthService;
    private final OpikConfiguration opikConfig;
    private final jakarta.inject.Provider<RequestContext> requestContext;

    @Override
    public void filter(ContainerRequestContext context) throws IOException {

        var headers = getHttpHeaders(context);

        var sessionToken = headers.getCookies().get(RequestContext.SESSION_COOKIE);

        UriInfo uriInfo = context.getUriInfo();

        if (Pattern.matches("/v1/private/.*", uriInfo.getRequestUri().getPath())) {
            ContextInfoHolder contextInfo = ContextInfoHolder.builder()
                    .uriInfo(uriInfo)
                    .method(context.getMethod())
                    .requiredPermissions(getRequiredPermissions(context))
                    .build();
            String authHeader = context.getHeaderString(HttpHeaders.AUTHORIZATION);
            if (opikConfig.getMcpOAuth().isEnabled() && McpOAuthTokenUtils.isMcpOAuthToken(authHeader)) {
                String token = authHeader.substring(BEARER_PREFIX.length()).trim();
                ValidatedToken validatedToken = mcpOAuthService.validateAccessTokenForWorkspace(
                        token, context.getHeaderString(RequestContext.WORKSPACE_HEADER));
                authService.authorizeOAuth(validatedToken, contextInfo);
            } else {
                authService.authenticate(headers, sessionToken, contextInfo);
            }
        } else if (Pattern.matches("/v1/session/.*", uriInfo.getRequestUri().getPath())) {
            authService.authenticateSession(sessionToken);
        }

        requestContext.get().setHeaders(context.getHeaders());
    }

    @SuppressWarnings("unchecked")
    private List<String> getRequiredPermissions(ContainerRequestContext context) {
        Object permissionsValue = context.getProperty(AuthDynamicFeature.REQUIRED_PERMISSIONS_PROPERTY);
        return permissionsValue instanceof List ? (List<String>) permissionsValue : List.of();
    }

    HttpHeaders getHttpHeaders(ContainerRequestContext context) {
        return new HttpHeaders() {

            @Override
            public List<String> getRequestHeader(String s) {
                return List.of(context.getHeaderString(s));
            }

            @Override
            public String getHeaderString(String s) {
                return context.getHeaderString(s);
            }

            @Override
            public MultivaluedMap<String, String> getRequestHeaders() {
                return context.getHeaders();
            }

            @Override
            public List<MediaType> getAcceptableMediaTypes() {
                return context.getAcceptableMediaTypes();
            }

            @Override
            public List<Locale> getAcceptableLanguages() {
                return context.getAcceptableLanguages();
            }

            @Override
            public MediaType getMediaType() {
                return context.getMediaType();
            }

            @Override
            public Locale getLanguage() {
                return context.getLanguage();
            }

            @Override
            public Map<String, Cookie> getCookies() {
                return context.getCookies();
            }

            @Override
            public Date getDate() {
                return context.getDate();
            }

            @Override
            public int getLength() {
                return context.getLength();
            }
        };
    }
}
