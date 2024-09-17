package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.infrastructure.RateLimitConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;

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

        RateLimited rateLimit = method.getAnnotation(RateLimited.class);
        String bucket = rateLimit.value();

        if (!rateLimitConfig.isEnabled()) {
            return invocation.proceed();
        }

        // Check if the bucket is the general events bucket
        if (bucket.equals(RateLimited.GENERAL_EVENTS)) {

            long limit = rateLimitConfig.getGeneralEvents().limit();
            long limitDurationInSeconds = rateLimitConfig.getGeneralEvents().durationInSeconds();
            String apiKey = requestContext.get().getApiKey();

            // Check if the rate limit is exceeded
            Boolean limitExceeded = rateLimitService.get()
                    .isLimitExceeded(apiKey, bucket, limit, limitDurationInSeconds)
                    .block();

            if (Boolean.TRUE.equals(limitExceeded)) {
                throw new ClientErrorException("Too Many Requests", 429);
            }

            try {
                return invocation.proceed();
            } catch (Exception ex) {
                decreaseLimitInCaseOfError(bucket);
                throw ex;
            }
        }

        return invocation.proceed();
    }

    private void decreaseLimitInCaseOfError(String bucket) {
        try {
            Mono.deferContextual(context -> {
                String apiKey = context.get(RequestContext.API_KEY);

                return rateLimitService.get().decrement(apiKey, bucket);
            }).subscribe();
        } catch (Exception ex) {
            log.warn("Failed to decrement rate limit", ex);
        }
    }

}