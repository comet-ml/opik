package com.comet.opik.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@DisplayName("Redis Configuration Integration Test")
@Disabled
class RedisConfigIntegrationTest {

    // Disabled by default
    // To enable, set the following environment variables to use AWS IAM authentication with ElasticCache Redis:
    // - AWS_ACCESS_KEY_ID
    // - AWS_SECRET_ACCESS_KEY
    // - AWS_REGION
    // - OPIK_REDIS_AWS_USER_ID
    // - OPIK_REDIS_AWS_RESOURCE_NAME
    // - REDIS_URL (e.g., redis://your-elasticache-endpoint:6379/0)

    private static final String REDIS_URL = System.getenv("REDIS_URL");
    private static final String AWS_REGION = System.getenv("AWS_REGION");
    private static final String AWS_USER_ID = System.getenv("OPIK_REDIS_AWS_USER_ID");
    private static final String AWS_RESOURCE_NAME = System.getenv("OPIK_REDIS_AWS_RESOURCE_NAME");

    @Test
    @DisplayName("Should connect to ElasticCache Redis and perform basic operations using AWS IAM authentication")
    @EnabledIfEnvironmentVariable(named = "AWS_ACCESS_KEY_ID", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "AWS_SECRET_ACCESS_KEY", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "AWS_REGION", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "OPIK_REDIS_AWS_USER_ID", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "OPIK_REDIS_AWS_RESOURCE_NAME", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "REDIS_URL", matches = ".+")
    void shouldConnectToElasticCacheRedis_withAwsIamAuth() {
        log.info("🚀 Starting Redis AWS IAM integration test");

        // Given
        log.info("📋 Environment Configuration:");
        log.info("   REDIS_URL: '{}'", REDIS_URL);
        log.info("   AWS_REGION: '{}'", AWS_REGION);
        log.info("   AWS_USER_ID: '{}'", AWS_USER_ID);
        log.info("   AWS_RESOURCE_NAME: '{}'", AWS_RESOURCE_NAME);

        var redisConfig = new RedisConfig();
        redisConfig.setSingleNodeUrl(REDIS_URL);

        var awsIamAuth = new RedisConfig.AwsIamAuthConfig();
        awsIamAuth.setEnabled(true);
        awsIamAuth.setAwsUserId(AWS_USER_ID); // ElasticCache user ID from environment
        awsIamAuth.setAwsRegion(AWS_REGION); // AWS region from environment
        awsIamAuth.setAwsResourceName(AWS_RESOURCE_NAME); // ElasticCache replication group ID from environment

        redisConfig.setAwsIamAuth(awsIamAuth);

        log.info("🔧 Redis configuration created with AWS IAM authentication enabled");

        // When
        log.info("🏗️ Building Redis configuration...");
        var config = redisConfig.build();

        log.info("🔌 Creating Redisson reactive client...");
        RedissonReactiveClient redissonClient = Redisson.create(config).reactive();
        log.info("✅ Redisson reactive client created successfully");

        // Test basic Redis operations with AWS IAM authentication
        var testKey = "test:iam:integration:" + UUID.randomUUID();
        var testValue = "iam-integration-test-value-" + System.currentTimeMillis();

        log.info("🧪 Testing basic Redis operations with key: '{}'", testKey);

        var bucket = redissonClient.getBucket(testKey);

        // Set value with expiration
        log.info("📝 Setting test value: '{}'", testValue);
        bucket.set(testValue).block();
        log.info("✅ Value set successfully");

        // Get value back
        log.info("📖 Retrieving test value...");
        var retrievedValue = bucket.get().block();
        log.info("✅ Value retrieved: '{}'", retrievedValue);

        // Then
        assertThat(retrievedValue).isEqualTo(testValue);
        assertThat(bucket.isExists().block()).isTrue();
        log.info("✅ Basic bucket operations verified");

        // Test map operations
        var mapKey = "test:iam:map:" + UUID.randomUUID();
        var map = redissonClient.getMap(mapKey);

        log.info("🗺️ Testing map operations with key: '{}'", mapKey);

        map.put("field1", "value1").block();
        map.put("field2", "value2").block();
        log.info("✅ Map values set successfully");

        assertThat(map.get("field1").block()).isEqualTo("value1");
        assertThat(map.get("field2").block()).isEqualTo("value2");
        assertThat(map.size().block()).isEqualTo(2);
        log.info("✅ Map operations verified");

        // Cleanup
        log.info("🧹 Cleaning up test data...");
        bucket.delete().block();
        map.delete().block();

        assertThat(bucket.isExists().block()).isFalse();
        assertThat(map.isExists().block()).isFalse();
        log.info("✅ Cleanup completed successfully");

        log.info("🔌 Shutting down Redisson client...");
        redissonClient.shutdown();
        log.info("🎉 Redis AWS IAM integration test completed successfully!");
    }
}
