package com.comet.opik.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates an automation rule's variable mappings against the supported path grammar — see
 * {@link com.comet.opik.utils.VariablePathUtils}. Rejects recursive descent ({@code ..}) and filter
 * predicates ({@code [?(}), whose evaluation cost grows with the size of the scored trace on a
 * scheduler shared by every workspace on the pod. Indexed access and single-level wildcards stay
 * supported.
 */
@Documented
@Constraint(validatedBy = SupportedVariablePathsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SupportedVariablePaths {
    String message() default "contains an unsupported path construct";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
