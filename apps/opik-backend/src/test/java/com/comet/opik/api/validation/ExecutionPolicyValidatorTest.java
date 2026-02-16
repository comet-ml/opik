package com.comet.opik.api.validation;

import com.comet.opik.api.ExecutionPolicy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPolicyValidatorTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        var validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @ParameterizedTest
    @MethodSource("validCases")
    void validateWhenValid(int runsPerItem, int passThreshold) {
        var policy = new ExecutionPolicy(runsPerItem, passThreshold);

        Set<ConstraintViolation<ExecutionPolicy>> violations = validator.validate(policy);

        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidCrossFieldCases")
    void validateWhenPassThresholdExceedsRunsPerItem(int runsPerItem, int passThreshold) {
        var policy = new ExecutionPolicy(runsPerItem, passThreshold);

        Set<ConstraintViolation<ExecutionPolicy>> violations = validator.validate(policy);

        assertThat(violations)
                .anyMatch(v -> v.getMessage().equals("pass_threshold must be less than or equal to runs_per_item"));
    }

    @ParameterizedTest
    @MethodSource("invalidMinCases")
    void validateWhenBelowMin(int runsPerItem, int passThreshold) {
        var policy = new ExecutionPolicy(runsPerItem, passThreshold);

        Set<ConstraintViolation<ExecutionPolicy>> violations = validator.validate(policy);

        assertThat(violations).isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidMaxCases")
    void validateWhenAboveMax(int runsPerItem, int passThreshold) {
        var policy = new ExecutionPolicy(runsPerItem, passThreshold);

        Set<ConstraintViolation<ExecutionPolicy>> violations = validator.validate(policy);

        assertThat(violations).isNotEmpty();
    }

    private static Stream<Arguments> validCases() {
        return Stream.of(
                Arguments.of(1, 1),
                Arguments.of(5, 3),
                Arguments.of(50, 30),
                Arguments.of(100, 100),
                Arguments.of(100, 1),
                Arguments.of(3, 2));
    }

    private static Stream<Arguments> invalidCrossFieldCases() {
        return Stream.of(
                Arguments.of(1, 2),
                Arguments.of(3, 5),
                Arguments.of(5, 10));
    }

    private static Stream<Arguments> invalidMinCases() {
        return Stream.of(
                Arguments.of(0, 1),
                Arguments.of(1, 0),
                Arguments.of(0, 0),
                Arguments.of(-1, 1));
    }

    private static Stream<Arguments> invalidMaxCases() {
        return Stream.of(
                Arguments.of(101, 1),
                Arguments.of(1, 101),
                Arguments.of(101, 101));
    }
}
