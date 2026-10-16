package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.domain.ExperimentCancellationService;
import com.comet.opik.domain.ExperimentItemLedger;
import com.comet.opik.domain.ExperimentItemLedger.Outcome;
import com.comet.opik.domain.ExperimentItemProcessor;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.fasterxml.jackson.databind.node.TextNode;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExperimentItemProcessingSubscriber Test")
class ExperimentItemProcessingSubscriberTest {

    private static final String WORKSPACE_ID = "test-workspace";
    private static final StreamMessageId ENTRY = new StreamMessageId(1760612345678L, 0);

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private ExperimentItemLedger itemLedger;

    @Mock
    private ExperimentItemProcessor itemProcessor;

    @Mock
    private ExperimentService experimentService;

    @Mock
    private TestSuiteAssertionCounterService testSuiteAssertionCounterService;

    @Mock
    private ExperimentCancellationService cancellationService;

    private ExperimentItemProcessingSubscriber subscriber;

    @BeforeEach
    void setUp() {
        var config = new ExperimentExecutionConfig();
        config.setBatchCounterTtl(Duration.hours(24));

        subscriber = new ExperimentItemProcessingSubscriber(config, redisClient, itemProcessor,
                experimentService, testSuiteAssertionCounterService, cancellationService, itemLedger);

        // A first delivery: nothing has handled this entry yet.
        lenient().when(itemLedger.outcome(any(UUID.class), any(StreamMessageId.class))).thenReturn(Mono.empty());
        lenient().when(itemLedger.record(any(UUID.class), any(StreamMessageId.class), any(Outcome.class)))
                .thenReturn(Mono.just(true));
        // One item left of a larger batch: the batch does not drain, so the finish path stays out of
        // these tests and they describe the skip alone.
        lenient().when(itemLedger.remaining(any(UUID.class))).thenReturn(Mono.just(5L));
        lenient().when(itemLedger.failures(any(UUID.class))).thenReturn(Mono.just(0L));
        lenient().when(itemLedger.expireAfterDrain(any(UUID.class))).thenReturn(Mono.empty());
        // The drain hands the finish to one caller; these tests are always that caller.
        lenient().when(cancellationService.claimFinish(anyString(), any(UUID.class)))
                .thenReturn(Mono.just(true));
        lenient().when(cancellationService.discardQueued(anyString(), any(UUID.class)))
                .thenReturn(Mono.empty());
    }

    private ExperimentItemToProcess buildMessage(UUID experimentId) {
        return buildMessage(experimentId, List.of(experimentId));
    }

    private ExperimentItemToProcess buildMessage(UUID experimentId, List<UUID> allExperimentIds) {
        return ExperimentItemToProcess.builder()
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
                .allExperimentIds(allExperimentIds)
                .testSuite(false)
                .build();
    }

