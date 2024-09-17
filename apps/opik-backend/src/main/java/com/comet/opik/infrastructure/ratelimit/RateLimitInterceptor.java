package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.infrastructure.RateLimitConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;


@RequiredArgsConstructor(onConstructor_ = @Inject)
class RateLimitInterceptor implements MethodInterceptor {

    private final RateLimitService rateLimitService;
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

            Boolean limitExceeded = Mono.deferContextual(context -> {
                String apiKey = context.get(RequestContext.API_KEY);

                // Check if the rate limit is exceeded
                return rateLimitService.isLimitExceeded(apiKey, bucket, limit, limitDurationInSeconds);
            }).block();


            if (Boolean.TRUE.equals(limitExceeded)) {
                throw new ClientErrorException(429);
            }
        }

        return invocation.proceed();
    }

}