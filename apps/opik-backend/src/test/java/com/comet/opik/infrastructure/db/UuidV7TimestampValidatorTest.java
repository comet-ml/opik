package com.comet.opik.infrastructure.db;

import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.api.error.InvalidUUIDException.Reason;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.UuidValidationConfig;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("UuidV7TimestampValidator modes")
class UuidV7TimestampValidatorTest {

    private static final String RESOURCE = "trace";
    private static final String WORKSPACE_ID = "ws-123";
    private static final String BYPASS_WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String OTHER_BYPASS_WORKSPACE_ID = UUID.randomUUID().toString();
    private static final Duration WINDOW = Duration.hours(24);
    private static final Duration BYPASS_WINDOW = Duration.days(30);
    /**
     * Offsets either side of BYPASS_WINDOW, both outside WINDOW.
     */
    private static final int WITHIN_BYPASS_WINDOW_DAYS = 10;
    private static final int BEYOND_BYPASS_WINDOW_DAYS = 40;
    /**
     * The bounds the validator enforces on the allow-list variable.
     */
    private static final String ENTRY_DELIMITER = ",";
    private static final int MAX_WORKSPACE_ID_LENGTH = 64;
    private static final int MAX_BYPASS_WORKSPACES = 100;

    private final IdGenerator idGenerator = TestIdGeneratorFactory.create();

    private UUID idAt(Instant instant) {
        return idGenerator.getTimeOrderedEpoch(instant.toEpochMilli());
    }

    private UUID inWindowId() {
        return idAt(Instant.now());
    }

    private UUID tooFarFutureId() {
        return idAt(Instant.now().plus(48, ChronoUnit.HOURS));
    }

    private UUID tooOldId() {
        return idAt(Instant.now().minus(48, ChronoUnit.HOURS));
    }

    private UUID idDaysFromNow(int days) {
        return idAt(Instant.now().plus(days, ChronoUnit.DAYS));
    }

    private UuidV7TimestampValidator validator(boolean enabled, boolean auditOnly) {
        return TestUuidV7TimestampValidatorFactory
                .create(config().enabled(enabled).auditOnly(auditOnly).build());
    }

    /**
     * Reject mode over the default windows, for callers to override only what their case is about.
     */
    private UuidValidationConfig.UuidValidationConfigBuilder config() {
        return UuidValidationConfig.builder()
                .enabled(true)
                .auditOnly(false)
                .window(WINDOW)
                .bypassWindow(BYPASS_WINDOW);
    }

    @Nested
    @DisplayName("disabled (enabled=false)")
    class Disabled {

        private final UuidV7TimestampValidator validator = validator(false, false);

        @Test
        @DisplayName("accepts out-of-window ids on both paths")
        void acceptsEverything() {
            assertThatCode(() -> validator.validate(tooFarFutureId(), RESOURCE, WORKSPACE_ID))
                    .doesNotThrowAnyException();
            assertThatCode(() -> validator.validate(tooOldId(), RESOURCE, WORKSPACE_ID)).doesNotThrowAnyException();
            assertThatCode(() -> validator.validateNotInFuture(tooFarFutureId(), RESOURCE, WORKSPACE_ID))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("reject (enabled=true, auditOnly=false)")
    class Reject {

        private final UuidV7TimestampValidator validator = validator(true, false);

        @Test
        @DisplayName("validate: accepts in-window, rejects too-old and too-far-future")
        void validate() {
            assertThatCode(() -> validator.validate(inWindowId(), RESOURCE, WORKSPACE_ID)).doesNotThrowAnyException();
            assertThatThrownBy(() -> validator.validate(tooOldId(), RESOURCE, WORKSPACE_ID))
                    .isInstanceOf(InvalidUUIDException.class)
                    .extracting(e -> ((InvalidUUIDException) e).getReason()).isEqualTo(Reason.TOO_OLD);
            assertThatThrownBy(() -> validator.validate(tooFarFutureId(), RESOURCE, WORKSPACE_ID))
                    .isInstanceOf(InvalidUUIDException.class)
                    .extracting(e -> ((InvalidUUIDException) e).getReason()).isEqualTo(Reason.TOO_FAR_FUTURE);
        }

        @Test
        @DisplayName("validateNotInFuture: accepts too-old, rejects only too-far-future")
        void validateNotInFuture() {
            assertThatCode(() -> validator.validateNotInFuture(tooOldId(), RESOURCE, WORKSPACE_ID))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> validator.validateNotInFuture(tooFarFutureId(), RESOURCE, WORKSPACE_ID))
                    .isInstanceOf(InvalidUUIDException.class)
                    .extracting(e -> ((InvalidUUIDException) e).getReason()).isEqualTo(Reason.TOO_FAR_FUTURE);
        }
    }

