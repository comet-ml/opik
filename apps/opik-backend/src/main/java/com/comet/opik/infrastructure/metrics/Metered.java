package com.comet.opik.infrastructure.metrics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods for automatic metrics collection via OpenTelemetry.
 * 
 * <p>When applied to a method, the {@link MetricsInterceptor} will automatically record:
 * <ul>
 *   <li>Operation count (counter)</li>
 *   <li>Operation duration (histogram)</li>
 *   <li>Batch size (histogram, if enabled)</li>
 *   <li>Error count (counter)</li>
 *   <li>Payload size (histogram)</li>
 *   <li>Attachment count (histogram, if enabled)</li>
 * </ul>
 * 
 * <p>All metrics include labels for operation, entity_type, and workspace_name.
 * 
 * <p>Example usage:
 * <pre>{@code
 * @Metered(operation = "trace.batch.create", entity = "trace", recordBatchSize = true, recordAttachments = true)
 * public Response createTraces(TraceBatch traces) {
 *     // method implementation
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Metered {

    /**
     * The operation name for the metric (e.g., "trace.create", "trace.batch.create").
     * This will be used as the "operation" label in all metrics.
     * 
     * @return the operation name
     */
    String operation();

    /**
     * The entity type (e.g., "trace", "span").
     * This will be used as the "entity_type" label in all metrics.
     * 
     * @return the entity type
     */
    String entity();

    /**
     * Whether to record batch size for batch operations.
     * When enabled, the interceptor will attempt to extract batch size from method arguments
     * using common patterns (e.g., traces(), spans(), ids(), scores()).
     * 
     * @return true if batch size should be recorded, false otherwise
     */
    boolean recordBatchSize() default false;

    /**
     * Whether to detect and record attachment counts (images/files) in the payload.
     * When enabled, the interceptor will scan for attachment patterns in the request payload.
     * 
     * @return true if attachments should be detected and counted, false otherwise
     */
    boolean recordAttachments() default false;
}

