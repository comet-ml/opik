package com.comet.opik.infrastructure.db;

import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.api.error.InvalidUUIDException.Reason;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.UuidValidationConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UuidV7TimestampValidator modes")
class UuidV7TimestampValidatorTest {

    private static final String RESOURCE = "trace";
    private static final String WORKSPACE_ID = "ws-123";
    private static final Duration WINDOW = Duration.hours(24);

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

    private UuidV7TimestampValidator validator(boolean enabled, boolean auditOnly) {
        return TestUuidV7TimestampValidatorFactory.create(UuidValidationConfig.builder()
                .enabled(enabled)
                .auditOnly(auditOnly)
                .window(WINDOW)
                .build());
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
}
