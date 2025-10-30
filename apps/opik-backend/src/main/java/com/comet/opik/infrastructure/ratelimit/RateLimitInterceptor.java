package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.infrastructure.RateLimitConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.hc.core5.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;

@Slf4j
@RequiredArgsConstructor
class RateLimitInterceptor implements MethodInterceptor {

    private static final String KEY = "rate-limit:%s-%s";

    // OpenTelemetry metrics - initialized once at class loading
    private static final Meter METER = GlobalOpenTelemetry.get().getMeter("opik.ratelimit");
    private static final AttributeKey<String> HTTP_ROUTE_KEY = AttributeKey.stringKey("http_route");
    private static final AttributeKey<String> HTTP_REQUEST_METHOD_KEY = AttributeKey.stringKey("http_request_method");

    private static final LongCounter ENTITIES_CREATED_UPDATED = METER
            .counterBuilder("opik.resources.entities")
            .setDescription(
                    "Number of entities created/updated through endpoints (tagged by http_route and http_request_method)")
            .build();

    private final Provider<RequestContext> requestContext;
    private final Provider<RateLimitService> rateLimitService;
    private final RateLimitConfig rateLimitConfig;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {

        // Get the method being invoked
        Method method = invocation.getMethod();

        // Check if the method is annotated with @RateLimited or if rate limiting is disabled
        if (!rateLimitConfig.isEnabled() || !method.isAnnotationPresent(RateLimited.class)) {
            return invocation.proceed();
        }

        RateLimited rateLimit = method.getAnnotation(RateLimited.class);

        Map<String, LimitConfig> limits = new HashMap<>();

        // Check if the workspace limit should be affected
        if (rateLimit.shouldAffectWorkspaceLimit()) {
            LimitConfig workspaceLimit = getLimitOrDefault(requestContext.get().getWorkspaceId(),
                    rateLimitConfig.getWorkspaceLimit());
            String key = KEY.formatted(RateLimited.WORKSPACE_EVENTS, requestContext.get().getWorkspaceId());
            limits.put(key, workspaceLimit);
        }

        // Check events bucket
        if (rateLimit.shouldAffectUserGeneralLimit()) {
            LimitConfig generalLimit = rateLimitConfig.getGeneralLimit();
            String key = KEY.formatted(RateLimited.GENERAL_EVENTS, requestContext.get().getApiKey());
            limits.put(key, generalLimit);
        }

        for (var limit : rateLimit.value()) {
            String bucketName = getBucketName(limit);
            LimitConfig limitConfig = rateLimitConfig.getCustomLimits().get(bucketName);
            if (limitConfig != null) {
                if (containsPlacedHolder(limit)) {
                    var actualLimit = replaceLimitVariables(limit);
                    limits.put(actualLimit, limitConfig);
                } else {
                    limits.put(limit, limitConfig);
                }
            } else {
                log.warn("Rate limit bucket not found: '{}'", bucketName);
            }
        }

        Object body = getParameters(invocation);

        long events = body instanceof RateEventContainer container ? container.eventCount() : 1;

        verifyRateLimit(events, limits);

        // Extract HTTP route and method for metric tagging
        String httpRoute = extractHttpRoute(invocation);
        String httpMethod = extractHttpMethod(invocation);

        try {
            Object result = invocation.proceed();

            // Record metric for entities created/updated
            ENTITIES_CREATED_UPDATED.add(events,
                    Attributes.of(HTTP_ROUTE_KEY, httpRoute, HTTP_REQUEST_METHOD_KEY, httpMethod));

            return result;
        } finally {
            setLimitHeaders(limits, null);
        }
    }

    private String replaceLimitVariables(String limit) {
        return limit
                .replace(":{workspaceId}", requestContext.get().getWorkspaceId())
                .replace(":{apiKey}", requestContext.get().getApiKey());
    }

    private String getBucketName(String limit) {
        return limit
                .replace(":{workspaceId}", "")
                .replace(":{apiKey}", "");
    }

    private LimitConfig getLimitOrDefault(String bucket, LimitConfig defaultLimit) {
        return Optional.ofNullable(rateLimitConfig.getCustomLimits())
                .map(limitConfigs -> limitConfigs.get(bucket))
                .orElse(defaultLimit);
    }

    private boolean containsPlacedHolder(String limit) {
        return limit.contains("{") && limit.contains("}");
    }

    private void verifyRateLimit(long events, Map<String, LimitConfig> limitConfigs) {

        // Check if the rate limit is exceeded
        limitConfigs.forEach((bucket, limitConfig) -> {
            Boolean limitExceeded = rateLimitService.get()
                    .isLimitExceeded(events, bucket, limitConfig)
                    .block();

            if (Boolean.TRUE.equals(limitExceeded)) {
                setLimitHeaders(limitConfigs, limitConfig);
                throw new ClientErrorException("Too Many Requests: %s".formatted(limitConfig.errorMessage()),
                        HttpStatus.SC_TOO_MANY_REQUESTS);
            }
        });
    }

