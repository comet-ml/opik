package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.WebhookConfig;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.config.Config;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AlertBucketService} focusing on configuration change handling.
 *
 * These tests verify that when the debouncing window configuration is changed:
 * 1. Existing buckets continue to use their original window size
 * 2. New buckets created after the change use the new window size
 * 3. Both types of buckets can coexist and be processed independently
 */
@DisplayName("AlertBucketService Tests")
class AlertBucketServiceTest {

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private RedissonReactiveClient redissonClient;
    private AlertBucketService alertBucketService;
    private WebhookConfig webhookConfig;

    @BeforeEach
    void setUp() {
        REDIS.start();

        // Configure Redisson client
        Config config = new Config();
        config.useSingleServer().setAddress(REDIS.getRedisURI());
        redissonClient = Redisson.create(config).reactive();

        // Create initial configuration with 1-second window for faster tests
        webhookConfig = new WebhookConfig();
        var debouncingConfig = new WebhookConfig.DebouncingConfig();
        debouncingConfig.setEnabled(true);
        debouncingConfig.setWindowSize(Duration.seconds(1));
        debouncingConfig.setBucketTtl(Duration.minutes(5));
        webhookConfig.setDebouncing(debouncingConfig);

        alertBucketService = new AlertBucketService(redissonClient, webhookConfig);

        // Clean Redis before each test
        redissonClient.getKeys().flushall().block();
    }

