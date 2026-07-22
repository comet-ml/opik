package com.comet.opik.api.error;

import com.comet.opik.infrastructure.metrics.UuidValidationMetrics;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

/**
 * Maps {@link InvalidUUIDException} to HTTP 400 and records the reject-rate metric.
 * Tags each rejection with the matched route and {@code mode=reject} (see {@link UuidValidationMetrics}
 * for the shared {@code opik.ingestion.uuid_v7.rejected} counter contract).
 *
 * <p>{@link ResourceInfo} is request-scoped, so it is obtained lazily through a {@link Provider}
 * (injecting it directly would fail to construct this singleton outside a request). The lookup resolves
 * because ingestion resources block on the request thread, so mapping happens within the request scope.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class InvalidUUIDExceptionMapper implements ExceptionMapper<InvalidUUIDException> {

    private static final String METRIC_NAMESPACE = "opik.ingestion";
    private static final String UNKNOWN_ROUTE = "unknown";

    private static final AttributeKey<String> HTTP_ROUTE_KEY = AttributeKey.stringKey("http_route");
    private static final AttributeKey<String> REASON_KEY = AttributeKey.stringKey("reason");

    private static final LongCounter REJECTED_COUNTER = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE)
            .counterBuilder("%s.uuid_v7.rejected".formatted(METRIC_NAMESPACE))
            .setDescription("Number of writes rejected because the id failed UUIDv7 ingestion validation")
            .build();

    private final Provider<ResourceInfo> resourceInfo;

    @Override
    public Response toResponse(@NonNull InvalidUUIDException exception) {
        var httpRoute = getHttpRoute();
        log.info("Rejected ingestion id, httpRoute: '{}', reason: '{}', error message: '{}'",
                httpRoute, exception.getReason().getValue(), exception.getMessage());
        REJECTED_COUNTER.add(1, Attributes.builder()
                .put(UuidValidationMetrics.MODE_KEY, UuidValidationMetrics.MODE_REJECT)
                .put(HTTP_ROUTE_KEY, httpRoute)
                .put(REASON_KEY, exception.getReason().getValue())
                .build());
        // Force JSON: ingestion endpoints negotiate other content types (e.g. the OTel endpoint uses protobuf),
        // which have no writer for the error entity — without this the 400 fails to serialize and surfaces as a 500
        return Response.status(BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorMessage(BAD_REQUEST.getStatusCode(), "Invalid UUID for id", exception.getMessage()))
                .build();
    }

    private String getHttpRoute() {
        try {
            var info = resourceInfo.get();
            return info == null ? UNKNOWN_ROUTE : route(info.getResourceClass(), info.getResourceMethod());
        } catch (RuntimeException exception) {
            log.warn("Failed to extract http route, defaulting to '{}'", UNKNOWN_ROUTE, exception);
            return UNKNOWN_ROUTE;
        }
    }

    /**
     * Builds the matched route from the resource class + method {@code @Path} templates (no concrete
     * ids), so the metric stays low-cardinality — e.g. {@code /v1/private/traces/batch}.
     *
     * <p>{@link #pathValue} yields {@code ""} (never {@code null}) for a missing {@code @Path}; the two
     * passes then collapse duplicate slashes and strip a trailing one. With no {@code @Path} on either,
     * the join collapses to {@code ""}, so the {@link String#isEmpty()} fallback to
     * {@link #UNKNOWN_ROUTE} is reachable.
     */
    private String route(Class<?> resourceClass, Method resourceMethod) {
        var classPath = pathValue(resourceClass);
        var methodPath = pathValue(resourceMethod);
        var route = "%s/%s".formatted(classPath, methodPath)
                .replaceAll("/+", "/")
                .replaceAll("/$", "");
        return route.isEmpty() ? UNKNOWN_ROUTE : route;
    }

    private String pathValue(Class<?> resourceClass) {
        var path = resourceClass == null ? null : resourceClass.getAnnotation(Path.class);
        return path == null ? "" : path.value();
    }

    private String pathValue(Method resourceMethod) {
        var path = resourceMethod == null ? null : resourceMethod.getAnnotation(Path.class);
        return path == null ? "" : path.value();
    }
}
