package com.comet.opik.infrastructure.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    String GENERAL_EVENTS = "general_events";

    String value() default GENERAL_EVENTS; // bucket capacity
}
