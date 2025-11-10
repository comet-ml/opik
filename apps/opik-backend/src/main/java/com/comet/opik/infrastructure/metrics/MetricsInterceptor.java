package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Provider;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.TRACER_NAME;

/**
 * AOP interceptor that automatically collects OpenTelemetry metrics for methods annotated with {@link Metered}.
 * 
 * <p>This interceptor records the following metrics:
 * <ul>
 *   <li><b>opik.operation.count</b> - Counter of operations with status (success/error) and has_attachments labels</li>
 *   <li><b>opik.operation.duration</b> - Histogram of operation duration in milliseconds</li>
 *   <li><b>opik.operation.batch_size</b> - Histogram of batch sizes (when enabled)</li>
 *   <li><b>opik.operation.errors</b> - Counter of errors by error type</li>
 *   <li><b>opik.operation.payload_size</b> - Histogram of payload sizes in bytes</li>
 *   <li><b>opik.operation.attachments_count</b> - Histogram of attachment counts (when enabled)</li>
 * </ul>
 * 
 * <p>All metrics include the following labels:
 * <ul>
 *   <li><b>operation</b> - The operation name from the annotation</li>
 *   <li><b>entity_type</b> - The entity type from the annotation</li>
 *   <li><b>workspace_name</b> - The workspace name from the RequestContext</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class MetricsInterceptor implements MethodInterceptor {

    private final @NonNull Provider<RequestContext> requestContext;

    // OpenTelemetry metrics
    private final Meter meter = GlobalOpenTelemetry.get().getMeter(TRACER_NAME);
    private final LongCounter operationCounter = meter.counterBuilder("opik.operation.count")
            .setDescription("Total number of operations performed")
            .build();
    private final LongHistogram durationHistogram = meter.histogramBuilder("opik.operation.duration")
            .setDescription("Operation duration in milliseconds")
            .ofLongs()
            .build();
    private final LongHistogram batchSizeHistogram = meter.histogramBuilder("opik.operation.batch_size")
            .setDescription("Number of items in batch operations")
            .ofLongs()
            .build();
    private final LongCounter errorCounter = meter.counterBuilder("opik.operation.errors")
            .setDescription("Total number of errors by type")
            .build();
    private final LongHistogram payloadSizeHistogram = meter.histogramBuilder("opik.operation.payload_size")
            .setDescription("Size of request payload in bytes")
            .ofLongs()
            .build();
    private final LongHistogram attachmentCountHistogram = meter.histogramBuilder("opik.operation.attachments_count")
            .setDescription("Number of attachments (images/files) in the operation")
            .ofLongs()
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Patterns for attachment detection
    private static final Pattern STRIPPED_ATTACHMENT_PATTERN = Pattern
            .compile("\\[([^\\]]+\\.(png|jpg|jpeg|gif|pdf|txt|json|csv|mp4|webm|mp3|wav))\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_URI_PATTERN = Pattern.compile("data:[^;]+;base64,");

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        // Check if method is annotated with @Metered
        if (!method.isAnnotationPresent(Metered.class)) {
            return invocation.proceed();
        }

        Metered metered = method.getAnnotation(Metered.class);
        long startTime = System.currentTimeMillis();
        boolean success = false;
        Throwable caughtException = null;

        // Extract metrics data before execution
        int batchSize = 0;
        long payloadSize = 0;
        int attachmentCount = 0;
        boolean hasAttachments = false;

        try {
            Object[] arguments = invocation.getArguments();

            // Extract batch size if enabled
            if (metered.recordBatchSize()) {
                batchSize = extractBatchSize(arguments);
            }

            // Calculate payload size
            payloadSize = calculatePayloadSize(arguments);

            // Detect attachments if enabled
            if (metered.recordAttachments()) {
                AttachmentInfo attachmentInfo = detectAttachments(arguments);
                attachmentCount = attachmentInfo.count();
                hasAttachments = attachmentInfo.hasAttachments();
            }

        } catch (Exception e) {
            log.debug("Failed to extract metrics metadata for operation: '{}'", metered.operation(), e);
        }

        try {
            // Execute the actual method
            Object result = invocation.proceed();
            success = true;
            return result;

        } catch (Throwable throwable) {
            success = false;
            caughtException = throwable;
            throw throwable;

        } finally {
            long duration = System.currentTimeMillis() - startTime;

            try {
                // Record metrics
                recordMetrics(metered, duration, success, caughtException, batchSize, payloadSize, attachmentCount,
                        hasAttachments);
            } catch (Exception e) {
                log.warn("Failed to record metrics for operation: '{}'", metered.operation(), e);
            }
        }
    }

    private void recordMetrics(Metered metered, long duration, boolean success, Throwable exception, int batchSize,
            long payloadSize, int attachmentCount, boolean hasAttachments) {

        String workspaceName = getWorkspaceName();

        // Build base attributes
        AttributesBuilder baseBuilder = Attributes.builder()
                .put("operation", metered.operation())
                .put("entity_type", metered.entity())
                .put("workspace_name", workspaceName);

        Attributes baseAttributes = baseBuilder.build();

        // Record operation count with status and has_attachments labels
        Attributes countAttributes = baseBuilder
                .put("status", success ? "success" : "error")
                .put("has_attachments", String.valueOf(hasAttachments))
                .build();
        operationCounter.add(1, countAttributes);

        // Record duration
        durationHistogram.record(duration, baseAttributes);

        // Record batch size if applicable
        if (metered.recordBatchSize() && batchSize > 0) {
            batchSizeHistogram.record(batchSize, baseAttributes);
        }

        // Record payload size
        if (payloadSize > 0) {
            payloadSizeHistogram.record(payloadSize, baseAttributes);
        }

        // Record attachment count if applicable
        if (metered.recordAttachments() && attachmentCount > 0) {
            attachmentCountHistogram.record(attachmentCount, baseAttributes);
        }

        // Record errors
        if (!success && exception != null) {
            Attributes errorAttributes = baseBuilder
                    .put("error_type", exception.getClass().getSimpleName())
                    .build();
            errorCounter.add(1, errorAttributes);
        }
    }

    private String getWorkspaceName() {
        try {
            String workspaceName = requestContext.get().getWorkspaceName();
            if (workspaceName == null || workspaceName.isEmpty()) {
                return "unknown";
            }
            return workspaceName;
        } catch (Exception e) {
            log.debug("Failed to get workspace name from context", e);
            return "unknown";
        }
    }

    /**
     * Extracts batch size from method arguments using reflection.
     * Looks for common patterns like traces(), spans(), ids(), scores(), threadIds().
     */
    private int extractBatchSize(Object[] arguments) {
        for (Object arg : arguments) {
            if (arg == null) {
                continue;
            }

            try {
                // Pattern 1: traces() or spans() returning List
                if (hasMethod(arg, "traces")) {
                    Method method = arg.getClass().getMethod("traces");
                    Object result = method.invoke(arg);
                    if (result instanceof List<?> list) {
                        return list.size();
                    }
                }

                if (hasMethod(arg, "spans")) {
                    Method method = arg.getClass().getMethod("spans");
                    Object result = method.invoke(arg);
                    if (result instanceof List<?> list) {
                        return list.size();
                    }
                }

                // Pattern 2: ids() returning Set or List
                if (hasMethod(arg, "ids")) {
                    Method method = arg.getClass().getMethod("ids");
                    Object result = method.invoke(arg);
                    if (result instanceof Collection<?> collection) {
                        return collection.size();
                    }
                }

                // Pattern 3: scores() returning List
                if (hasMethod(arg, "scores")) {
                    Method method = arg.getClass().getMethod("scores");
                    Object result = method.invoke(arg);
                    if (result instanceof List<?> list) {
                        return list.size();
                    }
                }

                // Pattern 4: threadIds() returning List
                if (hasMethod(arg, "threadIds")) {
                    Method method = arg.getClass().getMethod("threadIds");
                    Object result = method.invoke(arg);
                    if (result instanceof List<?> list) {
                        return list.size();
                    }
                }

            } catch (Exception e) {
                log.debug("Could not extract batch size from '{}'", arg.getClass().getSimpleName(), e);
            }
        }

        return 0;
    }

    private boolean hasMethod(Object obj, String methodName) {
        try {
            obj.getClass().getMethod(methodName);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Calculates payload size by serializing the argument to JSON.
     */
    private long calculatePayloadSize(Object[] arguments) {
        for (Object arg : arguments) {
            if (arg == null) {
                continue;
            }

            try {
                byte[] jsonBytes = objectMapper.writeValueAsBytes(arg);
                return jsonBytes.length;
            } catch (Exception e) {
                log.debug("Could not calculate payload size for '{}'", arg.getClass().getSimpleName(), e);
            }
        }

        return 0;
    }

    /**
     * Detects attachments in the payload by searching for attachment patterns.
     * Returns both the count and whether attachments are present.
     */
    private AttachmentInfo detectAttachments(Object[] arguments) {
        int attachmentCount = 0;

        for (Object arg : arguments) {
            if (arg == null) {
                continue;
            }

            try {
                // Serialize to JSON and search for attachment patterns
                String json = objectMapper.writeValueAsString(arg);
                attachmentCount += countAttachmentMarkers(json);
            } catch (Exception e) {
                log.debug("Could not detect attachments in '{}'", arg.getClass().getSimpleName(), e);
            }
        }

        boolean hasAttachments = attachmentCount > 0;
        return new AttachmentInfo(hasAttachments, attachmentCount);
    }

    /**
     * Counts attachment markers in a JSON string.
     * Looks for two patterns:
     * 1. Stripped attachment references: [filename.ext]
     * 2. Base64 data URIs: data:image/png;base64,...
     */
    private int countAttachmentMarkers(String content) {
        int count = 0;

        // Pattern 1: Stripped attachment references [filename.ext]
        Matcher strippedMatcher = STRIPPED_ATTACHMENT_PATTERN.matcher(content);
        while (strippedMatcher.find()) {
            count++;
        }

        // Pattern 2: Base64 data URIs
        Matcher dataUriMatcher = DATA_URI_PATTERN.matcher(content);
        while (dataUriMatcher.find()) {
            count++;
        }

        return count;
    }

    private record AttachmentInfo(boolean hasAttachments, int count) {
    }
}

