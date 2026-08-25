package com.comet.opik.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderAuthConfigTest {

    private static final ProviderAuthConfig AUTH_CONFIG = ProviderAuthConfig.builder()
            .tokenUrl("https://auth.example.com/oauth/token")
            .sendAs(ProviderAuthConfig.SendAs.BASIC)
            .credentials(List.of(
                    credential("grant_type", "client_credentials", false),
                    credential("client_id", "opik-prod", false),
                    credential("client_secret", "s3cr3t", true)))
            .tokenField("access_token")
            .expiresField("expires_in")
            .build();

    private static ProviderAuthConfig.Credential credential(String key, String value, boolean secret) {
        return ProviderAuthConfig.Credential.builder().key(key).value(value).secret(secret).build();
    }

    @Test
    void maskReplacesOnlySecretValues() {
        var masked = AUTH_CONFIG.mask();

        assertThat(masked.credentials()).containsExactly(
                credential("grant_type", "client_credentials", false),
                credential("client_id", "opik-prod", false),
                credential("client_secret", ProviderAuthConfig.SECRET_SENTINEL, true));
        assertThat(masked.tokenUrl()).isEqualTo(AUTH_CONFIG.tokenUrl());
    }

    @Test
    void emptyObjectIsTheClearConvention() {
        assertThat(ProviderAuthConfig.builder().build().isEmpty()).isTrue();
        assertThat(AUTH_CONFIG.isEmpty()).isFalse();
        assertThat(ProviderAuthConfig.builder().tokenUrl("https://auth.example.com").build().isEmpty()).isFalse();
    }

    @Test
    void toStringNeverExposesCredentialValues() {
        assertThat(AUTH_CONFIG.toString()).doesNotContain("s3cr3t", "opik-prod", "client_credentials");
        // Lombok builders don't inherit the record's toString() override; the builder skeleton does
        assertThat(credential("client_secret", "s3cr3t", true).toBuilder().toString()).doesNotContain("s3cr3t");
        assertThat(AUTH_CONFIG.credentials().getLast().toString()).doesNotContain("s3cr3t");
    }
}
