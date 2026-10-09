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
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
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
    }

    @Test
    @DisplayName("should not mark an experiment cancelled in another workspace")
    void cancelIsScopedToItsWorkspace() {
        var experimentId = UUID.randomUUID();

        service.cancel(WORKSPACE_ID, List.of(experimentId)).block();

        assertThat(service.isCancelled("other-workspace", experimentId).block()).isFalse();
    }

    // XDEL takes an entry out of the stream without telling the consumer holding it, so purging one
    // mid provider call would count work that is still running and settle the run early.
    @Test
    @DisplayName("should leave the entries a consumer is already holding, and not count them")
    void purgeQueuedSkipsInFlightEntries() {
        var experimentId = UUID.randomUUID();
        publish(messages(experimentId, 5));

        var inFlight = deliverWithoutAck(2);

        var drained = service.purgeQueued(WORKSPACE_ID, experimentId).block();

        assertThat(drained)
                .as("two are still with a consumer, so the run has not stopped producing")
                .isFalse();
        assertThat(itemCounter(experimentId).get().block())
                .as("only the three nobody had taken are accounted for here; the other two count"
                        + " themselves down when their calls return")
                .isEqualTo(2L);
        assertThat(remainingStreamIds())
                .as("the entries a consumer holds are left in the stream")
                .containsExactlyInAnyOrderElementsOf(inFlight);
    }

    @Test
    @DisplayName("should not claim a drain when every entry is already with a consumer")
    void purgeQueuedWithEverythingInFlight() {
        var experimentId = UUID.randomUUID();
        publish(messages(experimentId, 3));

        deliverWithoutAck(3);

        var drained = service.purgeQueued(WORKSPACE_ID, experimentId).block();

        assertThat(drained).isFalse();
        assertThat(itemCounter(experimentId).get().block())
                .as("nothing was purged, so every item is still owed a decrement")
                .isEqualTo(3L);
    }

    // Both the consumer draining the last item and a cancel emptying the queue reach the finish, and
    // a counter taken past zero would otherwise re-run it for every item that lands afterwards.
    @Test
    @DisplayName("should hand the finish to the first caller only")
    void claimFinishIsTakenOnce() {
        var experimentId = UUID.randomUUID();

        assertThat(service.claimFinish(WORKSPACE_ID, experimentId).block()).isTrue();
        assertThat(service.claimFinish(WORKSPACE_ID, experimentId).block()).isFalse();
    }

    @Test
    @DisplayName("should let each experiment be finished on its own")
    void claimFinishIsPerExperiment() {
        var experimentId = UUID.randomUUID();
        var sibling = UUID.randomUUID();

        service.claimFinish(WORKSPACE_ID, experimentId).block();

        assertThat(service.claimFinish(WORKSPACE_ID, sibling).block()).isTrue();
    }

    // Released only for the one that could not be written: a release reaching a sibling, or the same
    // id in another workspace, would open a finish that something is still holding.
    @Test
    @DisplayName("should release the finish of one experiment without touching its neighbours")
    void releaseFinishIsScopedToItsOwnKey() {
        var experimentId = UUID.randomUUID();
        var sibling = UUID.randomUUID();

        service.claimFinish(WORKSPACE_ID, experimentId).block();
        service.claimFinish(WORKSPACE_ID, sibling).block();
        service.claimFinish("other-workspace", experimentId).block();

        assertThat(service.releaseFinish(WORKSPACE_ID, experimentId).block()).isTrue();

        assertThat(service.isFinishClaimed(WORKSPACE_ID, experimentId).block()).isFalse();
        assertThat(service.isFinishClaimed(WORKSPACE_ID, sibling).block()).isTrue();
        assertThat(service.isFinishClaimed("other-workspace", experimentId).block()).isTrue();
    }

    // The release runs from a failure handler, which a retry can reach more than once.
    @Test
    @DisplayName("should let the finish be claimed again, and released again, after a release")
    void releaseFinishReopensTheClaim() {
        var experimentId = UUID.randomUUID();

        service.claimFinish(WORKSPACE_ID, experimentId).block();
        service.releaseFinish(WORKSPACE_ID, experimentId).block();

        assertThat(service.claimFinish(WORKSPACE_ID, experimentId).block())
                .as("nothing holds it any more, so the next caller takes it")
                .isTrue();
        assertThat(service.releaseFinish(WORKSPACE_ID, experimentId).block()).isTrue();
        assertThat(service.releaseFinish(WORKSPACE_ID, experimentId).block())
                .as("already gone, so there is nothing to remove")
                .isFalse();
    }

    /**
     * Reads entries through the consumer group without acking, which is what leaves them in the PEL —
     * the state an item sits in while its provider call is running.
     */
    private List<StreamMessageId> deliverWithoutAck(int count) {
        RStreamReactive<String, ExperimentItemToProcess> stream = redissonClient
                .getStream(config.getStreamName(), config.getCodec());

        stream.createGroup(StreamCreateGroupArgs.name(config.getConsumerGroupName())
                .id(StreamMessageId.ALL)).block();

        var delivered = stream.readGroup(config.getConsumerGroupName(), "test-consumer",
                StreamReadGroupArgs.neverDelivered().count(count)).block();

        return delivered == null ? List.of() : List.copyOf(delivered.keySet());
    }

    private List<StreamMessageId> remainingStreamIds() {
        RStreamReactive<String, ExperimentItemToProcess> stream = redissonClient
                .getStream(config.getStreamName(), config.getCodec());
        var entries = stream.range(StreamMessageId.MIN, StreamMessageId.MAX).block();

        return entries == null ? List.of() : List.copyOf(entries.keySet());
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
