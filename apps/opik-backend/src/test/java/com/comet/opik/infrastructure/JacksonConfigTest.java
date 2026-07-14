package com.comet.opik.infrastructure;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JacksonConfig defaults")
class JacksonConfigTest {

    @Test
    @DisplayName("POJO defaults: 512MB document + request (self-hosted); document budget stays above the string limit")
    void defaults() {
        JacksonConfig config = new JacksonConfig();

        // maxStringLength POJO default is Jackson's (20MB); config.yml raises it to 100MB at load.
        assertThat(config.getMaxStringLength()).isEqualTo(StreamReadConstraints.DEFAULT_MAX_STRING_LEN);
        // 512MB is the self-hosted default; the Comet cloud deployment overrides both to 256MB.
        assertThat(config.getMaxDocumentLength()).isEqualTo(536_870_912L); // 512MB
        assertThat(config.getMaxRequestSizeBytes()).isEqualTo(536_870_912L); // 512MB
        // request cap == document cap, so the filter never shadows the document guard.
        assertThat(config.getMaxRequestSizeBytes()).isEqualTo(config.getMaxDocumentLength());
        // A single legitimate max-size string must still fit inside the document budget
        // (holds for both the 20MB POJO default and the 100MB config.yml value).
        assertThat(config.getMaxDocumentLength()).isGreaterThan(104_857_600L); // > 100MB maxStringLength
    }
}
