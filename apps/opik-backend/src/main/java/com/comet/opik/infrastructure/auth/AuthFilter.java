package com.comet.opik.infrastructure.auth;

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

@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AuthFilter implements ContainerRequestFilter {

    // Compiled once and reused across requests rather than recompiled on every path check.
    private static final Pattern PRIVATE_PATH_PATTERN = Pattern.compile("/v1/private/.*");
    private static final Pattern ANALYTICS_QUERIES_EXECUTOR_PATH_PATTERN = Pattern
            .compile("/v1/internal/analytics-queries-executor.*");
    private static final Pattern SESSION_PATH_PATTERN = Pattern.compile("/v1/session/.*");

    private final AuthService authService;
    private final jakarta.inject.Provider<RequestContext> requestContext;

    @Override
    public void filter(ContainerRequestContext context) throws IOException {

        var headers = getHttpHeaders(context);

        var sessionToken = headers.getCookies().get(RequestContext.SESSION_COOKIE);

        UriInfo uriInfo = context.getUriInfo();
        String path = uriInfo.getRequestUri().getPath();

        // Unlike other /v1/internal/* endpoints, the Agent Insights query executor must be authenticated: it derives
        // the bounding workspace_id from auth (see OPIK-6814 / Agent Insights technical design).
        if (PRIVATE_PATH_PATTERN.matcher(path).matches()
                || ANALYTICS_QUERIES_EXECUTOR_PATH_PATTERN.matcher(path).matches()) {
            authService.authenticate(headers, sessionToken, ContextInfoHolder.builder()
                    .uriInfo(uriInfo)
                    .method(context.getMethod())
                    .requiredPermissions(getRequiredPermissions(context))
                    .build());
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
