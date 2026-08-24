package com.comet.opik.api.validation;

import com.comet.opik.api.ProviderAuthConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ProviderAuthConfigValidatorTest {

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
    void validationErrorsRequireTokenUrlAndCredentials() {
        assertThat(ProviderAuthConfigValidator.validationErrors(AUTH_CONFIG)).isEmpty();

        assertThat(ProviderAuthConfigValidator.validationErrors(AUTH_CONFIG.toBuilder().tokenUrl("not a uri").build()))
                .containsExactly("auth_config.token_url must be a valid absolute URI");
        assertThat(ProviderAuthConfigValidator
                .validationErrors(AUTH_CONFIG.toBuilder().tokenUrl("ftp://auth.example.com/token").build()))
                .containsExactly("auth_config.token_url must use http or https");
        assertThat(ProviderAuthConfigValidator.validationErrors(AUTH_CONFIG.toBuilder().credentials(List.of()).build()))
                .containsExactly("auth_config.credentials must not be empty");
        assertThat(ProviderAuthConfigValidator.validationErrors(ProviderAuthConfig.builder().build())).hasSize(2);
    }

    @Test
    void duplicateCredentialKeysAreRejected() {
        var config = AUTH_CONFIG.toBuilder()
                .credentials(List.of(
                        credential("client_id", "first", false),
                        credential("client_id", "second", false),
                        credential("client_secret", "s3cr3t", true)))
                .build();

        assertThat(ProviderAuthConfigValidator.validationErrors(config))
                .containsExactly("auth_config.credentials contains duplicate key 'client_id'");
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
        assertThat(ProviderAuthConfigValidator.validationErrors(partial)).isNotEmpty();
    }
}
