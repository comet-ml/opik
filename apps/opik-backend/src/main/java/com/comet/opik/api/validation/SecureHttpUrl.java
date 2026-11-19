package com.comet.opik.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An https URL, or an http URL to this host ({@code localhost} or a loopback address literal), for a URL a credential
 * is sent to: never in clear over a network. A null or blank value passes.
 */
@Documented
@Constraint(validatedBy = SecureHttpUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SecureHttpUrl {
    String message() default "must be an https URL, or an http URL to localhost";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
