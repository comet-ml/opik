package com.comet.opik.infrastructure.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    String GENERAL_EVENTS = "general_events"; // User limit
    String WORKSPACE_EVENTS = "workspace_events"; // Workspace limit
    String SINGLE_TRACING_OPS = "singleTracingOps"; // Single tracing operations limit

    /**
     * Define the custom bucket name for the rate limit.
     *
     * @return the bucket names
     * <br>
     * To define custom bucket names, use the following format:
     * <br>
     * - for simple bucket names: "bucketName"
     * - for bucket names with placeholders: "bucketName:{placeholder}"
     * <br>
     * The placeholders are replaced with the actual values from the request context. Currently, the following placeholders are supported:
     * <br>
     * - {workspaceId}
     * - {apiKey}
     * */
    String[] value() default {};

    boolean shouldAffectWorkspaceLimit() default true;
    boolean shouldAffectUserGeneralLimit() default true;
}
