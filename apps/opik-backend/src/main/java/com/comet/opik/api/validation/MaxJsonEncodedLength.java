package com.comet.opik.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated string, written as a JSON string with every non-ASCII character escaped, must be at most
 * {@code value} characters long, quotes included. That is Python's {@code json.dumps} default, which is how
 * opik-inference measures a decision's state.
 *
 * <p>
 * {@code null} elements are considered valid.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxJsonEncodedLengthValidator.class)
public @interface MaxJsonEncodedLength {

    String message() default "must be at most {value} characters once JSON-encoded, where a quote, backslash or line "
            + "break counts as 2 and a non-ASCII character as 6";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * @return the maximum length of the encoded string, quotes included
     */
    int value();
}
