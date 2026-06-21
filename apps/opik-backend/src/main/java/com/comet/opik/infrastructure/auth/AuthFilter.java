package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.domain.mcpoauth.McpOAuthTokenUtils;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
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

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AuthFilter implements ContainerRequestFilter {

    // Compiled once and reused across requests rather than recompiled on every path check.
    private static final Pattern PRIVATE_PATH_PATTERN = Pattern.compile("/v1/private/.*");
    private static final Pattern ANALYTICS_QUERIES_PATH_PATTERN = Pattern
            .compile("/v1/internal/analytics-queries(/.*)?");
    private static final Pattern SESSION_PATH_PATTERN = Pattern.compile("/v1/session/.*");

    private final AuthService authService;
    private final McpOAuthService mcpOAuthService;
    private final OpikConfiguration opikConfig;
    private final Provider<RequestContext> requestContext;

    @Override
    public void filter(ContainerRequestContext context) throws IOException {

        var headers = getHttpHeaders(context);

        var sessionToken = headers.getCookies().get(RequestContext.SESSION_COOKIE);

        UriInfo uriInfo = context.getUriInfo();
        String path = uriInfo.getRequestUri().getPath();

        // Unlike other /v1/internal/* endpoints, the Agent Insights query executor must be authenticated: it derives
        // the bounding workspace_id from auth (see OPIK-6814 / Agent Insights technical design), so it goes through the
        // same authentication path as /v1/private/*.
        if (PRIVATE_PATH_PATTERN.matcher(path).matches()
                || ANALYTICS_QUERIES_PATH_PATTERN.matcher(path).matches()) {
            ContextInfoHolder contextInfo = ContextInfoHolder.builder()
                    .uriInfo(uriInfo)
                    .method(context.getMethod())
                    .requiredPermissions(getRequiredPermissions(context))
                    .build();
            String authHeader = context.getHeaderString(HttpHeaders.AUTHORIZATION);
            if (opikConfig.getMcpOAuth().isEnabled() && McpOAuthTokenUtils.isMcpOAuthToken(authHeader)) {
                String token = McpOAuthTokenUtils.extractBearerToken(authHeader);
                ValidatedToken validatedToken = mcpOAuthService.validateAccessTokenForWorkspace(
                        token, context.getHeaderString(RequestContext.WORKSPACE_HEADER));
                authService.authorizeOAuth(validatedToken, contextInfo);
            } else {
                authService.authenticate(headers, sessionToken, contextInfo);
            }
        } else if (SESSION_PATH_PATTERN.matcher(path).matches()) {
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
