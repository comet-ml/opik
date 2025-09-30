package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.events.webhooks.WebhookEventTypes;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.WebhookConfig;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.config.Config;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookEventAggregationTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER_NAME = "test-user";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private RedissonReactiveClient redissonClient;
    private WebhookEventAggregationService aggregationService;
    private WebhookConfig webhookConfig;

    @BeforeAll
    void setUpAll() {
        REDIS.start();

        // Configure Redisson client
        Config config = new Config();
        config.useSingleServer()
                .setAddress(REDIS.getRedisURI());

        redissonClient = Redisson.create(config).reactive();

        // Configure webhook config
        webhookConfig = createWebhookConfig();

        // Create aggregation service
        aggregationService = new WebhookEventAggregationService(redissonClient, webhookConfig);
    }

    @AfterAll
    void tearDownAll() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        // Clean up Redis before each test
        redissonClient.getKeys().flushall().block();
    }

    @Test
    void aggregateEvent_shouldStoreEventInBucket() {
        // Given
        var alertId = UUID.randomUUID();
        var eventId = "test-event-" + UUID.randomUUID();
        var webhookEvent = createWebhookEvent(alertId, eventId, WebhookEventTypes.TRACE_CREATED);

        // When
        StepVerifier.create(aggregationService.aggregateEvent(webhookEvent))
                .verifyComplete();

        // Then - verify event was stored in bucket
        String bucketKey = generateExpectedBucketKey(webhookEvent.getCreatedAt());
        String eventKey = alertId + ":" + WebhookEventTypes.TRACE_CREATED.name();

        StepVerifier.create(
                redissonClient.getMap(bucketKey).get(eventKey)
                        .map(json -> aggregationService.parseEventIds(json)))
                .assertNext(eventIds -> {
                    assertThat(eventIds).contains(eventId);
                })
                .verifyComplete();
    }

    @Test
    void aggregateEvent_shouldAccumulateMultipleEventsForSameAlertAndType() {
        // Given
        var alertId = UUID.randomUUID();
        var event1 = createWebhookEvent(alertId, "event-1", WebhookEventTypes.TRACE_CREATED);
        var event2 = createWebhookEvent(alertId, "event-2", WebhookEventTypes.TRACE_CREATED);
        var event3 = createWebhookEvent(alertId, "event-3", WebhookEventTypes.TRACE_CREATED);

        // When
        StepVerifier.create(
                aggregationService.aggregateEvent(event1)
                        .then(aggregationService.aggregateEvent(event2))
                        .then(aggregationService.aggregateEvent(event3)))
                .verifyComplete();

        // Then - verify all three events are in the same bucket
        String bucketKey = generateExpectedBucketKey(event1.getCreatedAt());
        String eventKey = alertId + ":" + WebhookEventTypes.TRACE_CREATED.name();

        StepVerifier.create(
                redissonClient.getMap(bucketKey).get(eventKey)
                        .map(json -> aggregationService.parseEventIds(json)))
                .assertNext(eventIds -> {
                    assertThat(eventIds).hasSize(3);
                    assertThat(eventIds).containsExactlyInAnyOrder("event-1", "event-2", "event-3");
                })
                .verifyComplete();
    }

    @Test
    void aggregateEvent_shouldSeparateEventsByAlertId() {
        // Given
        var alert1 = UUID.randomUUID();
        var alert2 = UUID.randomUUID();
        var event1 = createWebhookEvent(alert1, "event-1", WebhookEventTypes.TRACE_CREATED);
        var event2 = createWebhookEvent(alert2, "event-2", WebhookEventTypes.TRACE_CREATED);

        // When
        StepVerifier.create(
                aggregationService.aggregateEvent(event1)
                        .then(aggregationService.aggregateEvent(event2)))
                .verifyComplete();

        // Then - verify events are stored separately
        String bucketKey = generateExpectedBucketKey(event1.getCreatedAt());
        String eventKey1 = alert1 + ":" + WebhookEventTypes.TRACE_CREATED.name();
        String eventKey2 = alert2 + ":" + WebhookEventTypes.TRACE_CREATED.name();

        StepVerifier.create(
                redissonClient.getMap(bucketKey).readAllMap())
                .assertNext(bucketData -> {
                    assertThat(bucketData).containsKeys(eventKey1, eventKey2);
                })
                .verifyComplete();
    }

    @Test
    void aggregateEvent_shouldSeparateEventsByEventType() {
        // Given
        var alertId = UUID.randomUUID();
        var event1 = createWebhookEvent(alertId, "event-1", WebhookEventTypes.TRACE_CREATED);
        var event2 = createWebhookEvent(alertId, "event-2", WebhookEventTypes.PROMPT_VERSION_CREATED);

        // When
        StepVerifier.create(
                aggregationService.aggregateEvent(event1)
                        .then(aggregationService.aggregateEvent(event2)))
                .verifyComplete();

        // Then - verify events are stored separately by type
        String bucketKey = generateExpectedBucketKey(event1.getCreatedAt());
        String eventKey1 = alertId + ":" + WebhookEventTypes.TRACE_CREATED.name();
        String eventKey2 = alertId + ":" + WebhookEventTypes.PROMPT_VERSION_CREATED.name();

        StepVerifier.create(
                redissonClient.getMap(bucketKey).readAllMap())
                .assertNext(bucketData -> {
                    assertThat(bucketData).containsKeys(eventKey1, eventKey2);
                })
                .verifyComplete();
    }

    @Test
    void getBucketsToProcess_shouldReturnOnlyOldBuckets() {
        // Given - create buckets from different time periods
        var now = Instant.now();
        var oldTimestamp = now.minusSeconds(120); // 2 minutes ago
        var currentTimestamp = now;

        var oldEvent = createWebhookEvent(UUID.randomUUID(), "old-event", WebhookEventTypes.TRACE_CREATED)
                .toBuilder().createdAt(oldTimestamp).build();
        var currentEvent = createWebhookEvent(UUID.randomUUID(), "current-event", WebhookEventTypes.TRACE_CREATED)
                .toBuilder().createdAt(currentTimestamp).build();

        // When - aggregate events
        StepVerifier.create(
                aggregationService.aggregateEvent(oldEvent)
                        .then(aggregationService.aggregateEvent(currentEvent)))
                .verifyComplete();

        // Then - only old bucket should be returned
        StepVerifier.create(aggregationService.getBucketsToProcess())
                .assertNext(bucketKey -> {
                    assertThat(bucketKey).isEqualTo(generateExpectedBucketKey(oldTimestamp));
                })
                .verifyComplete();
    }

    @Test
    void deleteBucket_shouldRemoveBucketFromRedis() {
        // Given
        var event = createWebhookEvent(UUID.randomUUID(), "test-event", WebhookEventTypes.TRACE_CREATED);
        String bucketKey = generateExpectedBucketKey(event.getCreatedAt());

        StepVerifier.create(aggregationService.aggregateEvent(event))
                .verifyComplete();

        // When
        StepVerifier.create(aggregationService.deleteBucket(bucketKey))
                .verifyComplete();

        // Then - bucket should not exist
        StepVerifier.create(redissonClient.getMap(bucketKey).isExists())
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void parseEventKey_shouldSplitAlertIdAndEventType() {
        // Given
        var alertId = UUID.randomUUID();
        var eventType = WebhookEventTypes.TRACE_CREATED;
        var eventKey = alertId + ":" + eventType.name();

        // When
        String[] parts = aggregationService.parseEventKey(eventKey);

        // Then
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).isEqualTo(alertId.toString());
        assertThat(parts[1]).isEqualTo(eventType.name());
    }

    @Test
    void parseEventIds_shouldDeserializeJsonToSet() {
        // Given
        var eventIds = Set.of("event-1", "event-2", "event-3");
        var json = com.comet.opik.utils.JsonUtils.writeValueAsString(eventIds);

        // When
        Set<String> parsedIds = aggregationService.parseEventIds(json);

        // Then
        assertThat(parsedIds).containsExactlyInAnyOrderElementsOf(eventIds);
    }

    private WebhookConfig createWebhookConfig() {
        var config = new WebhookConfig();
        config.setEnabled(true);
        config.setMaxRetries(3);
        config.setInitialRetryDelay(Duration.milliseconds(100));
        config.setMaxRetryDelay(Duration.seconds(5));
        config.setRequestTimeout(Duration.seconds(10));
        config.setConnectionTimeout(Duration.seconds(5));
        config.setConsumerBatchSize(10);
        config.setPoolingInterval(Duration.milliseconds(500));

        // Configure debouncing
        var debouncing = new WebhookConfig.DebouncingConfig();
        debouncing.setEnabled(true);
        debouncing.setWindowSize(Duration.minutes(1));
        debouncing.setBucketTtl(Duration.minutes(3));
        config.setDebouncing(debouncing);

        return config;
    }

    private WebhookEvent<?> createWebhookEvent(UUID alertId, String eventId, WebhookEventTypes eventType) {
        return WebhookEvent.builder()
                .id(eventId)
                .eventType(eventType)
                .alertId(alertId)
                .workspaceId(WORKSPACE_ID)
                .userName(USER_NAME)
                .url("http://localhost:8080/webhook")
                .payload(Map.of("test", "data"))
                .createdAt(Instant.now())
                .maxRetries(3)
                .headers(Map.of())
                .build();
    }

    private String generateExpectedBucketKey(Instant timestamp) {
        // Round down to the minute
        Instant roundedTimestamp = timestamp.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        return "alert_fired_debounced:" + 
               java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                       .withZone(java.time.ZoneId.of("UTC"))
                       .format(roundedTimestamp);
    }
}