    @AfterEach
    void tearDown() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        REDIS.stop();
    }

    @Test
    @DisplayName("When first event added to bucket, then should store windowSize, firstSeen, and workspaceId")
    void addEventToBucket__whenFirstEvent__shouldStoreWindowSizeFirstSeenAndWorkspaceId() {
        // Given
        var alertId = UUID.randomUUID();
        var workspaceId = "test-workspace-" + UUID.randomUUID();
        var eventType = AlertEventType.TRACE_ERRORS;
        var eventId = "test-event-1";

        // When
        StepVerifier.create(alertBucketService.addEventToBucket(alertId, workspaceId, eventType, eventId))
                .verifyComplete();

        // Then - verify bucket structure
        String bucketKey = "alert_bucket:" + alertId + ":" + eventType.getValue();
        var bucket = redissonClient.getMap(bucketKey);

        StepVerifier.create(bucket.get("windowSize"))
                .assertNext(windowSize -> {
                    assertThat(Long.parseLong((String) windowSize))
                            .as("Window size should be stored as 1000ms")
                            .isEqualTo(1000L);
                })
                .verifyComplete();

        StepVerifier.create(bucket.get("firstSeen"))
                .assertNext(firstSeen -> {
                    assertThat(Long.parseLong((String) firstSeen))
                            .as("First seen timestamp should be set")
                            .isGreaterThan(0L);
                })
                .verifyComplete();

        StepVerifier.create(bucket.get("workspaceId"))
                .assertNext(storedWorkspaceId -> {
                    assertThat(storedWorkspaceId)
                            .as("Workspace ID should be stored")
                            .isEqualTo(workspaceId);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("When subsequent events added, then should preserve original windowSize")
    void addEventToBucket__whenSubsequentEvents__shouldPreserveOriginalWindowSize() {
        // Given
        var alertId = UUID.randomUUID();
        var workspaceId = "test-workspace-" + UUID.randomUUID();
        var eventType = AlertEventType.TRACE_ERRORS;
        var eventId1 = "test-event-1";
        var eventId2 = "test-event-2";

        // When - add first event
        StepVerifier.create(alertBucketService.addEventToBucket(alertId, workspaceId, eventType, eventId1))
                .verifyComplete();

        String bucketKey = "alert_bucket:" + alertId + ":" + eventType.getValue();
        var bucket = redissonClient.getMap(bucketKey);

        // Store original values
        String originalWindowSize = (String) bucket.get("windowSize").block();
        String originalFirstSeen = (String) bucket.get("firstSeen").block();
        String originalWorkspaceId = (String) bucket.get("workspaceId").block();

        // When - add second event
        StepVerifier.create(alertBucketService.addEventToBucket(alertId, workspaceId, eventType, eventId2))
                .verifyComplete();

        // Then - verify values haven't changed
        StepVerifier.create(bucket.get("windowSize"))
                .assertNext(windowSize -> {
                    assertThat(windowSize)
                            .as("Window size should remain unchanged")
                            .isEqualTo(originalWindowSize);
                })
                .verifyComplete();

        StepVerifier.create(bucket.get("firstSeen"))
                .assertNext(firstSeen -> {
                    assertThat(firstSeen)
                            .as("First seen should remain unchanged")
                            .isEqualTo(originalFirstSeen);
                })
                .verifyComplete();

        StepVerifier.create(bucket.get("workspaceId"))
                .assertNext(storedWorkspaceId -> {
                    assertThat(storedWorkspaceId)
                            .as("Workspace ID should remain unchanged")
                            .isEqualTo(originalWorkspaceId);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("When config changes, existing bucket uses original window, new bucket uses new window")
    void addEventToBucket__whenConfigChanges__shouldCreateSeparateBucketsWithDifferentWindows() {
        // Given - create first bucket with 1-second window
        var alert1Id = UUID.randomUUID();
        var workspaceId1 = "test-workspace-" + UUID.randomUUID();
        var eventType = AlertEventType.TRACE_ERRORS;
        var event1Id = "test-event-1";

        StepVerifier.create(alertBucketService.addEventToBucket(alert1Id, workspaceId1, eventType, event1Id))
                .verifyComplete();

        String bucket1Key = "alert_bucket:" + alert1Id + ":" + eventType.getValue();
        var bucket1 = redissonClient.getMap(bucket1Key);

        // Verify first bucket has 1-second window
        StepVerifier.create(bucket1.get("windowSize"))
                .assertNext(windowSize -> {
                    assertThat(Long.parseLong((String) windowSize))
                            .as("First bucket should have 1-second window")
                            .isEqualTo(1000L);
                })
                .verifyComplete();

        // When - change configuration to 2-second window
        var newDebouncingConfig = new WebhookConfig.DebouncingConfig();
        newDebouncingConfig.setEnabled(true);
        newDebouncingConfig.setWindowSize(Duration.seconds(2));
        newDebouncingConfig.setBucketTtl(Duration.minutes(5));
        webhookConfig.setDebouncing(newDebouncingConfig);

        // Create new service instance with updated config
        var updatedAlertBucketService = new AlertBucketService(redissonClient, webhookConfig);

        // Create second bucket with new config
        var alert2Id = UUID.randomUUID();
        var workspaceId2 = "test-workspace-" + UUID.randomUUID();
        var event2Id = "test-event-2";

        StepVerifier.create(updatedAlertBucketService.addEventToBucket(alert2Id, workspaceId2, eventType, event2Id))
                .verifyComplete();

        String bucket2Key = "alert_bucket:" + alert2Id + ":" + eventType.getValue();
        var bucket2 = redissonClient.getMap(bucket2Key);

        // Then - verify second bucket has 2-second window
        StepVerifier.create(bucket2.get("windowSize"))
                .assertNext(windowSize -> {
                    assertThat(Long.parseLong((String) windowSize))
                            .as("Second bucket should have 2-second window")
                            .isEqualTo(2000L);
                })
                .verifyComplete();

        // And verify first bucket still has its original 1-second window
        StepVerifier.create(bucket1.get("windowSize"))
                .assertNext(windowSize -> {
                    assertThat(Long.parseLong((String) windowSize))
                            .as("First bucket should still have original 1-second window")
                            .isEqualTo(1000L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("When processing buckets after config change, should use each bucket's stored window")
    void getBucketsReadyToProcess__afterConfigChange__shouldUseStoredWindowSizes() throws InterruptedException {
        // Given - create first bucket with 500ms window for fast testing
        var debouncingConfig = new WebhookConfig.DebouncingConfig();
        debouncingConfig.setEnabled(true);
        debouncingConfig.setWindowSize(Duration.milliseconds(500));
        debouncingConfig.setBucketTtl(Duration.minutes(5));
        webhookConfig.setDebouncing(debouncingConfig);

        var shortWindowService = new AlertBucketService(redissonClient, webhookConfig);

        var alert1Id = UUID.randomUUID();
        var workspaceId1 = "test-workspace-" + UUID.randomUUID();
        var eventType = AlertEventType.TRACE_ERRORS;

        StepVerifier.create(shortWindowService.addEventToBucket(alert1Id, workspaceId1, eventType, "event-1"))
                .verifyComplete();

        // When - change to 3-second window and create second bucket
        var newDebouncingConfig = new WebhookConfig.DebouncingConfig();
        newDebouncingConfig.setEnabled(true);
        newDebouncingConfig.setWindowSize(Duration.seconds(3));
        newDebouncingConfig.setBucketTtl(Duration.minutes(5));
        webhookConfig.setDebouncing(newDebouncingConfig);

        var longWindowService = new AlertBucketService(redissonClient, webhookConfig);

        var alert2Id = UUID.randomUUID();
        var workspaceId2 = "test-workspace-" + UUID.randomUUID();

        StepVerifier.create(longWindowService.addEventToBucket(alert2Id, workspaceId2, eventType, "event-2"))
                .verifyComplete();

        // Wait for first bucket's window to elapse (500ms + buffer)
        Thread.sleep(700);

        // Then - only first bucket should be ready (500ms window elapsed)
        StepVerifier.create(longWindowService.getBucketsReadyToProcess())
                .assertNext(bucketKey -> {
                    assertThat(bucketKey)
                            .as("First bucket should be ready after 500ms")
                            .isEqualTo("alert_bucket:" + alert1Id + ":" + eventType.getValue());
                })
                .verifyComplete();

        // Second bucket should not be ready (3-second window not elapsed)
        StepVerifier.create(longWindowService.getBucketsReadyToProcess())
                .recordWith(java.util.ArrayList::new)
                .thenConsumeWhile(key -> true)
                .consumeRecordedWith(keys -> {
                    assertThat(keys)
                            .as("Second bucket should not be ready yet")
                            .doesNotContain("alert_bucket:" + alert2Id + ":" + eventType.getValue());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("When adding events to same alert after config change, should use original window")
    void addEventToBucket__whenAddingToSameAlertAfterConfigChange__shouldUseOriginalWindow() {
        // Given - create bucket with 60-second window
        var alertId = UUID.randomUUID();
        var workspaceId = "test-workspace-" + UUID.randomUUID();
        var eventType = AlertEventType.TRACE_ERRORS;

        StepVerifier.create(alertBucketService.addEventToBucket(alertId, workspaceId, eventType, "event-1"))
                .verifyComplete();

        // When - change config to 2-second window
        var newDebouncingConfig = new WebhookConfig.DebouncingConfig();
        newDebouncingConfig.setEnabled(true);
        newDebouncingConfig.setWindowSize(Duration.seconds(2));
        newDebouncingConfig.setBucketTtl(Duration.minutes(5));
        webhookConfig.setDebouncing(newDebouncingConfig);

        var updatedService = new AlertBucketService(redissonClient, webhookConfig);

        // Add another event to the same alert
        StepVerifier.create(updatedService.addEventToBucket(alertId, workspaceId, eventType, "event-2"))
                .verifyComplete();

        // Then - bucket should still have original 1-second window
        String bucketKey = "alert_bucket:" + alertId + ":" + eventType.getValue();
        var bucket = redissonClient.getMap(bucketKey);

        StepVerifier.create(bucket.get("windowSize"))
                .assertNext(windowSize -> {
                    assertThat(Long.parseLong((String) windowSize))
                            .as("Should preserve original 1-second window despite config change")
                            .isEqualTo(1000L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("When retrieving bucket data, should return all event IDs")
    void getBucketData__shouldReturnAllEventIds() {
        // Given
        var alertId = UUID.randomUUID();
        var workspaceId = "test-workspace-" + UUID.randomUUID();
        var eventType = AlertEventType.TRACE_ERRORS;
        var eventId1 = "test-event-1";
        var eventId2 = "test-event-2";

        // When - add multiple events
        StepVerifier.create(
                alertBucketService.addEventToBucket(alertId, workspaceId, eventType, eventId1)
                        .then(alertBucketService.addEventToBucket(alertId, workspaceId, eventType, eventId2)))
                .verifyComplete();

        String bucketKey = "alert_bucket:" + alertId + ":" + eventType.getValue();

        // Then - verify both events are in bucket
        StepVerifier.create(alertBucketService.getBucketData(bucketKey))
                .assertNext(bucketData -> {
                    assertThat(bucketData.eventIds())
                            .as("Bucket should contain both event IDs")
                            .hasSize(2)
                            .contains(eventId1, eventId2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("When deleting bucket, should remove from Redis")
    void deleteBucket__shouldRemoveBucketFromRedis() {
        // Given
        var alertId = UUID.randomUUID();
        var workspaceId = "test-workspace-" + UUID.randomUUID();
        var eventType = AlertEventType.TRACE_ERRORS;
        var eventId = "test-event-1";

        StepVerifier.create(alertBucketService.addEventToBucket(alertId, workspaceId, eventType, eventId))
                .verifyComplete();

        String bucketKey = "alert_bucket:" + alertId + ":" + eventType.getValue();

        // Verify bucket exists
        StepVerifier.create(redissonClient.getMap(bucketKey).isExists())
                .expectNext(true)
                .verifyComplete();

        // When
        StepVerifier.create(alertBucketService.deleteBucket(bucketKey))
                .verifyComplete();

        // Then
        StepVerifier.create(redissonClient.getMap(bucketKey).isExists())
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("When adding first event to bucket, should set TTL")
    void addEventToBucket__whenFirstEvent__shouldSetTtl() {
        // Given
        var alertId = UUID.randomUUID();
        var workspaceId = "test-workspace-" + UUID.randomUUID();
        var eventType = AlertEventType.TRACE_ERRORS;
        var eventId = "test-event-1";
        long expectedTtlMillis = webhookConfig.getDebouncing().getBucketTtl().toMilliseconds();

        // When - add first event
        StepVerifier.create(alertBucketService.addEventToBucket(alertId, workspaceId, eventType, eventId))
                .verifyComplete();

        // Then - verify TTL is set
        String bucketKey = "alert_bucket:" + alertId + ":" + eventType.getValue();
        var bucket = redissonClient.getMap(bucketKey);

        StepVerifier.create(bucket.remainTimeToLive())
                .assertNext(ttl -> {
                    assertThat(ttl)
                            .as("Bucket should have TTL set on creation")
                            .isGreaterThan(0L)
                            .as("TTL should be approximately equal to configured bucketTtl (5 minutes)")
                            .isLessThanOrEqualTo(expectedTtlMillis);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("When adding subsequent events, should NOT refresh TTL")
    void addEventToBucket__whenSubsequentEvents__shouldNotRefreshTtl() throws InterruptedException {
        // Given
        var alertId = UUID.randomUUID();
        var workspaceId = "test-workspace-" + UUID.randomUUID();
        var eventType = AlertEventType.TRACE_ERRORS;

        // When - add first event
        StepVerifier.create(alertBucketService.addEventToBucket(alertId, workspaceId, eventType, "event-1"))
                .verifyComplete();

        String bucketKey = "alert_bucket:" + alertId + ":" + eventType.getValue();
        var bucket = redissonClient.getMap(bucketKey);

        // Get initial TTL using StepVerifier to avoid blocking
        java.util.concurrent.atomic.AtomicReference<Long> initialTtlRef = new java.util.concurrent.atomic.AtomicReference<>();
        StepVerifier.create(bucket.remainTimeToLive())
                .assertNext(initialTtlRef::set)
                .verifyComplete();
        Long initialTtl = initialTtlRef.get();

        // Wait a bit so TTL decreases
        Thread.sleep(100);

        // Add second event
        StepVerifier.create(alertBucketService.addEventToBucket(alertId, workspaceId, eventType, "event-2"))
                .verifyComplete();

        // Then - verify TTL was NOT refreshed (it should be less than initial)
        StepVerifier.create(bucket.remainTimeToLive())
                .assertNext(currentTtl -> {
                    assertThat(currentTtl)
                            .as("TTL should not be refreshed on subsequent events")
                            .isLessThan(initialTtl)
                            .as("TTL should still be positive")
                            .isGreaterThan(0L);
                })
                .verifyComplete();
    }
}
