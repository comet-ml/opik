package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AnnotationQueue.AnnotationScope;
import com.comet.opik.api.events.FeedbackScoresCreated;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.domain.AnnotationQueueAutomationService;
import com.comet.opik.domain.AnnotationQueueRoutingBufferService;
import com.comet.opik.domain.AnnotationQueueRoutingMessage;
import com.comet.opik.domain.AnnotationQueueRoutingPublisher;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
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
import org.redisson.api.RScoredSortedSetReactive;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashSet;
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
 * OPIK-6303, cross-layer half. The unit tests assert what the listener asks the buffer to do; this asserts
 * what actually lands in a real Redis buffer, what a real flush publishes to a real stream, and that it
 * survives the shipped codec — driving the real listener, buffer service and publisher.
 *
 * <p>Stops at the stream rather than driving the REST API end to end: the router rule that the guard reads
 * is the automation service's, mocked here, and standing up MySQL and ClickHouse to re-observe Redis writes
 * would test the harness more than the change. The disabled cases assert {@code verifyNoInteractions} on
 * that mock so a guard which stopped short cannot pass unnoticed.
 *
 * <p>The flush is invoked directly rather than through the Quartz job, which only adds the lock and the
 * schedule; {@code AnnotationQueueRoutingFlushJobTest} covers that orchestration.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnotationQueueRoutingIntegrationTest {

    private static final Duration IMMEDIATE = Duration.milliseconds(100);
    private static final Duration FAR_AWAY = Duration.seconds(30);

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final IdGenerator idGenerator = TestIdGeneratorFactory.create();

    private RedissonReactiveClient redissonClient;
    private AnnotationQueueRoutingConfig config;
    private AnnotationQueueAutomationService automationService;
    private AnnotationQueueRoutingPublisher publisher;
    private AnnotationQueueRoutingBufferService bufferService;
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
     * A stream per test, so one test's entries can never be read by another. The buffer key is a constant,
     * so it is emptied instead: every test awaits its own writes before finishing, so nothing lands late.
     */
    @BeforeEach
    void setUp() {
        redissonClient.getKeys().delete(AnnotationQueueRoutingConfig.PENDING_SET_KEY).block();
        wire(IMMEDIATE, 100);
    }

    private void wire(Duration bufferMinAge, int jobBatchSize) {
        config = AnnotationQueueRoutingConfig.builder()
                .enabled(true)
                .streamName("test-stream-%s".formatted(randomString().toLowerCase()))
                .streamMaxLen(10_000)
                .streamTrimLimit(100)
                .bufferMinAge(bufferMinAge)
                .bufferTtl(Duration.minutes(1))
                .jobBatchSize(jobBatchSize)
                .build();

        automationService = mock(AnnotationQueueAutomationService.class);
        publisher = new AnnotationQueueRoutingPublisher(redissonClient, config);
        bufferService = new AnnotationQueueRoutingBufferService(redissonClient, publisher, config);
        listener = new AnnotationQueueRoutingListener(automationService, bufferService, config);
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
    @DisplayName("An admitted event is buffered, then flushed as one decodable entry carrying the whole batch")
    void admittedEventIsBufferedThenFlushedAsOneEntry(EntityType entityType, AnnotationScope scope,
            boolean hasProjectId) {

        UUID projectId = hasProjectId ? idGenerator.generateId() : null;
        var entityIds = Set.of(idGenerator.generateId(), idGenerator.generateId(), idGenerator.generateId());
        String workspaceId = randomString();
        when(automationService.hasEnabledAutomation(workspaceId, projectId, scope)).thenReturn(true);

        listener.onFeedbackScoresCreated(new FeedbackScoresCreated(entityIds, entityType, workspaceId,
                randomString(), projectId));

        // One member per entity, not per event.
        awaitBuffered(3);
        assertThat(readStream()).isEmpty();

        var published = awaitFlushed(1);

        // Compared whole, against an independently built expectation: asserting only the entity ids would
        // pass while the codec silently dropped the scope or the workspace.
        assertThat(published).containsExactly(AnnotationQueueRoutingMessage.builder()
                .workspaceId(workspaceId)
                .scope(scope)
                .entityIds(entityIds)
                .build());
        assertThat(bufferSize()).isZero();
    }

    @Test
    @DisplayName("An entity scored again while it waits folds into the one buffered member")
    void repeatedScoresFoldIntoOneMember() {
        UUID entityId = idGenerator.generateId();
        String workspaceId = randomString();
        when(automationService.hasEnabledAutomation(anyString(), any(), any())).thenReturn(true);

        for (int i = 0; i < 5; i++) {
            listener.onFeedbackScoresCreated(new FeedbackScoresCreated(Set.of(entityId), EntityType.TRACE,
                    workspaceId, randomString(), idGenerator.generateId()));
        }
        awaitBuffered(1);

        var published = awaitFlushed(1);
        assertThat(published).singleElement()
                .extracting(AnnotationQueueRoutingMessage::entityIds)
                .isEqualTo(Set.of(entityId));
    }

    @Test
    @DisplayName("Nothing younger than bufferMinAge is flushed")
    void nothingYoungerThanMinAgeIsFlushed() {
        wire(FAR_AWAY, 100);
        UUID entityId = idGenerator.generateId();
        when(automationService.hasEnabledAutomation(anyString(), any(), any())).thenReturn(true);

        listener.onFeedbackScoresCreated(event(EntityType.TRACE, randomString(), entityId));
        awaitBuffered(1);

        assertThat(bufferService.flush().block()).isZero();
        assertThat(readStream()).isEmpty();
        assertThat(bufferSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("A flush publishes one entry per (workspace, scope), never mixing them")
    void flushGroupsByWorkspaceAndScope() {
        String workspaceId = randomString();
        UUID traceHere = idGenerator.generateId();
        UUID anotherTraceHere = idGenerator.generateId();
        UUID threadHere = idGenerator.generateId();
        UUID traceElsewhere = idGenerator.generateId();
        when(automationService.hasEnabledAutomation(anyString(), any(), any())).thenReturn(true);

        listener.onFeedbackScoresCreated(event(EntityType.TRACE, workspaceId, traceHere));
        listener.onFeedbackScoresCreated(event(EntityType.TRACE, workspaceId, anotherTraceHere));
        listener.onFeedbackScoresCreated(event(EntityType.THREAD, workspaceId, threadHere));
        listener.onFeedbackScoresCreated(event(EntityType.TRACE, randomString(), traceElsewhere));
        awaitBuffered(4);

        var published = awaitFlushed(3);

        // Two events from different authors in the same workspace and scope travel as one entry.
        assertThat(published)
                .extracting(AnnotationQueueRoutingMessage::entityIds)
                .containsExactlyInAnyOrder(Set.of(traceHere, anotherTraceHere), Set.of(threadHere),
                        Set.of(traceElsewhere));
        assertThat(published)
                .filteredOn(message -> message.entityIds().contains(threadHere))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.scope()).isEqualTo(AnnotationScope.THREAD);
                    assertThat(message.workspaceId()).isEqualTo(workspaceId);
                });
    }

    @Test
    @DisplayName("A backlog larger than one page is flushed in full, page by page")
    void backlogLargerThanOnePageIsFlushedInFull() {
        wire(IMMEDIATE, 100);
        String workspaceId = randomString();
        var entityIds = Stream.generate(idGenerator::generateId).limit(250).collect(Collectors.toUnmodifiableSet());
        when(automationService.hasEnabledAutomation(anyString(), any(), any())).thenReturn(true);

        listener.onFeedbackScoresCreated(new FeedbackScoresCreated(entityIds, EntityType.TRACE, workspaceId,
                randomString(), idGenerator.generateId()));
        awaitBuffered(250);

        long published = awaitMinAge(() -> bufferService.flush().block());

        // Three pages of 100, one entry each; the split is a paging artefact and the union is what matters.
        assertThat(published).isEqualTo(3);
        Set<UUID> routed = new HashSet<>();
        readStream().forEach(message -> routed.addAll(message.entityIds()));
        assertThat(routed).isEqualTo(entityIds);
        assertThat(bufferSize()).isZero();
    }

    @Test
    @DisplayName("A malformed member is dropped from the buffer rather than re-read every run")
    void malformedMemberIsDropped() {
        pending().add(Instant.now().minusSeconds(60).toEpochMilli(), "not json at all").block();

        assertThat(awaitMinAge(() -> bufferService.flush().block())).isZero();
        assertThat(readStream()).isEmpty();
        assertThat(bufferSize()).isZero();
    }

    @Test
    @DisplayName("The buffer key carries the configured TTL from its first write")
    void bufferKeyCarriesTtl() {
        UUID entityId = idGenerator.generateId();
        when(automationService.hasEnabledAutomation(anyString(), any(), any())).thenReturn(true);

        listener.onFeedbackScoresCreated(event(EntityType.TRACE, randomString(), entityId));
        awaitBuffered(1);

        Long ttlMillis = pending().remainTimeToLive().block();
        assertThat(ttlMillis).isNotNull().isPositive()
                .isLessThanOrEqualTo(config.getBufferTtl().toMilliseconds());
    }

    @Test
    @DisplayName("Disabled routing buffers nothing and never reaches the automation lookup")
    void disabledRoutingWritesNothingAndSkipsTheLookup() {
        config = config.toBuilder().enabled(false).build();
        listener = new AnnotationQueueRoutingListener(automationService, bufferService, config);

        listener.onFeedbackScoresCreated(event(EntityType.TRACE, randomString(), idGenerator.generateId()));

        assertNothingBuffered();
        verifyNoInteractions(automationService);
    }

    @Test
    @DisplayName("No enabled automation means nothing is buffered")
    void noEnabledAutomationWritesNothing() {
        when(automationService.hasEnabledAutomation(anyString(), any(), any())).thenReturn(false);

        listener.onFeedbackScoresCreated(event(EntityType.TRACE, randomString(), idGenerator.generateId()));

        assertNothingBuffered();
    }

    @Test
    @DisplayName("An enqueue with no entities writes nothing, and neither does a null set")
    void enqueueWithoutEntitiesWritesNothing() {
        publisher.enqueue(randomString(), AnnotationScope.TRACE, Set.of()).block();
        publisher.enqueue(randomString(), AnnotationScope.TRACE, null).block();

        assertThat(readStream()).isEmpty();
    }

    /**
     * The trim settings reach Redis through the value-taking {@code buildAddArgs} overload, which is the
     * only thing standing between a stalled consumer and an unbounded stream. Asserted by behaviour rather
     * than by inspecting {@code StreamAddArgs}, which is a builder with no accessors — reading its state
     * back would test Redisson, not this.
     *
     * <p>Only "smaller than what was written" is asserted: {@code MAXLEN ~} trims whole macro nodes when it
     * is cheap to, so the surviving count is deliberately not exact and pinning it would be a flake.
     */
    @Test
    @DisplayName("The stream is trimmed to the configured bound instead of growing with every publish")
    void streamIsTrimmedToTheConfiguredBound() {
        var bounded = config.toBuilder()
                .streamName("test-stream-%s".formatted(randomString().toLowerCase()))
                .streamMaxLen(1_000)
                .streamTrimLimit(100)
                .build();
        var boundedPublisher = new AnnotationQueueRoutingPublisher(redissonClient, bounded);
        int published = 5_000;

        for (int i = 0; i < published; i++) {
            boundedPublisher.enqueue(randomString(), AnnotationScope.TRACE, Set.of(idGenerator.generateId()))
                    .block();
        }

        Long size = redissonClient.getStream(bounded.getStreamName(), bounded.getCodec()).size().block();
        assertThat(size)
                .as("stream must be bounded by the configured maxLen, not grow one-to-one with publishes")
                .isNotNull()
                .isLessThan((long) published);
    }

    private FeedbackScoresCreated event(EntityType entityType, String workspaceId, UUID entityId) {
        return new FeedbackScoresCreated(Set.of(entityId), entityType, workspaceId, randomString(),
                idGenerator.generateId());
    }

    /**
     * The listener subscribes and returns, so the buffer fills on another thread; wait for it.
     */
    private void awaitBuffered(int expectedMembers) {
        Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(10))
                .pollInterval(java.time.Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(bufferSize()).isEqualTo(expectedMembers));
    }

    /**
     * Flushes until the expected number of entries is on the stream. Members become due only once they are
     * {@code bufferMinAge} old, so the first attempts may legitimately publish nothing.
     */
    private List<AnnotationQueueRoutingMessage> awaitFlushed(int expectedSize) {
        Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(10))
                .pollInterval(java.time.Duration.ofMillis(50))
                .untilAsserted(() -> {
                    bufferService.flush().block();
                    assertThat(readStream()).hasSize(expectedSize);
                });
        return readStream();
    }

    private <T> T awaitMinAge(java.util.function.Supplier<T> action) {
        Awaitility.await()
                .pollDelay(java.time.Duration.ofMillis(config.getBufferMinAge().toMilliseconds() + 50))
                .atMost(java.time.Duration.ofSeconds(10))
                .until(() -> true);
        return action.get();
    }

    /**
     * Waits before concluding, rather than reading once. The listener subscribes and returns, so an
     * immediate read would pass whether the guard held or simply had not been overtaken yet.
     */
    private void assertNothingBuffered() {
        Awaitility.await()
                .during(java.time.Duration.ofMillis(500))
                .atMost(java.time.Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(bufferSize()).isZero());
    }

    private int bufferSize() {
        Integer size = pending().size().block();
        return size == null ? 0 : size;
    }

    private RScoredSortedSetReactive<String> pending() {
        return redissonClient.getScoredSortedSet(AnnotationQueueRoutingConfig.PENDING_SET_KEY,
                StringCodec.INSTANCE);
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
