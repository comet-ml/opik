package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue.AnnotationScope;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueRoutingMessage;
import com.comet.opik.domain.AnnotationQueueRoutingPublisher;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.Redisson;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OPIK-6303, cross-layer half. The unit tests assert what the listener asks the publisher to do; this
 * asserts what actually lands on a real Redis stream and survives the shipped codec, driving the real
 * listener and the real publisher.
 *
 * <p>Stops at the stream rather than driving the REST API end to end: the router rule that the guard reads
 * has no REST surface on this PR, so there is nothing to create one through from outside, and standing up
 * MySQL and ClickHouse to re-observe a Redis write would test the harness more than the change. The
 * automation service is therefore the one mock here, and the disabled cases assert
 * {@code verifyNoInteractions} on it so that a guard which stopped short cannot pass unnoticed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnotationQueueRoutingIntegrationTest {

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final IdGenerator idGenerator = TestIdGeneratorFactory.create();

    private RedissonReactiveClient redissonClient;
    private AnnotationQueueRoutingConfig config;
    private AnnotationQueueAutomationService automationService;
    private AnnotationQueueRoutingListener listener;

    @BeforeAll
    void setUpAll() {
        redis.start();
        var redissonConfig = new Config();
        redissonConfig.useSingleServer().setAddress(redis.getRedisURI()).setDatabase(0);
        redissonClient = Redisson.create(redissonConfig).reactive();
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

    /**
     * A stream per test, so one test's entries can never be read by another - these run against a reused
     * container, and the listener is fire and forget, so a late write has nowhere to land but its own key.
     */
    @BeforeEach
    void setUp() {
        config = new AnnotationQueueRoutingConfig();
        config.setEnabled(true);
        config.setStreamName("test-stream-%s".formatted(randomString().toLowerCase()));
        config.setStreamMaxLen(10_000);
        config.setStreamTrimLimit(100);

        automationService = mock(AnnotationQueueAutomationService.class);
        listener = new AnnotationQueueRoutingListener(automationService,
                new AnnotationQueueRoutingPublisher(redissonClient, config), config);
    }

    static Stream<Arguments> scopes() {
        return Stream.of(
                Arguments.of(EntityType.TRACE, AnnotationScope.TRACE, true),
                Arguments.of(EntityType.TRACE, AnnotationScope.TRACE, false),
                Arguments.of(EntityType.THREAD, AnnotationScope.THREAD, true),
                Arguments.of(EntityType.THREAD, AnnotationScope.THREAD, false));
    }

    @ParameterizedTest
    @MethodSource("scopes")
    @DisplayName("An admitted event lands one decodable entry carrying the whole batch")
    void admittedEventLandsOneEntryOnTheStream(EntityType entityType, AnnotationScope scope,
            boolean hasProjectId) {

        UUID projectId = hasProjectId ? idGenerator.generateId() : null;
        var entityIds = Set.of(idGenerator.generateId(), idGenerator.generateId(), idGenerator.generateId());
        var scoreNames = Set.of(randomString(), randomString());
        String workspaceId = randomString();
        String userName = randomString();
        when(automationService.hasEnabledAutomation(workspaceId, projectId, scope)).thenReturn(true);

        listener.onFeedbackScoresCreated(new FeedbackScoresCreated(entityIds, entityType, workspaceId,
                userName, projectId, scoreNames));

        // Compared whole, against an independently built expectation: asserting only the entity ids would
        // pass while the codec silently dropped the author, the scope or the score names.
        var expected = AnnotationQueueRoutingMessage.builder()
                .workspaceId(workspaceId)
                .userName(userName)
                .scope(scope)
                .entityIds(entityIds)
                .scoreNames(scoreNames)
                .build();
        assertThat(awaitStream(1)).containsExactly(expected);
    }

    @Test
    @DisplayName("The whole batch travels in one entry, not one entry per entity")
    void aBatchOfEntitiesTravelsInASingleEntry() {
        var entityIds = Stream.generate(idGenerator::generateId).limit(50)
                .collect(Collectors.toUnmodifiableSet());
        String workspaceId = randomString();
        when(automationService.hasEnabledAutomation(anyString(), any(), any())).thenReturn(true);

        listener.onFeedbackScoresCreated(new FeedbackScoresCreated(entityIds, EntityType.TRACE, workspaceId,
                randomString(), idGenerator.generateId(), Set.of(randomString())));

        var published = awaitStream(1);
        assertThat(published).singleElement()
                .extracting(AnnotationQueueRoutingMessage::entityIds)
                .isEqualTo(entityIds);
    }

    @Test
    @DisplayName("Score names survive the codec and are readable per entity")
    void scoreNamesAreReadableForEveryEntityInTheBatch() {
        var entityIds = Set.of(idGenerator.generateId(), idGenerator.generateId());
        var scoreNames = Set.of(randomString(), randomString());
        when(automationService.hasEnabledAutomation(anyString(), any(), any())).thenReturn(true);

        listener.onFeedbackScoresCreated(new FeedbackScoresCreated(entityIds, EntityType.TRACE,
                randomString(), randomString(), idGenerator.generateId(), scoreNames));

        var message = awaitStream(1).getFirst();
        // The flat set is read back per entity, which is the shape the consumer asks for.
        entityIds.forEach(entityId -> assertThat(message.expectedScoreNames(entityId)).isEqualTo(scoreNames));
        assertThat(message.expectedScoreNames(idGenerator.generateId())).isEmpty();
    }

    @Test
    @DisplayName("Nothing is published, and the guard is never even reached, when routing is disabled")
    void disabledRoutingWritesNothingAndSkipsTheLookup() {
        config.setEnabled(false);

        listener.onFeedbackScoresCreated(new FeedbackScoresCreated(
                Set.of(idGenerator.generateId()), EntityType.TRACE, randomString(), randomString(),
                idGenerator.generateId(), Set.of()));

        assertNothingPublished();
        // Without this the test would pass on a listener that ran the lookup and only skipped the write.
        verifyNoInteractions(automationService);
    }

    @Test
    @DisplayName("Nothing is published when the workspace has no enabled automation")
    void noEnabledAutomationWritesNothing() {
        when(automationService.hasEnabledAutomation(anyString(), any(), any())).thenReturn(false);

        listener.onFeedbackScoresCreated(new FeedbackScoresCreated(
                Set.of(idGenerator.generateId()), EntityType.TRACE, randomString(), randomString(),
                idGenerator.generateId(), Set.of()));

        assertNothingPublished();
    }

    private List<AnnotationQueueRoutingMessage> awaitStream(int expectedSize) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(readStream()).hasSize(expectedSize));
        return readStream();
    }

    /**
     * Waits before concluding, rather than reading once. The listener subscribes and returns, so an
     * immediate read would pass whether the guard held or simply had not been overtaken yet.
     */
    private void assertNothingPublished() {
        Awaitility.await()
                .during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(readStream()).isEmpty());
    }

    private List<AnnotationQueueRoutingMessage> readStream() {
        RStreamReactive<String, AnnotationQueueRoutingMessage> stream = redissonClient
                .getStream(config.getStreamName(), config.getCodec());
        var entries = stream.range(StreamMessageId.MIN, StreamMessageId.MAX).block();
        return entries == null
                ? List.of()
                : entries.values().stream()
                        .map(entry -> entry.get(AnnotationQueueRoutingConfig.PAYLOAD_FIELD))
                        .toList();
    }

    private static String randomString() {
        return RandomStringUtils.secure().nextAlphanumeric(20);
    }
}
