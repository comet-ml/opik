package com.comet.opik.infrastructure;

import com.fasterxml.jackson.core.StreamReadConstraints;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JacksonConfig defaults")
class JacksonConfigTest {

    @Test
    @DisplayName("maxStringLength POJO default is Jackson's symbolic default (byte caps come from config.yml)")
    void defaults() {
        JacksonConfig config = new JacksonConfig();

        // maxStringLength POJO default is Jackson's (20MB); config.yml raises it to 100MB at load.
        assertThat(config.getMaxStringLength()).isEqualTo(StreamReadConstraints.DEFAULT_MAX_STRING_LEN);
    }

    @Test
    @DisplayName("maxDocumentLength validator: <= 0 (unlimited), or between maxStringLength and 2GB")
    void maxDocumentLengthValidity() {
        JacksonConfig config = new JacksonConfig();
        assertThat(config.isMaxDocumentLengthValid()).isTrue(); // defaults hold the invariant

        config.setMaxStringLength(104_857_600); // 100MB
        config.setMaxDocumentLength(52_428_800L); // 50MB < 100MB -> a valid max-size string would be rejected
        assertThat(config.isMaxDocumentLengthValid()).isFalse();

        config.setMaxDocumentLength(104_857_600L); // == string cap -> valid
        assertThat(config.isMaxDocumentLengthValid()).isTrue();

        config.setMaxDocumentLength(-1L); // <= 0 means "unlimited" -> always valid
        assertThat(config.isMaxDocumentLengthValid()).isTrue();

        config.setMaxDocumentLength(3_221_225_472L); // 3GB > 2GB ceiling -> invalid (typo / silently-defeated guard)
        assertThat(config.isMaxDocumentLengthValid()).isFalse();

        config.setMaxDocumentLength((long) Integer.MAX_VALUE); // exactly the max String/array length -> valid
        assertThat(config.isMaxDocumentLengthValid()).isTrue();

        config.setMaxDocumentLength(2_147_483_648L); // Integer.MAX_VALUE + 1 -> one byte past the real limit, invalid
        assertThat(config.isMaxDocumentLengthValid()).isFalse();
    }

    @Test
    @DisplayName("@Max: an oversized (> 2GB) maxRequestSizeBytes override fails validation at startup")
    void oversizedRequestSizeRejected() {
        JacksonConfig config = new JacksonConfig();
        config.setMaxRequestSizeBytes(3_221_225_472L); // 3GB > 2GB ceiling

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(config))
                    .anyMatch(v -> v.getMessage().contains("at most 2GB"));
        }
    }
}
