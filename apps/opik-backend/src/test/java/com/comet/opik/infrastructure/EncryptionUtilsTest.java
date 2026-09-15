package com.comet.opik.infrastructure;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionUtilsTest {

    @BeforeAll
    static void setUpAll() {
        var config = new OpikConfiguration();
        config.getEncryption().setKey("0123456789abcdef");
        EncryptionUtils.setConfig(config);
    }

    @Test
    void gcmRoundTrip() {
        var plaintext = "{\"credentials\":[{\"key\":\"client_secret\",\"value\":\"s3cr3t\"}]}";

        var encrypted = EncryptionUtils.encryptGcm(plaintext);

        assertThat(encrypted).doesNotContain("s3cr3t");
        assertThat(EncryptionUtils.decryptGcm(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void gcmUsesARandomIvPerEncryption() {
        var plaintext = "same plaintext";

        assertThat(EncryptionUtils.encryptGcm(plaintext)).isNotEqualTo(EncryptionUtils.encryptGcm(plaintext));
    }

    @Test
    void gcmRejectsTamperedCiphertext() {
        byte[] payload = Base64.getDecoder().decode(EncryptionUtils.encryptGcm("payload"));
        payload[payload.length - 1] ^= 1;
        var tampered = Base64.getEncoder().encodeToString(payload);

        assertThatThrownBy(() -> EncryptionUtils.decryptGcm(tampered)).isInstanceOf(SecurityException.class);
    }

    @Test
    void gcmRejectsLegacyCiphertext() {
        var legacy = EncryptionUtils.encrypt("payload");

        assertThatThrownBy(() -> EncryptionUtils.decryptGcm(legacy)).isInstanceOf(SecurityException.class);
    }
}
