package com.comet.opik.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that the annotated string is a syntactically valid, absolute URI (i.e. has a scheme).
 * Unlike {@link HttpUrl} it does not restrict the scheme, so OAuth redirect URIs using native-app
 * custom schemes or loopback http are accepted.
 */
@Documented
@Constraint(validatedBy = AbsoluteUriValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AbsoluteUri {
    String message() default "must be an absolute URI";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
