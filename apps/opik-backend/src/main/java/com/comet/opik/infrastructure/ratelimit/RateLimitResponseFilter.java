package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.List;

@Provider
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RateLimitResponseFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        List<Object> userLimit = getValueFromHeader(requestContext, RequestContext.USER_LIMIT);
        List<Object> remainingLimit = getValueFromHeader(requestContext, RequestContext.USER_REMAINING_LIMIT);
        List<Object> remainingTtl = getValueFromHeader(requestContext, RequestContext.USER_LIMIT_REMAINING_TTL);

        responseContext.getHeaders().put(RequestContext.USER_LIMIT, userLimit);
        responseContext.getHeaders().put(RequestContext.USER_REMAINING_LIMIT, remainingLimit);
        responseContext.getHeaders().put(RequestContext.USER_LIMIT_REMAINING_TTL, remainingTtl);
    }

    private List<Object> getValueFromHeader(ContainerRequestContext requestContext, String key) {
        return requestContext.getHeaders().getOrDefault(key, List.of())
                .stream()
                .map(Object.class::cast)
                .toList();
    }

}