    @Nested
    @DisplayName("audit (enabled=true, auditOnly=true)")
    class Audit {

        private final UuidV7TimestampValidator validator = validator(true, true);

        @Test
        @DisplayName("never rejects, even for out-of-window ids (shadow / log-only)")
        void neverRejects() {
            assertThatCode(() -> validator.validate(inWindowId(), RESOURCE, WORKSPACE_ID)).doesNotThrowAnyException();
            assertThatCode(() -> validator.validate(tooOldId(), RESOURCE, WORKSPACE_ID)).doesNotThrowAnyException();
            assertThatCode(() -> validator.validate(tooFarFutureId(), RESOURCE, WORKSPACE_ID))
                    .doesNotThrowAnyException();
            assertThatCode(() -> validator.validateNotInFuture(tooFarFutureId(), RESOURCE, WORKSPACE_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tolerates a blank workspace id (falls back to unknown in the metric)")
        void toleratesBlankWorkspace() {
            assertThat(validator).isNotNull();
            assertThatCode(() -> validator.validate(tooFarFutureId(), RESOURCE, "")).doesNotThrowAnyException();
        }
    }

    /**
     * Workspace-scoped bypass (OPIK-7794): an allow-listed workspace is validated against the wider
     * {@code bypassWindow} instead of {@code window}. The bypass stays bounded, and everyone else is
     * untouched.
     */
    @Nested
    class Bypass {

        private final UuidV7TimestampValidator validator = TestUuidV7TimestampValidatorFactory
                .create(config().build(), BYPASS_WORKSPACE_ID);

        static Stream<Integer> acceptsIdsWithinTheBypassWindow() {
            return Stream.of(-WITHIN_BYPASS_WINDOW_DAYS, WITHIN_BYPASS_WINDOW_DAYS);
        }

        @ParameterizedTest
        @MethodSource
        void acceptsIdsWithinTheBypassWindow(int daysFromNow) {
            assertThatCode(() -> validator.validate(idDaysFromNow(daysFromNow), RESOURCE, BYPASS_WORKSPACE_ID))
                    .doesNotThrowAnyException();
            assertThatCode(
                    () -> validator.validateNotInFuture(idDaysFromNow(daysFromNow), RESOURCE, BYPASS_WORKSPACE_ID))
                    .doesNotThrowAnyException();
        }

        static Stream<Arguments> rejectsIdsBeyondTheBypassWindow() {
            return Stream.of(
                    arguments(-BEYOND_BYPASS_WINDOW_DAYS, Reason.TOO_OLD),
                    arguments(BEYOND_BYPASS_WINDOW_DAYS, Reason.TOO_FAR_FUTURE));
        }

        @ParameterizedTest
        @MethodSource
        void rejectsIdsBeyondTheBypassWindow(int daysFromNow, Reason expectedReason) {
            assertThatThrownBy(() -> validator.validate(idDaysFromNow(daysFromNow), RESOURCE, BYPASS_WORKSPACE_ID))
                    .isInstanceOf(InvalidUUIDException.class)
                    .extracting(throwable -> ((InvalidUUIDException) throwable).getReason())
                    .isEqualTo(expectedReason);
        }

        @Test
        void rejectsFutureDatedReferencedIdsBeyondTheBypassWindow() {
            assertThatThrownBy(
                    () -> validator.validateNotInFuture(idDaysFromNow(BEYOND_BYPASS_WINDOW_DAYS), RESOURCE,
                            BYPASS_WORKSPACE_ID))
                    .isInstanceOf(InvalidUUIDException.class);
        }

        @Test
        void reportsTheBypassWindowWhenRejectingAnAllowListedWorkspace() {
            assertThatThrownBy(
                    () -> validator.validate(idDaysFromNow(BEYOND_BYPASS_WINDOW_DAYS), RESOURCE, BYPASS_WORKSPACE_ID))
                    .hasMessageContaining(BYPASS_WINDOW.toJavaDuration().toString())
                    .hasMessageNotContaining(WINDOW.toJavaDuration().toString());
        }

        @Test
        void leavesEveryOtherWorkspaceOnTheDefaultWindow() {
            assertThatCode(() -> validator.validate(inWindowId(), RESOURCE, WORKSPACE_ID)).doesNotThrowAnyException();
            assertThatThrownBy(() -> validator.validate(tooFarFutureId(), RESOURCE, WORKSPACE_ID))
                    .isInstanceOf(InvalidUUIDException.class)
                    .hasMessageContaining(WINDOW.toJavaDuration().toString());
            assertThatThrownBy(() -> validator.validate(tooOldId(), RESOURCE, WORKSPACE_ID))
                    .isInstanceOf(InvalidUUIDException.class);
        }

        /**
         * No case-folding, no prefix or suffix match, no untrimmed match: normalizing could only ever
         * broaden the allow-list, which is the unsafe direction. A missing workspace never matches either.
         */
        static Stream<String> neverBypassesAnUnknownWorkspace() {
            return Stream.of(
                    BYPASS_WORKSPACE_ID.toUpperCase(Locale.ROOT),
                    BYPASS_WORKSPACE_ID.substring(0, BYPASS_WORKSPACE_ID.length() - 1),
                    "%s0".formatted(BYPASS_WORKSPACE_ID),
                    " %s".formatted(BYPASS_WORKSPACE_ID),
                    OTHER_BYPASS_WORKSPACE_ID,
                    "",
                    null);
        }

        @ParameterizedTest
        @MethodSource
        void neverBypassesAnUnknownWorkspace(String workspaceId) {
            assertThatThrownBy(
                    () -> validator.validate(idDaysFromNow(WITHIN_BYPASS_WINDOW_DAYS), RESOURCE, workspaceId))
                    .isInstanceOf(InvalidUUIDException.class);
        }

        @Test
        void neverNarrowsTheDefaultWindowWhenTheBypassWindowIsSmaller() {
            var narrow = TestUuidV7TimestampValidatorFactory
                    .create(config().bypassWindow(Duration.hours(12)).build(), BYPASS_WORKSPACE_ID);

            assertThatCode(() -> narrow.validate(inWindowId(), RESOURCE, BYPASS_WORKSPACE_ID))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> narrow.validate(tooFarFutureId(), RESOURCE, BYPASS_WORKSPACE_ID))
                    .isInstanceOf(InvalidUUIDException.class)
                    .hasMessageContaining(WINDOW.toJavaDuration().toString());
        }

        /**
         * One offset inside the bypass window and one beyond it, for the cases that must behave the same
         * either way.
         */
        static Stream<Integer> bypassWindowDays() {
            return Stream.of(WITHIN_BYPASS_WINDOW_DAYS, BEYOND_BYPASS_WINDOW_DAYS);
        }

        @ParameterizedTest
        @MethodSource("bypassWindowDays")
        void neverRejectsAnAllowListedWorkspaceInAuditMode(int daysFromNow) {
            var audit = TestUuidV7TimestampValidatorFactory
                    .create(config().auditOnly(true).build(), BYPASS_WORKSPACE_ID);

            assertThatCode(() -> audit.validate(idDaysFromNow(daysFromNow), RESOURCE, BYPASS_WORKSPACE_ID))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @MethodSource("bypassWindowDays")
        void keepsTheDisabledKillSwitchWinningOverTheBypass(int daysFromNow) {
            var disabled = TestUuidV7TimestampValidatorFactory
                    .create(config().enabled(false).build(), BYPASS_WORKSPACE_ID);

            assertThatCode(() -> disabled.validate(idDaysFromNow(daysFromNow), RESOURCE, WORKSPACE_ID))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Parsing of the bypass allow-list environment variable. Every case is asserted through the resulting
     * behavior, since granting a bypass that was not configured is the only failure mode that matters:
     * parsing must never throw, and must only ever drop entries.
     */
    @Nested
    class BypassAllowList {

        /**
         * An unset variable, and values carrying no usable entry at all.
         */
        static Stream<String> allowListsNothing() {
            return Stream.of(null, "", "  ", ENTRY_DELIMITER);
        }

        @ParameterizedTest
        @MethodSource
        void allowListsNothing(String rawBypassWorkspaces) {
            assertDoesNotBypass(rawBypassWorkspaces, BYPASS_WORKSPACE_ID);
        }

        static Stream<String> allowListsEveryValidEntry() {
            return Stream.of(
                    BYPASS_WORKSPACE_ID,
                    "  %s  ".formatted(BYPASS_WORKSPACE_ID),
                    String.join(ENTRY_DELIMITER, OTHER_BYPASS_WORKSPACE_ID, BYPASS_WORKSPACE_ID),
                    String.join(ENTRY_DELIMITER, BYPASS_WORKSPACE_ID, "", OTHER_BYPASS_WORKSPACE_ID, ""),
                    String.join(ENTRY_DELIMITER, BYPASS_WORKSPACE_ID, " %s".formatted(BYPASS_WORKSPACE_ID)));
        }

        @ParameterizedTest
        @MethodSource
        void allowListsEveryValidEntry(String rawBypassWorkspaces) {
            assertBypasses(rawBypassWorkspaces, BYPASS_WORKSPACE_ID);
        }

        /**
         * Whitespace, a character outside the allowed shape, and an over-long value, none of which is a
         * valid workspace id.
         */
        static Stream<String> dropsAMalformedEntryAndKeepsTheValidOnes() {
            return Stream.of(
                    "a workspace",
                    "%s/child".formatted(BYPASS_WORKSPACE_ID),
                    RandomStringUtils.secure().nextAlphanumeric(MAX_WORKSPACE_ID_LENGTH + 1));
        }

        @ParameterizedTest
        @MethodSource
        void dropsAMalformedEntryAndKeepsTheValidOnes(String malformedWorkspaceId) {
            var rawBypassWorkspaces = String.join(ENTRY_DELIMITER, malformedWorkspaceId, BYPASS_WORKSPACE_ID);

            assertDoesNotBypass(malformedWorkspaceId, malformedWorkspaceId);
            assertDoesNotBypass(rawBypassWorkspaces, malformedWorkspaceId);
            assertBypasses(rawBypassWorkspaces, BYPASS_WORKSPACE_ID);
        }

        /**
         * The placeholder passed in by callers that carry no workspace matches the allowed shape, so only
         * an explicit exclusion stops it from bypassing all of them at once.
         */
        @Test
        void neverAllowListsTheMissingWorkspacePlaceholder() {
            var rawBypassWorkspaces = String.join(ENTRY_DELIMITER, ErrorMetricsResolver.UNKNOWN,
                    BYPASS_WORKSPACE_ID);

            assertDoesNotBypass(ErrorMetricsResolver.UNKNOWN, ErrorMetricsResolver.UNKNOWN);
            assertDoesNotBypass(rawBypassWorkspaces, ErrorMetricsResolver.UNKNOWN);
            assertBypasses(rawBypassWorkspaces, BYPASS_WORKSPACE_ID);
        }

        @Test
        void allowListsAnEntryOfExactlyTheMaximumLength() {
            var maximumLengthWorkspaceId = RandomStringUtils.secure().nextAlphanumeric(MAX_WORKSPACE_ID_LENGTH);

            assertBypasses(maximumLengthWorkspaceId, maximumLengthWorkspaceId);
        }

        @Test
        void dropsEntriesBeyondTheMaximumCount() {
            var withinLimit = IntStream.range(0, MAX_BYPASS_WORKSPACES)
                    .mapToObj(index -> UUID.randomUUID().toString())
                    .toList();
            var rawBypassWorkspaces = String.join(ENTRY_DELIMITER, String.join(ENTRY_DELIMITER, withinLimit),
                    BYPASS_WORKSPACE_ID);

            assertBypasses(rawBypassWorkspaces, withinLimit.getFirst());
            assertBypasses(rawBypassWorkspaces, withinLimit.getLast());
            assertDoesNotBypass(rawBypassWorkspaces, BYPASS_WORKSPACE_ID);
        }

        @Test
        void doesNotLetDuplicatesConsumeTheMaximumCount() {
            var duplicates = IntStream.range(0, MAX_BYPASS_WORKSPACES)
                    .mapToObj(index -> OTHER_BYPASS_WORKSPACE_ID)
                    .collect(Collectors.joining(ENTRY_DELIMITER));
            var rawBypassWorkspaces = String.join(ENTRY_DELIMITER, duplicates, BYPASS_WORKSPACE_ID);

            assertBypasses(rawBypassWorkspaces, BYPASS_WORKSPACE_ID);
        }

        private void assertBypasses(String rawBypassWorkspaces, String workspaceId) {
            assertThatCode(() -> validate(rawBypassWorkspaces, workspaceId)).doesNotThrowAnyException();
        }

        private void assertDoesNotBypass(String rawBypassWorkspaces, String workspaceId) {
            assertThatThrownBy(() -> validate(rawBypassWorkspaces, workspaceId))
                    .isInstanceOf(InvalidUUIDException.class);
        }

        /**
         * Validates an id outside the default window but inside the bypass window, so it is accepted only
         * if {@code workspaceId} made it onto the allow-list.
         */
        private void validate(String rawBypassWorkspaces, String workspaceId) {
            TestUuidV7TimestampValidatorFactory.create(config().build(), rawBypassWorkspaces)
                    .validate(idDaysFromNow(WITHIN_BYPASS_WINDOW_DAYS), RESOURCE, workspaceId);
        }
    }
}
