package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Provider
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RateLimitResponseFilter implements ContainerResponseFilter {

    public static final String MATCHING = "Opik-.*-Limit(-TTL-Millis)?";

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        moveRatelimitHeader(requestContext, responseContext.getHeaders());
        moveRateLimitResetHeader(requestContext, responseContext);
    }

    private void moveRateLimitResetHeader(ContainerRequestContext requestContext,
            ContainerResponseContext responseContext) {
        Optional.ofNullable(requestContext.getHeaders().get(RequestContext.RATE_LIMIT_RESET))
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(values -> responseContext.getHeaders().put(RequestContext.RATE_LIMIT_RESET,
                        List.copyOf(values)));
    }

    private void moveRatelimitHeader(ContainerRequestContext requestContext, MultivaluedMap<String, Object> headers) {
        requestContext.getHeaders().keySet()
                .stream()
                .filter(k -> k.matches(MATCHING))
                .forEach(key -> {
                    List<String> values = requestContext.getHeaders().get(key);
                    headers.add(key, values.getFirst());
                });
    }

}
