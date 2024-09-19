package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.infrastructure.RateLimitConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.hc.core5.http.HttpStatus;

import java.lang.reflect.Method;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
class RateLimitInterceptor implements MethodInterceptor {

    private final Provider<RequestContext> requestContext;
    private final Provider<RateLimitService> rateLimitService;
    private final RateLimitConfig rateLimitConfig;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {

        // Get the method being invoked
        Method method = invocation.getMethod();

        // Check if the method is annotated with @RateLimit
        if (!method.isAnnotationPresent(RateLimited.class)) {
            return invocation.proceed();
        }

        if (!rateLimitConfig.isEnabled()) {
            return invocation.proceed();
        }

        RateLimited rateLimit = method.getAnnotation(RateLimited.class);
        String bucket = rateLimit.value();

        // Check if the bucket is the general events bucket
        if (bucket.equals(RateLimited.GENERAL_EVENTS)) {

            Object body = getParameters(invocation);
            long events = body instanceof RateEventContainer container ? container.eventCount() : 1;

            long limit = rateLimitConfig.getGeneralEvents().limit();
            long limitDurationInSeconds = rateLimitConfig.getGeneralEvents().durationInSeconds();
            String apiKey = requestContext.get().getApiKey();

            // Check if the rate limit is exceeded
            Boolean limitExceeded = rateLimitService.get()
                    .isLimitExceeded(apiKey, events, bucket, limit, limitDurationInSeconds)
                    .block();

            if (Boolean.TRUE.equals(limitExceeded)) {
                setLimitHeaders(apiKey, bucket);
                throw new ClientErrorException("Too Many Requests", HttpStatus.SC_TOO_MANY_REQUESTS);
            }

            try {
                return invocation.proceed();
            } catch (Exception ex) {
                decreaseLimitInCaseOfError(bucket, events);
                throw ex;
            } finally {
                setLimitHeaders(apiKey, bucket);
            }
        }

        return invocation.proceed();
    }

    private void setLimitHeaders(String apiKey, String bucket) {
        requestContext.get().getHeaders().put(RequestContext.USER_LIMIT, List.of(RateLimited.GENERAL_EVENTS));
        requestContext.get().getHeaders().put(RequestContext.USER_LIMIT_REMAINING_TTL, List.of("" + rateLimitService.get().getRemainingTTL(apiKey, bucket).block()));
        requestContext.get().getHeaders().put(RequestContext.USER_REMAINING_LIMIT, List.of("" + rateLimitService.get().availableEvents(apiKey, bucket).block()));
    }

    private Object getParameters(MethodInvocation method) {

        for (int i = 0; i < method.getArguments().length; i++) {
            if (method.getMethod().getParameters()[i].isAnnotationPresent(RequestBody.class)) {
                return method.getArguments()[i];
            }
        }

        return null;
    }

    private void decreaseLimitInCaseOfError(String bucket, Long events) {
        try {
            String apiKey = requestContext.get().getApiKey();
            rateLimitService.get().decrement(apiKey, bucket, events)
                    .subscribe();
        } catch (Exception ex) {
            log.warn("Failed to decrement rate limit", ex);
        }
    }

}