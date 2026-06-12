package com.comet.opik.domain.mcpoauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("McpOAuthTokens")
class McpOAuthTokensTest {

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("maskToken returns empty for null/empty input")
    void maskToken_nullOrEmpty_returnsEmpty(String token) {
        assertThat(McpOAuthTokens.maskToken(token)).isEmpty();
    }

    @Test
    @DisplayName("maskToken returns a non-reversible hash prefix, never a token fragment")
    void maskToken_fullToken_returnsHashPrefix() {
        String token = McpOAuthTokens.generateAccessToken();

        String masked = McpOAuthTokens.maskToken(token);

        assertThat(masked)
                .isEqualTo("sha256:" + McpOAuthTokens.hash(token).substring(0, 12))
                .doesNotContain(token);
    }

    @Test
    @DisplayName("maskToken masks short non-Opik values too")
    void maskToken_shortValue_isMasked() {
        String shortSecret = "short-secret";

        assertThat(McpOAuthTokens.maskToken(shortSecret))
                .startsWith("sha256:")
                .doesNotContain(shortSecret);
    }
}
