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
import java.util.Optional;

import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;

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

        if (!rateLimitConfig.isEnabled()) {
            return invocation.proceed();
        }

        // Check if the method is annotated with @RateLimit
        if (!method.isAnnotationPresent(RateLimited.class)) {
            return invocation.proceed();
        }

        RateLimited rateLimit = method.getAnnotation(RateLimited.class);

        // Check events bucket
        Optional<LimitConfig> limitConfig = Optional.ofNullable(rateLimitConfig.getCustomLimits())
                .map(limits -> limits.get(rateLimit.value()));

        String limitBucket = limitConfig.isPresent() ? rateLimit.value() : RateLimited.GENERAL_EVENTS;

        LimitConfig generalLimit = limitConfig
                .orElse(rateLimitConfig.getGeneralLimit());

        String apiKey = requestContext.get().getApiKey();
        Object body = getParameters(invocation);

        long events = body instanceof RateEventContainer container ? container.eventCount() : 1;

        verifyRateLimit(events, apiKey, limitBucket, generalLimit);

        try {
            return invocation.proceed();
        } finally {
            setLimitHeaders(apiKey, limitBucket);
        }
    }

    private void verifyRateLimit(long events, String apiKey, String bucket, LimitConfig limitConfig) {

        // Check if the rate limit is exceeded
        Boolean limitExceeded = rateLimitService.get()
                .isLimitExceeded(apiKey, events, bucket, limitConfig.limit(), limitConfig.durationInSeconds())
                .block();

        if (Boolean.TRUE.equals(limitExceeded)) {
            setLimitHeaders(apiKey, bucket);
            throw new ClientErrorException("Too Many Requests", HttpStatus.SC_TOO_MANY_REQUESTS);
        }
    }

    private void setLimitHeaders(String apiKey, String bucket) {
        requestContext.get().getHeaders().put(RequestContext.USER_LIMIT, List.of(bucket));
        requestContext.get().getHeaders().put(RequestContext.USER_LIMIT_REMAINING_TTL,
                List.of("" + rateLimitService.get().getRemainingTTL(apiKey, bucket).block()));
        requestContext.get().getHeaders().put(RequestContext.USER_REMAINING_LIMIT,
                List.of("" + rateLimitService.get().availableEvents(apiKey, bucket).block()));
    }

    private Object getParameters(MethodInvocation method) {

        for (int i = 0; i < method.getArguments().length; i++) {
            if (method.getMethod().getParameters()[i].isAnnotationPresent(RequestBody.class)) {
                return method.getArguments()[i];
            }
        }

        return null;
    }
}