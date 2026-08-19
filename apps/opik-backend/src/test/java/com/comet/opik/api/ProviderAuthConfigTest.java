package com.comet.opik.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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

    // any single field set means "not the clear convention" — the update path must
    // route these through validationErrors(), never silently clear the stored recipe
    static Stream<Arguments> partialConfigs() {
        return Stream.of(
                arguments("sendAs", ProviderAuthConfig.builder().sendAs(ProviderAuthConfig.SendAs.BASIC).build()),
                arguments("credentials", ProviderAuthConfig.builder()
                        .credentials(List.of(credential("client_id", "opik", false)))
                        .build()),
                arguments("tokenField", ProviderAuthConfig.builder().tokenField("access_token").build()),
                arguments("expiresField", ProviderAuthConfig.builder().expiresField("expires_in").build()),
                arguments("fallbackTtlSeconds", ProviderAuthConfig.builder().fallbackTtlSeconds(60L).build()));
    }

    @ParameterizedTest(name = "only {0} set")
    @MethodSource("partialConfigs")
    void partialConfigsAreNotEmptySoTheyValidateInsteadOfClearing(String field, ProviderAuthConfig partial) {
        assertThat(partial.isEmpty()).isFalse();
        assertThat(partial.validationErrors()).isNotEmpty();
    }

    @Test
    void validationErrorsRequireTokenUrlAndCredentials() {
        assertThat(AUTH_CONFIG.validationErrors()).isEmpty();

        assertThat(AUTH_CONFIG.toBuilder().tokenUrl("not a uri").build().validationErrors())
                .containsExactly("auth_config.token_url must be a valid absolute URI");
        assertThat(AUTH_CONFIG.toBuilder().credentials(List.of()).build().validationErrors())
                .containsExactly("auth_config.credentials must not be empty");
        assertThat(ProviderAuthConfig.builder().build().validationErrors()).hasSize(2);
    }

    @Test
    void toStringNeverExposesCredentialValues() {
        assertThat(AUTH_CONFIG.toString()).doesNotContain("s3cr3t", "opik-prod", "client_credentials");
        assertThat(AUTH_CONFIG.credentials().getLast().toString()).doesNotContain("s3cr3t");
    }
}
