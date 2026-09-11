package com.comet.opik.api.validation;

import com.comet.opik.api.TraceThreadBatchIdentifier;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TraceThreadBatchIdentifierValidatorTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private Validator validator;

    @BeforeEach
    void setUp() {
        var validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @Test
    void validateWhenValidSingleThreadIdWithProjectName() {
        // Given
        var identifier = TraceThreadBatchIdentifier.builder()
                .projectName("test-project")
                .threadId("thread-123")
                .build();

        // When
        Set<ConstraintViolation<TraceThreadBatchIdentifier>> violations = validator.validate(identifier);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    void validateWhenValidSingleThreadIdWithProjectId() {
        // Given
        var identifier = TraceThreadBatchIdentifier.builder()
                .projectId(UUID.randomUUID())
                .threadId("thread-123")
                .build();

        // When
        Set<ConstraintViolation<TraceThreadBatchIdentifier>> violations = validator.validate(identifier);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    void validateWhenValidBatchThreadIdsWithProjectName() {
        // Given
        var identifier = TraceThreadBatchIdentifier.builder()
                .projectName("test-project")
                .threadIds(Set.of("thread-1", "thread-2", "thread-3"))
                .build();

        // When
        Set<ConstraintViolation<TraceThreadBatchIdentifier>> violations = validator.validate(identifier);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    void validateWhenValidBatchThreadIdsWithProjectId() {
        // Given
        var identifier = TraceThreadBatchIdentifier.builder()
                .projectId(UUID.randomUUID())
                .threadIds(Set.of("thread-1", "thread-2", "thread-3"))
                .build();

        // When
        Set<ConstraintViolation<TraceThreadBatchIdentifier>> violations = validator.validate(identifier);

        // Then
        assertThat(violations).isEmpty();
    }

    @Test
    void validateWhenNoProjectIdentifierThrowsValidationError() {
        // Given
        var identifier = TraceThreadBatchIdentifier.builder()
                .threadId("thread-123")
                .build();

        // When
        Set<ConstraintViolation<TraceThreadBatchIdentifier>> violations = validator.validate(identifier);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Either 'projectName' or 'projectId' must be provided.");
    }

    @Test
    void validateWhenNoThreadIdentifierThrowsValidationError() {
        // Given
        var identifier = TraceThreadBatchIdentifier.builder()
                .projectName("test-project")
                .build();

        // When
        Set<ConstraintViolation<TraceThreadBatchIdentifier>> violations = validator.validate(identifier);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Either 'threadId' or 'threadIds' must be provided.");
    }

    @Test
    void validateWhenBothThreadIdAndThreadIdsProvidedThrowsValidationError() {
        // Given
        var identifier = TraceThreadBatchIdentifier.builder()
                .projectName("test-project")
                .threadId("thread-123")
                .threadIds(Set.of("thread-1", "thread-2"))
                .build();

        // When
        Set<ConstraintViolation<TraceThreadBatchIdentifier>> violations = validator.validate(identifier);

        // Then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo(
                        "Cannot provide both 'threadId' and 'threadIds'. Use 'threadId' for single operations or 'threadIds' for batch operations.");
    }

    @Test
    void validateWhenMultipleValidationErrorsOccur() {
        // Given - No project identifier and no thread identifier
        var identifier = TraceThreadBatchIdentifier.builder().build();

        // When
        Set<ConstraintViolation<TraceThreadBatchIdentifier>> violations = validator.validate(identifier);

        // Then
        assertThat(violations).hasSize(2);
        var messages = violations.stream()
                .map(ConstraintViolation::getMessage)
                .toList();
        assertThat(messages).containsExactlyInAnyOrder(
                "Either 'projectName' or 'projectId' must be provided.",
                "Either 'threadId' or 'threadIds' must be provided.");
    }

    @ParameterizedTest
    @MethodSource("invalidProjectIdentifierCases")
    void validateWhenInvalidProjectIdentifier(String projectName, UUID projectId, String expectedMessage) {
        // Given
        var identifier = TraceThreadBatchIdentifier.builder()
                .projectName(projectName)
                .projectId(projectId)
                .threadId("thread-123")
                .build();

        // When
        Set<ConstraintViolation<TraceThreadBatchIdentifier>> violations = validator.validate(identifier);

        // Then
        if (expectedMessage != null) {
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage()).isEqualTo(expectedMessage);
        } else {
            assertThat(violations).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidThreadIdentifierCases")
    void validateWhenInvalidThreadIdentifier(String threadId, Set<String> threadIds, String expectedMessage) {
        // Given
        var identifier = TraceThreadBatchIdentifier.builder()
                .projectName("test-project")
                .threadId(threadId)
                .threadIds(threadIds)
                .build();

        // When
        Set<ConstraintViolation<TraceThreadBatchIdentifier>> violations = validator.validate(identifier);

        // Then
        if (expectedMessage != null) {
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage()).isEqualTo(expectedMessage);
        } else {
            assertThat(violations).isEmpty();
        }
    }

    private static Stream<Arguments> invalidProjectIdentifierCases() {
        return Stream.of(
                // Valid cases
                Arguments.of("valid-project", null, null),
                Arguments.of(null, UUID.randomUUID(), null),
                Arguments.of("valid-project", UUID.randomUUID(), null),

                // Invalid cases
                Arguments.of(null, null, "Either 'projectName' or 'projectId' must be provided."),
                Arguments.of("", null, "Either 'projectName' or 'projectId' must be provided."),
                Arguments.of("   ", null, "Either 'projectName' or 'projectId' must be provided."));
    }

    private static Stream<Arguments> invalidThreadIdentifierCases() {
        return Stream.of(
                // Valid cases
                Arguments.of("valid-thread", null, null),
                Arguments.of(null, Set.of("thread-1", "thread-2"), null),

                // Invalid cases
                Arguments.of(null, null, "Either 'threadId' or 'threadIds' must be provided."),
                Arguments.of("", null, "Either 'threadId' or 'threadIds' must be provided."),
                Arguments.of("   ", null, "Either 'threadId' or 'threadIds' must be provided."),
                Arguments.of(null, Set.of(), "size must be between 1 and 1000"),
                Arguments.of("thread-1", Set.of("thread-2"),
                        "Cannot provide both 'threadId' and 'threadIds'. Use 'threadId' for single operations or 'threadIds' for batch operations."));
    }
}
