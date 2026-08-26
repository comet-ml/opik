package com.comet.opik.infrastructure.redaction;

import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Redaction Guard")
class RedactionGuardTest {

    @Test
    @DisplayName("a caller whose responses are masked is refused")
    void aCallerWhoseResponsesAreMaskedIsRefused() {
        assertThatThrownBy(() -> RedactionGuard.rejectUnmaskable(true, "Attachment download"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("the refusal names the response and the permission, so the caller can act on it")
    void theRefusalNamesTheResponseAndThePermission() {
        assertThatThrownBy(() -> RedactionGuard.rejectUnmaskable(true, "Attachment download"))
                .hasMessageContaining("Attachment download")
                .hasMessageContaining("trace_original_data_view");
    }

    @Test
    @DisplayName("a caller who may see originals passes through")
    void aCallerWhoMaySeeOriginalsPassesThrough() {
        // Also the flag-off case: RedactionRequestFilter leaves redactResponse false when the feature is
        // disabled, so an install with the flag off never reaches the refusal.
        assertThatCode(() -> RedactionGuard.rejectUnmaskable(false, "Attachment download"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the refusal is 403, not 401 or 500")
    void theRefusalIsForbidden() {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(ForbiddenException.class,
                () -> RedactionGuard.rejectUnmaskable(true, "Agent Insights free-form SQL"));

        // 403 rather than 401: the caller is authenticated, they simply may not read this content.
        assertThat(thrown.getResponse().getStatus()).isEqualTo(403);
    }
}
