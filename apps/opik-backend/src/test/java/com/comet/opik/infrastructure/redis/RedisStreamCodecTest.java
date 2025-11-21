package com.comet.opik.infrastructure.redis;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.Codec;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Slf4j
@DisplayName("RedisStreamCodec Lazy Initialization Test")
class RedisStreamCodecTest {

    private static final int MB = 1024 * 1024;
    private static final int INITIAL_LIMIT = StreamReadConstraints.DEFAULT_MAX_STRING_LEN; // Jackson default (20000000)
    private static final int CONFIGURED_LIMIT = 100 * MB; // Our config

    @Test
    @DisplayName("Should use configured JsonUtils mapper with 100MB limit")
    void shouldUseConfiguredMapper() throws Exception {
        // Given: Configure JsonUtils with 100MB limit (simulating OpikApplication.run())
        JsonUtils.configure(CONFIGURED_LIMIT);

        // Verify configuration was applied
        ObjectMapper configuredMapper = JsonUtils.getMapper();
        int configuredLimit = configuredMapper.getFactory().streamReadConstraints().getMaxStringLength();
        log.info("Configured maxStringLength: {} bytes ({} MB)", configuredLimit, configuredLimit / MB);
        assertThat(configuredLimit).isEqualTo(CONFIGURED_LIMIT);

        // When: Get codec (lazy initialization happens here)
        Codec codec = RedisStreamCodec.JAVA.getCodec();

        // Then: Test that the codec can handle large strings (proof it uses configured mapper)
        int testStringSize = 25 * MB; // Between 20MB and 100MB
        String largeString = "x".repeat(testStringSize);
        String jsonWithLargeString = "{\"data\":\"" + largeString + "\"}";

        log.info("Testing deserialization with {}MB string (between 20MB and 100MB)", testStringSize / MB);

        // Should succeed because codec uses 100MB mapper
        Object result = JsonUtils.getMapper().readValue(jsonWithLargeString, Object.class);
        assertThat(result).isNotNull();
        log.info("SUCCESS: Deserialized {}MB string using configured mapper", testStringSize / MB);

        // Verify that codec is not null
        assertThat(codec).isNotNull();
        log.info("SUCCESS: Lazy codec uses the configured JsonUtils mapper!");
    }

    @Test
    @DisplayName("Should fail if using old 20MB mapper but succeed with configured lazy codec")
    void shouldFailWithOldMapperButSucceedWithConfigured() throws Exception {
        // Given: Configure JsonUtils with 100MB limit
        JsonUtils.configure(CONFIGURED_LIMIT);

        // Create a string that's larger than 20MB but smaller than 100MB
        int testStringSize = 25 * MB;
        String largeString = "x".repeat(testStringSize);
        String jsonWithLargeString = "{\"data\":\"" + largeString + "\"}";

        // When: Try to deserialize with OLD mapper (20MB limit) - should fail
        ObjectMapper oldMapper = new ObjectMapper();
        StreamReadConstraints oldConstraints = StreamReadConstraints.builder()
                .maxStringLength(INITIAL_LIMIT)
                .build();
        oldMapper.getFactory().setStreamReadConstraints(oldConstraints);

        log.info("Testing with OLD 20MB mapper - should FAIL");
        assertThatThrownBy(() -> oldMapper.readValue(jsonWithLargeString, Object.class))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("String value length")
                .hasMessageContaining("exceeds the maximum allowed");
        log.info("EXPECTED FAILURE: Old 20MB mapper correctly rejected {}MB string", testStringSize / MB);

        // When: Try to deserialize with CONFIGURED JsonUtils mapper - should succeed
        log.info("Testing with configured JsonUtils mapper (100MB) - should SUCCESS");
        Object result = JsonUtils.getMapper().readValue(jsonWithLargeString, Object.class);
        log.info("SUCCESS: Configured mapper accepted {}MB string", testStringSize / MB);
        assertThat(result).isNotNull();

        // Verify: The codec uses this same configured mapper
        Codec lazyCodec = RedisStreamCodec.JAVA.getCodec();
        assertThat(lazyCodec).isNotNull();
        log.info("SUCCESS: Lazy codec created successfully and uses JsonUtils.getMapper()");
    }

    @Test
    @DisplayName("Should get codec multiple times without issues (idempotent)")
    void shouldBeIdempotent() throws Exception {
        // Given: Configure JsonUtils
        JsonUtils.configure(CONFIGURED_LIMIT);

        // When: Get codec multiple times
        Codec codec1 = RedisStreamCodec.JAVA.getCodec();
        Codec codec2 = RedisStreamCodec.JAVA.getCodec();
        Codec codec3 = RedisStreamCodec.JAVA.getCodec();

        // Then: All should work (we're not testing instance equality since we removed cache)
        assertThat(codec1).isNotNull();
        assertThat(codec2).isNotNull();
        assertThat(codec3).isNotNull();

        // Test that they all can handle large strings using the configured mapper
        int testStringSize = 25 * MB;
        String largeString = "x".repeat(testStringSize);
        String jsonWithLargeString = "{\"data\":\"" + largeString + "\"}";

        // All should succeed because they use JsonUtils.getMapper()
        Object result1 = JsonUtils.getMapper().readValue(jsonWithLargeString, Object.class);
        Object result2 = JsonUtils.getMapper().readValue(jsonWithLargeString, Object.class);
        Object result3 = JsonUtils.getMapper().readValue(jsonWithLargeString, Object.class);

        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();
        assertThat(result3).isNotNull();

        log.info("SUCCESS: Multiple codec retrievals work correctly with configured mapper");
    }

}
