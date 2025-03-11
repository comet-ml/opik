package com.comet.opik.infrastructure.ratelimit;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.List;

@Provider
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RateLimitResponseFilter implements ContainerResponseFilter {

    public static final String MATCHING = "Opik-.*-Limit(-TTL-Millis)?";

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        moveHeader(requestContext, MATCHING, responseContext.getHeaders());
    }

    private void moveHeader(ContainerRequestContext requestContext, String keyPattern,
            MultivaluedMap<String, Object> headers) {
        requestContext.getHeaders().keySet()
                .stream()
                .filter(k -> k.matches(keyPattern))
                .forEach(key -> {
                    List<String> values = requestContext.getHeaders().get(key);
                    headers.add(key, values.getFirst());
                });
    }

}
