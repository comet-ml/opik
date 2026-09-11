package com.comet.opik.api;

import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedAuthConfigTest {

    @BeforeAll
    static void setUpAll() {
        var config = new OpikConfiguration();
        config.getEncryption().setKey("0123456789abcdef");
        EncryptionUtils.setConfig(config);
    }

    private ProviderAuthConfig recipe() {
        return ProviderAuthConfig.builder()
                .tokenUrl("https://auth.example.com/oauth2/token")
                .credentials(List.of(
                        ProviderAuthConfig.Credential.builder()
                                .key("client_secret").value("s3cr3t").secret(true).build()))
                .build();
    }

    @Test
    @DisplayName("a DB-loaded config decrypts only when the value is touched")
    void decryptionIsLazy() {
        var garbage = EncryptedAuthConfig.fromCiphertext("not-even-base64!");

        assertThatCode(() -> {
            garbage.toString();
            garbage.hashSource();
            garbage.ciphertextOrEncrypt();
        }).doesNotThrowAnyException();

        assertThatThrownBy(garbage::value).isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("an untouched config persists its original ciphertext, byte for byte")
    void untouchedConfigPreservesCiphertext() {
        var ciphertext = EncryptionUtils.encryptGcm(JsonUtils.writeValueAsString(recipe()));
        var loaded = EncryptedAuthConfig.fromCiphertext(ciphertext);

        assertThat(loaded.ciphertextOrEncrypt()).isEqualTo(ciphertext);
        assertThat(loaded.hashSource()).isEqualTo(ciphertext);

        // still the same ciphertext after a read: value() memoizes, it never re-encrypts
        assertThat(loaded.value()).isEqualTo(recipe());
        assertThat(loaded.ciphertextOrEncrypt()).isEqualTo(ciphertext);
    }

    @Test
    @DisplayName("DB-loaded and request-built forms of the same recipe are equal")
    void equalityBridgesBothForms() {
        var ciphertext = EncryptionUtils.encryptGcm(JsonUtils.writeValueAsString(recipe()));

        assertThat(EncryptedAuthConfig.fromCiphertext(ciphertext)).isEqualTo(EncryptedAuthConfig.of(recipe()));
    }
}
