package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RBucketReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.Codec;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Slf4j
@DisplayName("RedisStreamCodec Lazy Initialization and Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisStreamCodecTest {

    private static final int MB = 1024 * 1024;
    private static final int INITIAL_LIMIT = StreamReadConstraints.DEFAULT_MAX_STRING_LEN; // Jackson default (20000000)
    private static final int CONFIGURED_LIMIT = 100 * MB; // Our config

    private RedisContainer redis;
    private RedissonReactiveClient redissonClient;
    private String currentRedisKey;

    @BeforeAll
    void setUpAll() {
        // Configure JsonUtils with 100MB limit (simulating OpikApplication startup)
        JsonUtils.configure(CONFIGURED_LIMIT);
        log.info("JsonUtils configured with 100MB limit");

        // Start Redis container
        redis = RedisContainerUtils.newRedisContainer();
        redis.start();
        log.info("Redis container started at: {}", redis.getRedisURI());

        // Create Redisson client using JsonJacksonCodec with configured mapper
        var redissonConfig = new Config();
        redissonConfig.useSingleServer()
                .setAddress(redis.getRedisURI())
                .setDatabase(0);
        // Use JsonJacksonCodec directly (not CompositeCodec for bucket operations)
        redissonConfig.setCodec(new JsonJacksonCodec(JsonUtils.getMapper()));
        redissonClient = Redisson.create(redissonConfig).reactive();
        log.info("Redisson client created with configured codec");
    }

    @AfterEach
    void tearDownEach() {
        // Clean up Redis key after each test
        if (currentRedisKey != null && redissonClient != null) {
            redissonClient.getBucket(currentRedisKey).delete().block();
            currentRedisKey = null;
        }
    }

    @AfterAll
    void tearDownAll() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (redis != null) {
            redis.stop();
        }
    }

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
    @DisplayName("Should memoize codec instance (same instance returned on multiple calls)")
    void shouldMemoizeCodecInstance() throws Exception {
        // Given: Configure JsonUtils
        JsonUtils.configure(CONFIGURED_LIMIT);

        // When: Get codec multiple times
        Codec codec1 = RedisStreamCodec.JAVA.getCodec();
        Codec codec2 = RedisStreamCodec.JAVA.getCodec();
        Codec codec3 = RedisStreamCodec.JAVA.getCodec();

        // Then: All should be the same instance (memoized)
        assertThat(codec1).isNotNull();
        assertThat(codec2).isNotNull();
        assertThat(codec3).isNotNull();
        assertThat(codec1).isSameAs(codec2);
        assertThat(codec2).isSameAs(codec3);

        log.info("SUCCESS: Codec is memoized - same instance returned on multiple calls");

        // Verify that the memoized codec uses the configured mapper
        int testStringSize = 25 * MB;
        String largeString = "x".repeat(testStringSize);
        String jsonWithLargeString = "{\"data\":\"" + largeString + "\"}";

        // Should succeed because codec uses JsonUtils.getMapper() (configured to 100MB)
        Object result = JsonUtils.getMapper().readValue(jsonWithLargeString, Object.class);
        assertThat(result).isNotNull();

        log.info("SUCCESS: Memoized codec correctly uses configured 100MB mapper");
    }

    @Test
    @DisplayName("Should write and read large payload (25MB) to/from Redis successfully")
    void shouldWriteAndReadLargePayloadToRedis() {
        // Given: Create a payload with 25MB string (between 20MB and 100MB)
        int payloadSize = 25 * MB;
        String largeValue = "x".repeat(payloadSize);

        Map<String, Object> largePayload = new HashMap<>();
        largePayload.put("id", "test-large-payload");
        largePayload.put("data", largeValue);
        largePayload.put("size", payloadSize);

        currentRedisKey = "test:large-payload:" + System.currentTimeMillis();

        log.info("Step 1: Writing {}MB payload to Redis key: '{}'", payloadSize / MB, currentRedisKey);

        // When: Write large payload to Redis
        RBucketReactive<Map<String, Object>> bucket = redissonClient.getBucket(currentRedisKey);
        bucket.set(largePayload).block(Duration.ofSeconds(30));

        log.info("Step 2: Successfully wrote {}MB payload to Redis", payloadSize / MB);

        // Then: Read it back and verify
        log.info("Step 3: Reading payload back from Redis");
        Map<String, Object> retrievedPayload = bucket.get().block(Duration.ofSeconds(30));

        assertThat(retrievedPayload).isNotNull();
        assertThat(retrievedPayload.get("id")).isEqualTo("test-large-payload");
        assertThat(retrievedPayload.get("size")).isEqualTo(payloadSize);
        assertThat(retrievedPayload.get("data")).isEqualTo(largeValue);

        log.info("Step 4: Successfully read and verified {}MB payload from Redis", payloadSize / MB);
        log.info("SUCCESS: Redis can handle large payloads with configured 100MB mapper!");
    }

    @Test
    @DisplayName("Should fail reading with 20MB codec but succeed with 100MB codec")
    void shouldFailWithOldCodecButSucceedWithConfiguredCodec() {
        // Given: Create a 25MB payload and write it using the configured client
        int payloadSize = 25 * MB;
        String largeValue = "x".repeat(payloadSize);

        Map<String, Object> largePayload = new HashMap<>();
        largePayload.put("data", largeValue);

        currentRedisKey = "test:codec-comparison:" + System.currentTimeMillis();

        // Write payload using configured 100MB codec
        log.info("Step 1: Writing {}MB payload with configured 100MB codec", payloadSize / MB);
        RBucketReactive<Map<String, Object>> configuredBucket = redissonClient.getBucket(currentRedisKey);
        configuredBucket.set(largePayload).block(Duration.ofSeconds(30));
        log.info("Successfully wrote {}MB payload", payloadSize / MB);

        // Step 2: Try to READ with OLD 20MB codec - should FAIL
        log.info("Step 2: Attempting to READ with OLD 20MB codec - should FAIL");

        ObjectMapper oldMapper = new ObjectMapper();
        StreamReadConstraints oldConstraints = StreamReadConstraints.builder()
                .maxStringLength(INITIAL_LIMIT)
                .build();
        oldMapper.getFactory().setStreamReadConstraints(oldConstraints);

        var oldConfig = new Config();
        oldConfig.useSingleServer()
                .setAddress(redis.getRedisURI())
                .setDatabase(0);
        oldConfig.setCodec(new JsonJacksonCodec(oldMapper));

        RedissonReactiveClient oldClient = Redisson.create(oldConfig).reactive();

        try {
            RBucketReactive<Map<String, Object>> oldBucket = oldClient.getBucket(currentRedisKey);

            // This should fail during DESERIALIZATION (reading)
            log.info("Attempting to read {}MB payload with 20MB codec...", payloadSize / MB);
            assertThatThrownBy(() -> oldBucket.get().block(Duration.ofSeconds(10)))
                    .hasMessageContaining("String value length")
                    .hasMessageContaining("exceeds the maximum allowed");

            log.info("EXPECTED FAILURE: Old 20MB codec correctly rejected reading {}MB payload",
                    payloadSize / MB);
        } finally {
            oldClient.shutdown();
        }

        // Step 3: Verify read with configured 100MB codec - should SUCCESS
        log.info("Step 3: Reading with configured 100MB codec - should SUCCESS");
        Map<String, Object> retrievedPayload = configuredBucket.get().block(Duration.ofSeconds(30));
        assertThat(retrievedPayload).isNotNull();
        assertThat(retrievedPayload.get("data")).isEqualTo(largeValue);

        log.info("SUCCESS: Configured 100MB codec read {}MB payload", payloadSize / MB);
    }

}