    private void setLimitHeaders(Map<String, LimitConfig> limitConfigs, LimitConfig limitExceededConfig) {

        List<Tuple3<Long, Long, LimitConfig>> limits = Flux.fromIterable(limitConfigs.entrySet())
                .flatMap(entry -> Mono.zip(
                        rateLimitService.get().getRemainingTTL(entry.getKey(), entry.getValue()),
                        rateLimitService.get().availableEvents(entry.getKey(), entry.getValue()),
                        Mono.just(entry.getValue())))
                .collectList()
                .block();

        limits.forEach(tuple -> {
            var ttl = tuple.getT1();
            var remainingLimit = tuple.getT2();
            var limitConfig = tuple.getT3();

            Optional.ofNullable(limitExceededConfig)
                    .filter(config -> config.equals(limitConfig))
                    .ifPresent(config -> requestContext.get().getHeaders()
                            .put(RequestContext.RATE_LIMIT_RESET,
                                    List.of(String.valueOf(Math.max(Duration.ofMillis(ttl).toSeconds(), 1)))));

            requestContext.get().getHeaders().put(RequestContext.LIMIT.formatted(limitConfig.headerName()),
                    List.of(limitConfig.userFacingBucketName()));

            try {
                requestContext.get().getHeaders().put(
                        RequestContext.LIMIT_REMAINING_TTL.formatted(limitConfig.headerName()),
                        List.of("" + ttl));

                requestContext.get().getHeaders().put(
                        RequestContext.REMAINING_LIMIT.formatted(limitConfig.headerName()),
                        List.of("" + remainingLimit));

            } catch (Exception e) {
                log.error("Error setting rate limit headers", e);
            }
        });
    }

    private Object getParameters(MethodInvocation method) {

        for (int i = 0; i < method.getArguments().length; i++) {
            if (method.getMethod().getParameters()[i].isAnnotationPresent(RequestBody.class)) {
                return method.getArguments()[i];
            }
        }

        return null;
    }

    /**
     * Extracts the HTTP route from the method invocation by combining class-level and method-level @Path annotations.
     *
     * @param invocation the method invocation
     * @return the HTTP route path, or "unknown" if no @Path annotation is found
     */
    private String extractHttpRoute(MethodInvocation invocation) {
        try {
            Method method = invocation.getMethod();
            Class<?> declaringClass = method.getDeclaringClass();

            // Get class-level @Path annotation
            String classPath = "";
            Path classPathAnnotation = declaringClass.getAnnotation(Path.class);
            if (classPathAnnotation != null) {
                classPath = classPathAnnotation.value();
            }

            // Get method-level @Path annotation
            String methodPath = "";
            Path methodPathAnnotation = method.getAnnotation(Path.class);
            if (methodPathAnnotation != null) {
                methodPath = methodPathAnnotation.value();
            }

            // Combine class and method paths with proper "/" separator
            String fullPath;
            if (classPath.isEmpty()) {
                fullPath = methodPath;
            } else if (methodPath.isEmpty()) {
                fullPath = classPath;
            } else {
                // Ensure proper "/" separator between class and method paths
                String separator = classPath.endsWith("/") || methodPath.startsWith("/") ? "" : "/";
                fullPath = classPath + separator + methodPath;
            }

            // Return the route or "unknown" if no path is found
            return fullPath.isEmpty() ? "unknown" : fullPath;

        } catch (Exception exception) {
            log.warn("Failed to extract HTTP route from method invocation", exception);
            return "unknown";
        }
    }

    /**
     * Extracts the HTTP method from the method invocation by checking JAX-RS HTTP method annotations.
     *
     * @param invocation the method invocation
     * @return the HTTP method (GET, POST, PUT, DELETE, etc.), or "unknown" if no HTTP method annotation is found
     */
    private String extractHttpMethod(MethodInvocation invocation) {
        try {
            Method method = invocation.getMethod();

            // Check for JAX-RS HTTP method annotations
            return Arrays.stream(method.getAnnotations())
                    .map(Annotation::annotationType)
                    .map(t -> t.getAnnotation(HttpMethod.class)) // meta-annotation on @GET/@POST/...
                    .filter(Objects::nonNull)
                    .map(HttpMethod::value) // e.g., "GET"
                    .findFirst()
                    .orElse("unknown");

        } catch (Exception exception) {
            log.warn("Failed to extract HTTP method from method invocation", exception);
            return "unknown";
        }
    }
}