    @Test
    @DisplayName("should not call the provider for an item of a cancelled experiment")
    void skipCancelledItem() {
        var experimentId = UUID.randomUUID();
        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(true));

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(itemProcessor, never()).process(any());
    }

    @Test
    @DisplayName("should still count a skipped item so its batch can drain")
    void countSkippedItem() {
        var experimentId = UUID.randomUUID();
        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(true));

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        // Left uncounted, a cancelled run's batch never reaches zero and its experiments hang.
        verify(itemLedger).record(experimentId, ENTRY, Outcome.SKIPPED);
    }

    @Test
    @DisplayName("should process an item normally when the experiment is not cancelled")
    void processUncancelledItem() {
        var experimentId = UUID.randomUUID();
        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(itemProcessor).process(any());
    }

    @Test
    @DisplayName("should fail only the experiment whose item failed, not its siblings")
    void failOnlyTheOwningExperiment() {
        var failing = UUID.randomUUID();
        var sibling = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, failing)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.error(new RuntimeException("persistence is down")));
        when(itemLedger.remaining(failing)).thenReturn(Mono.just(0L));
        when(itemLedger.failures(failing)).thenReturn(Mono.just(1L));
        when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(failing, List.of(failing, sibling)), ENTRY).block();

        verify(experimentService).update(eq(failing), argThat(
                update -> update.status() == ExperimentStatus.FAILED));
        verify(experimentService, never()).update(eq(sibling), any());
    }

    // Stop pressed on a run that had already seen a row fail: the earlier failure is still counted
    // when the last item drains. The stop is why the run ended, so it stays CANCELLED — FAILED is for
    // a run that ended on its own with faults.
    @Test
    @DisplayName("should keep a cancelled experiment cancelled when an earlier item had failed")
    void cancelledExperimentIsNotRelabelledFailed() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(true));
        // The stop reached the finish first and owns the outcome, so this loses the claim for it.
        when(cancellationService.claimFinish(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        // The cancelled item is skipped rather than run, and draining it is what reaches the finish.
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(itemLedger.failures(experimentId)).thenReturn(Mono.just(1L));
        when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        var captor = ArgumentCaptor.forClass(ExperimentUpdate.class);
        verify(experimentService).update(eq(experimentId), captor.capture());
        assertThat(captor.getValue().status())
                .as("the stop it was given stands, whatever its items did")
                .isNull();
        assertThat(captor.getValue().finished())
                .as("it still drained, which is what stops the page waiting on it")
                .isTrue();
    }

    @Test
    @DisplayName("should complete a drained run that nobody stopped and had no failures")
    void finishADrainedRunAsCompleted() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.empty());
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());
        when(experimentService.finishExperiments(any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        var captor = ArgumentCaptor.forClass(ExperimentUpdate.class);
        verify(experimentService).update(eq(experimentId), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ExperimentStatus.COMPLETED);
        assertThat(captor.getValue().finished()).isTrue();
        verify(experimentService).finishExperiments(Set.of(experimentId));
    }

    // Aggregates describe a run that ran. A stopped one did not, so building them would report
    // totals over whichever rows happened to land before the stop.
    @Test
    @DisplayName("should not aggregate a drained run that was stopped")
    void skipAggregationForAStoppedRun() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(true));
        when(cancellationService.claimFinish(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        var captor = ArgumentCaptor.forClass(ExperimentUpdate.class);
        verify(experimentService).update(eq(experimentId), captor.capture());
        assertThat(captor.getValue().status())
                .as("the stop it was given stands")
                .isNull();
        assertThat(captor.getValue().finished()).isTrue();
        verify(experimentService, never()).finishExperiments(any());
    }

    // The claim is taken when the counter drains and held until a terminal state is written. If that
    // write and its fallback both fail there is no item left to drain and try again, so holding it
    // would only keep a stop from reaching a run that still reads as running.
    @Test
    @DisplayName("should hand the finish back when no terminal state could be written")
    void releaseTheFinishWhenEveryTerminalWriteFails() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.empty());
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(experimentService.update(any(UUID.class), any()))
                .thenReturn(Mono.error(new IllegalStateException("database is down")));
        when(cancellationService.releaseFinish(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(true));

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(cancellationService).releaseFinish(WORKSPACE_ID, experimentId);
    }

    // A run whose status landed and whose aggregates did not has still completed. FAILED would
    // describe it less accurately, and could not be written anyway from inside the held claim.
    @Test
    @DisplayName("should stay completed when only the aggregates fail")
    void keepCompletedWhenOnlyTheAggregatesFail() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.empty());
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());
        when(experimentService.finishExperiments(any()))
                .thenReturn(Mono.error(new IllegalStateException("aggregation is down")));

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        var update = ArgumentCaptor.forClass(ExperimentUpdate.class);
        verify(experimentService).update(eq(experimentId), update.capture());
        assertThat(update.getValue().status()).isEqualTo(ExperimentStatus.COMPLETED);
        verify(cancellationService, never()).releaseFinish(any(), any());
    }

    // setIfAbsent refuses the holder a second time, so a terminal status written from inside the
    // claim lands as nothing. A failed finish hands the claim back instead.
    @Test
    @DisplayName("should hand the finish back rather than write a status it cannot claim")
    void releaseRatherThanReclaimWhenTheStatusWriteFails() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.empty());
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        // What Redis actually answers: won once, refused every time after.
        when(cancellationService.claimFinish(WORKSPACE_ID, experimentId))
                .thenReturn(Mono.just(true))
                .thenReturn(Mono.just(false));
        when(experimentService.update(any(UUID.class), any()))
                .thenReturn(Mono.error(new IllegalStateException("database is down")));
        when(cancellationService.releaseFinish(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(true));

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(cancellationService).releaseFinish(WORKSPACE_ID, experimentId);
    }

    @Test
    @DisplayName("should hand the finish back when a test suite cannot be stamped")
    void releaseTheFinishWhenTheTestSuiteStampFails() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(true));
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(experimentService.update(any(UUID.class), any()))
                .thenReturn(Mono.error(new IllegalStateException("database is down")));
        when(cancellationService.releaseFinish(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(true));

        assertThatThrownBy(() -> subscriber
                .processEvent(buildMessage(experimentId).toBuilder().testSuite(true).build(), ENTRY)
                .block())
                .hasMessage("database is down");

        verify(cancellationService).releaseFinish(WORKSPACE_ID, experimentId);
    }

    // Held is the correct state once something has been written: it is what stops a second actor
    // reaching the same finish and relabelling a run that already has its outcome.
    @Test
    @DisplayName("should keep the finish when the run settles normally")
    void keepTheFinishWhenTheRunSettles() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.empty());
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());
        when(experimentService.finishExperiments(any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(cancellationService, never()).releaseFinish(any(), any());
    }

    // Only a stop deleted them, so a run that simply finished left one id per item behind for the
    // whole of its TTL — on a large dataset, the entire run's worth, for a day, for nothing.
    @Test
    @DisplayName("should forget what it queued once the run has drained")
    void discardQueuedIdsOnDrain() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.empty());
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());
        when(experimentService.finishExperiments(any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(cancellationService).discardQueued(WORKSPACE_ID, experimentId);
    }

    @Test
    @DisplayName("should keep what it queued while items are still to come")
    void keepQueuedIdsWhileItemsRemain() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.empty());

        // The default: one of a larger batch, so a stop still has items to purge.
        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(cancellationService, never()).discardQueued(any(), any());
    }

    // A cancelled test suite never reaches its assertions, so stamping only there would leave its
    // rows looking forever like a run still in progress.
    @Test
    @DisplayName("should record a drained test suite as finished while its assertions are still pending")
    void stampTestSuiteOnItemDrain() {
        var experimentId = UUID.randomUUID();

        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(true));
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId).toBuilder().testSuite(true).build(), ENTRY).block();

        var captor = ArgumentCaptor.forClass(ExperimentUpdate.class);
        verify(experimentService).update(eq(experimentId), captor.capture());
        assertThat(captor.getValue().finished()).isTrue();
        assertThat(captor.getValue().status())
                .as("the status still waits for the assertions")
                .isNull();
    }

    // Redis hands out a message again whenever its acknowledgement is lost, after the provider call
    // and the trace write have already happened. Running it again pays for the call twice and writes
    // a second trace for one item.
    @Test
    @DisplayName("should not call the provider again for an entry that already ran")
    void redeliveredEntryDoesNotRunAgain() {
        var experimentId = UUID.randomUUID();
        when(itemLedger.outcome(experimentId, ENTRY)).thenReturn(Mono.just(Outcome.OK));

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(itemProcessor, never()).process(any());
        verify(itemLedger, never()).record(any(), any(), any());
    }

    // The first delivery may have recorded its outcome and then lost its way before the finish, which
    // nothing else would ever reach for a run with no item left to come.
    @Test
    @DisplayName("should still finish a drained run when the last entry is delivered again")
    void redeliveredLastEntryStillFinishesTheRun() {
        var experimentId = UUID.randomUUID();
        when(itemLedger.outcome(experimentId, ENTRY)).thenReturn(Mono.just(Outcome.OK));
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());
        when(experimentService.finishExperiments(any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(itemProcessor, never()).process(any());
        verify(experimentService).update(eq(experimentId), argThat(
                update -> update.status() == ExperimentStatus.COMPLETED));
    }

    @Test
    @DisplayName("should record the outcome of an item against its stream entry")
    void recordOutcomeAgainstTheEntry() {
        var experimentId = UUID.randomUUID();
        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(itemLedger).record(experimentId, ENTRY, Outcome.OK);
    }

    // A failed item yields no assertions, so the suite's assertion counter is taken down for it once.
    @Test
    @DisplayName("should take a failed test-suite item off the assertion counter")
    void failedTestSuiteItemReleasesItsAssertions() {
        var experimentId = UUID.randomUUID();
        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.error(new RuntimeException("persistence is down")));
        when(testSuiteAssertionCounterService.decrementAndFinishIfComplete(WORKSPACE_ID, experimentId))
                .thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId).toBuilder().testSuite(true).build(), ENTRY).block();

        verify(testSuiteAssertionCounterService).decrementAndFinishIfComplete(WORKSPACE_ID, experimentId);
    }

    // Another delivery of the same entry recorded first. It already took the item off the assertion
    // counter, and doing it again finishes the suite while assertions are still to land.
    @Test
    @DisplayName("should not take a failed item off the assertion counter twice")
    void failedItemRecordedElsewhereLeavesTheAssertionCounter() {
        var experimentId = UUID.randomUUID();
        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.error(new RuntimeException("persistence is down")));
        when(itemLedger.record(experimentId, ENTRY, Outcome.FAILED)).thenReturn(Mono.just(false));

        subscriber.processEvent(buildMessage(experimentId).toBuilder().testSuite(true).build(), ENTRY).block();

        verify(testSuiteAssertionCounterService, never()).decrementAndFinishIfComplete(any(), any());
    }

    @Test
    @DisplayName("should shorten the outcomes of a run once it has drained")
    void expireOutcomesOnDrain() {
        var experimentId = UUID.randomUUID();
        when(cancellationService.isCancelled(WORKSPACE_ID, experimentId)).thenReturn(Mono.just(false));
        when(itemProcessor.process(any())).thenReturn(Mono.empty());
        when(itemLedger.remaining(experimentId)).thenReturn(Mono.just(0L));
        when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());
        when(experimentService.finishExperiments(any())).thenReturn(Mono.empty());

        subscriber.processEvent(buildMessage(experimentId), ENTRY).block();

        verify(itemLedger).expireAfterDrain(experimentId);
    }
}
