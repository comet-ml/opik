package com.comet.opik.infrastructure.auth;

import com.google.inject.BindingAnnotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Qualifies the dedicated Reactor scheduler for the auth hop's outbound calls, so it is not confused
 * with any other {@code Scheduler} binding.
 */
@BindingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@interface AuthHttpScheduler {
}
