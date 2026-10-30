package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.net.DestinationGuard;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderBaseUrlGuardTest {

    private static final ProviderBaseUrlGuard HTTPS_ONLY = ProviderBaseUrlGuard.builder()
            .mode(DestinationGuard.Mode.STRICT)
            .scheme(DestinationGuard.Scheme.HTTPS_ONLY)
            .build();

    /** A public address literal, so no DNS lookup decides the outcome. */
    @Test
    @DisplayName("with https_only, a provider base URL over http is refused and one over https is not")
    void httpsOnlyRefusesHttpBaseUrls() {
        assertThatThrownBy(() -> HTTPS_ONLY.validate("http://8.8.8.8/v1"))
                .isExactlyInstanceOf(BadRequestException.class)
                .hasMessage(ProviderBaseUrlGuard.REFUSED);
        assertThatCode(() -> HTTPS_ONLY.validate("https://8.8.8.8/v1")).doesNotThrowAnyException();
    }
}
