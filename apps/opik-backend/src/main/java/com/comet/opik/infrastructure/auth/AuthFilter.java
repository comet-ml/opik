package com.comet.opik.infrastructure.auth;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Provider
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AuthFilter implements ContainerRequestFilter {

    private final AuthService authService;
    private final jakarta.inject.Provider<RequestContext> requestContext;

    @Override
    public void filter(ContainerRequestContext context) throws IOException {

        var headers = getHttpHeaders(context);

        var sessionToken = headers.getCookies().get(RequestContext.SESSION_COOKIE);

        UriInfo uriInfo = context.getUriInfo();

        if (Pattern.matches("/v1/private/.*", uriInfo.getRequestUri().getPath())) {
            authService.authenticate(headers, sessionToken, new ContextInfoHolder(uriInfo, context.getMethod()));
        } else if (Pattern.matches("/v1/session/.*", uriInfo.getRequestUri().getPath())) {
            authService.authenticateSession(sessionToken);
        }

        requestContext.get().setHeaders(context.getHeaders());
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
