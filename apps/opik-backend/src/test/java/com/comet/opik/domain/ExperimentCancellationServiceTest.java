package com.comet.opik.domain;

import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.fasterxml.jackson.databind.node.TextNode;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RAtomicLongReactive;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The cancellation purge against a real Redis.
 *
 * Publisher and cancellation service are driven together: the recording and the purge are one contract,
 * and testing either alone would pin the format without checking the other end agrees on it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ExperimentCancellationService Test")
class ExperimentCancellationServiceTest {

    private static final String WORKSPACE_ID = "test-workspace";

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();

    private RedissonReactiveClient redissonClient;
    private ExperimentExecutionConfig config;
    private ExperimentItemPublisher publisher;
    private ExperimentCancellationService service;

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
     * A stream per test, so one test's entries can never be counted by another. Experiment ids are random,
     * which keeps the queued-id and cancellation keys distinct too.
     */
    @BeforeEach
    void setUp() {
        config = new ExperimentExecutionConfig();
        config.setStreamName("test-stream-%s".formatted(
                RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase()));

        service = new ExperimentCancellationService(redissonClient, config);
        publisher = new ExperimentItemPublisher(redissonClient, config,
                mock(TestSuiteAssertionCounterService.class), service);
    }

    @Test
    @DisplayName("should remove the cancelled experiment's queued messages and leave the others")
    void purgeQueuedRemovesOnlyTheCancelledExperiment() {
        var cancelled = UUID.randomUUID();
        var sibling = UUID.randomUUID();

        publish(messages(cancelled, 3), messages(sibling, 2));

        var drained = service.purgeQueued(WORKSPACE_ID, cancelled).block();

        assertThat(drained).isTrue();
        assertThat(remainingExperimentIds()).containsOnly(sibling);
    }

    // Each prompt variant of a run counts its own work. Cancelling one used to strand a counter the
    // others shared, so a variant nobody stopped could never be reported finished.
    @Test
    @DisplayName("should leave a sibling variant's counter untouched when one is cancelled")
    void cancellingOneVariantLeavesItsSiblingAlone() {
        var cancelled = UUID.randomUUID();
        var sibling = UUID.randomUUID();
        var batchId = UUID.randomUUID();

        var batch = new ArrayList<>(messages(cancelled, 3));
        batch.addAll(messages(sibling, 2));
        publisher.publish(batchId, batch, false).block();

        assertThat(itemCounter(cancelled).get().block()).isEqualTo(3L);
        assertThat(itemCounter(sibling).get().block()).isEqualTo(2L);

        var drained = service.purgeQueued(WORKSPACE_ID, cancelled).block();

        assertThat(drained)
                .as("nothing of the cancelled variant is left with a consumer, so it has stopped producing")
                .isTrue();
        assertThat(itemCounter(cancelled).get().block())
                .as("its own counter is settled, which is what records it as finished")
                .isZero();
        assertThat(itemCounter(sibling).get().block())
                .as("the sibling still has its own two to drain, and nothing else to wait for")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("should not claim a drain for an experiment that published nothing")
    void purgeQueuedWithoutRecordedIds() {
        var drained = service.purgeQueued(WORKSPACE_ID, UUID.randomUUID()).block();

        assertThat(drained)
                .as("nothing recorded means nothing established, not that the run is over")
                .isFalse();
    }

    @Test
    @DisplayName("should not claim a drain when purging the same experiment twice")
    void purgeQueuedTwice() {
        var experimentId = UUID.randomUUID();
        publish(messages(experimentId, 2));

        service.purgeQueued(WORKSPACE_ID, experimentId).block();
        var drained = service.purgeQueued(WORKSPACE_ID, experimentId).block();

        assertThat(drained).isFalse();
    }

    @Test
    @DisplayName("should mark a cancelled experiment and leave the others unmarked")
    void cancelMarksOnlyTheGivenExperiments() {
        var cancelled = UUID.randomUUID();
        var untouched = UUID.randomUUID();

        service.cancel(WORKSPACE_ID, List.of(cancelled)).block();

        assertThat(service.isCancelled(WORKSPACE_ID, cancelled).block()).isTrue();
        assertThat(service.isCancelled(WORKSPACE_ID, untouched).block()).isFalse();
        assertThat(service.filterNotCancelled(WORKSPACE_ID, List.of(cancelled, untouched)).block())
                .containsOnly(untouched);
    }

    @Test
    @DisplayName("should not mark an experiment cancelled in another workspace")
    void cancelIsScopedToItsWorkspace() {
        var experimentId = UUID.randomUUID();

        service.cancel(WORKSPACE_ID, List.of(experimentId)).block();

        assertThat(service.isCancelled("other-workspace", experimentId).block()).isFalse();
    }

    @SafeVarargs
    private void publish(List<ExperimentItemToProcess>... batches) {
        for (var batch : batches) {
            publisher.publish(UUID.randomUUID(), batch, false).block();
        }
    }

    private RAtomicLongReactive itemCounter(UUID experimentId) {
        return redissonClient.getAtomicLong(ExperimentExecutionConfig.itemCounterKey(experimentId));
    }

    private List<ExperimentItemToProcess> messages(UUID experimentId, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> ExperimentItemToProcess.builder()
                        .batchId(UUID.randomUUID())
                        .prompt(ExperimentExecutionRequest.PromptVariant.builder()
                                .model("gpt-4o")
                                .messages(List.of(ExperimentExecutionRequest.PromptVariant.Message.builder()
                                        .role("user")
                                        .content(new TextNode("Hello"))
                                        .build()))
                                .build())
                        .datasetItemId(UUID.randomUUID())
                        .experimentId(experimentId)
                        .datasetId(UUID.randomUUID())
                        .projectName("project")
                        .workspaceId(WORKSPACE_ID)
                        .userName("user")
                        .allExperimentIds(List.of(experimentId))
                        .testSuite(false)
                        .build())
                .toList();
    }

    private List<UUID> remainingExperimentIds() {
        RStreamReactive<String, ExperimentItemToProcess> stream = redissonClient
                .getStream(config.getStreamName(), config.getCodec());
        var entries = stream.range(StreamMessageId.MIN, StreamMessageId.MAX).block();

        return entries == null
                ? List.of()
                : entries.values().stream()
                        .map(entry -> entry.get(ExperimentExecutionConfig.PAYLOAD_FIELD).experimentId())
                        .toList();
    }
}
