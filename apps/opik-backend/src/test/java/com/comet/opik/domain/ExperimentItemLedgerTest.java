package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.domain.ExperimentItemLedger.Outcome;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ExperimentItemLedger Test")
class ExperimentItemLedgerTest {

    private static final String WORKSPACE_ID = "test-workspace";

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final AtomicLong nextEntry = new AtomicLong(1);

    private RedissonReactiveClient redissonClient;
    private ExperimentExecutionConfig config;
    private ExperimentItemLedger ledger;

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

    @BeforeEach
    void setUp() {
        config = new ExperimentExecutionConfig();
        ledger = new ExperimentItemLedger(redissonClient, config);
    }

    @Test
    @DisplayName("should have no outcome for an entry nothing has handled")
    void noOutcomeForAnUnhandledEntry() {
        assertThat(ledger.outcome(WORKSPACE_ID, UUID.randomUUID(), entry()).block()).isNull();
    }

    // A redelivery records the same entry again. Taking the second outcome would let a retry that
    // skipped the provider overwrite what the call that ran it actually reported.
    @Test
    @DisplayName("should keep the first outcome recorded for an entry")
    void firstOutcomeWins() {
        var experimentId = UUID.randomUUID();
        var entry = entry();

        ledger.record(WORKSPACE_ID, experimentId, entry, Outcome.FAILED).block();
        ledger.record(WORKSPACE_ID, experimentId, entry, Outcome.OK).block();

        assertThat(ledger.outcome(WORKSPACE_ID, experimentId, entry).block()).isEqualTo(Outcome.FAILED);
    }

    @Test
    @DisplayName("should count an item once however many times it is delivered")
    void recordingAgainDoesNotCountAgain() {
        var experimentId = UUID.randomUUID();
        setTotal(experimentId, 3);
        var entry = entry();

        ledger.record(WORKSPACE_ID, experimentId, entry, Outcome.OK).block();
        ledger.record(WORKSPACE_ID, experimentId, entry, Outcome.OK).block();
        ledger.record(WORKSPACE_ID, experimentId, entry, Outcome.OK).block();

        assertThat(ledger.remaining(WORKSPACE_ID, experimentId).block())
                .as("three deliveries of one item leave the other two still to come")
                .isEqualTo(2L);
    }

    // A dataset that runs each item several times queues the same dataset item more than once. Each of
    // those is a separate entry, so each is its own piece of work.
    @Test
    @DisplayName("should count every entry of a run that repeats its items")
    void everyEntryCounts() {
        var experimentId = UUID.randomUUID();
        setTotal(experimentId, 2);

        ledger.record(WORKSPACE_ID, experimentId, entry(), Outcome.OK).block();
        ledger.record(WORKSPACE_ID, experimentId, entry(), Outcome.OK).block();

        assertThat(ledger.remaining(WORKSPACE_ID, experimentId).block()).isZero();
    }

    @Test
    @DisplayName("should report whether the run's total still exists")
    void hasTotalReflectsTheTotalKey() {
        var experimentId = UUID.randomUUID();
        assertThat(ledger.hasTotal(WORKSPACE_ID, experimentId).block()).isFalse();

        setTotal(experimentId, 3);

        assertThat(ledger.hasTotal(WORKSPACE_ID, experimentId).block()).isTrue();
    }

    @Test
    @DisplayName("should read a run whose total is gone as drained")
    void missingTotalReadsAsDrained() {
        assertThat(ledger.remaining(WORKSPACE_ID, UUID.randomUUID()).block()).isZero();
    }

    // A stop deletes queued entries and a consumer may take one of them just before. Whichever records
    // it first decides how it is counted, and it is counted once.
    @Test
    @DisplayName("should not overwrite what a consumer recorded when a stop skips the same entry")
    void skippingLeavesRecordedOutcomesAlone() {
        var experimentId = UUID.randomUUID();
        setTotal(experimentId, 3);
        var ranFirst = entry();
        ledger.record(WORKSPACE_ID, experimentId, ranFirst, Outcome.OK).block();

        ledger.recordSkipped(WORKSPACE_ID, experimentId, List.of(ranFirst, entry(), entry())).block();

        assertThat(ledger.outcome(WORKSPACE_ID, experimentId, ranFirst).block()).isEqualTo(Outcome.OK);
        assertThat(ledger.remaining(WORKSPACE_ID, experimentId).block()).isZero();
    }

    @Test
    @DisplayName("should record a skip for every entry of a purge spanning several chunks")
    void skippingCoversEntriesBeyondASingleChunk() {
        var experimentId = UUID.randomUUID();
        var entries = IntStream.range(0, 1_200).mapToObj(i -> entry()).toList();
        setTotal(experimentId, entries.size());

        ledger.recordSkipped(WORKSPACE_ID, experimentId, entries).block();

        assertThat(ledger.remaining(WORKSPACE_ID, experimentId).block()).isZero();
        assertThat(ttl(experimentId))
                .as("expired once for the whole purge, so the outcomes do not outlive their run")
                .isPositive();
    }

    @Test
    @DisplayName("should count only failed outcomes as failures")
    void failuresCountsFailedOnly() {
        var experimentId = UUID.randomUUID();

        ledger.record(WORKSPACE_ID, experimentId, entry(), Outcome.FAILED).block();
        ledger.record(WORKSPACE_ID, experimentId, entry(), Outcome.OK).block();
        ledger.record(WORKSPACE_ID, experimentId, entry(), Outcome.SKIPPED).block();
        ledger.record(WORKSPACE_ID, experimentId, entry(), Outcome.FAILED).block();

        assertThat(ledger.failures(WORKSPACE_ID, experimentId).block()).isEqualTo(2L);
    }

    @Test
    @DisplayName("should keep each experiment's outcomes apart")
    void outcomesArePerExperiment() {
        var experimentId = UUID.randomUUID();
        var sibling = UUID.randomUUID();
        setTotal(sibling, 1);
        var entry = entry();

        ledger.record(WORKSPACE_ID, experimentId, entry, Outcome.FAILED).block();

        assertThat(ledger.outcome(WORKSPACE_ID, sibling, entry).block()).isNull();
        assertThat(ledger.remaining(WORKSPACE_ID, sibling).block()).isEqualTo(1L);
        assertThat(ledger.failures(WORKSPACE_ID, sibling).block()).isZero();
    }

    // A success whose acknowledgement is lost is redelivered with no bound from maxRetries, so the
    // outcomes are kept for the run's full TTL rather than a guessed redelivery window.
    @Test
    @DisplayName("should keep outcomes for the full TTL")
    void outcomesAreKeptForTheFullTtl() {
        var experimentId = UUID.randomUUID();
        ledger.record(WORKSPACE_ID, experimentId, entry(), Outcome.OK).block();

        assertThat(ttl(experimentId))
                .isGreaterThan(config.getBatchCounterTtl().toJavaDuration().minusHours(1))
                .isLessThanOrEqualTo(config.getBatchCounterTtl().toJavaDuration());
    }

    // Once drained, theoutcomes only have to outlast the gap to the next redelivery, which sets
    // the window again.
    @Test
    @DisplayName("should keep a drained run's outcomes only for a few redelivery periods")
    void drainShortensTheOutcomes() {
        var experimentId = UUID.randomUUID();
        ledger.record(WORKSPACE_ID, experimentId, entry(), Outcome.OK).block();

        ledger.expireAfterDrain(WORKSPACE_ID, experimentId).block();

        assertThat(ttl(experimentId))
                .isPositive()
                .isLessThanOrEqualTo(config.getPendingMessageDuration().toJavaDuration().multipliedBy(6));
    }

    @Test
    @DisplayName("should not stretch a drained run's outcomes back to a day when a late item is recorded")
    void lateRecordKeepsTheShortenedExpiry() {
        var experimentId = UUID.randomUUID();
        ledger.record(WORKSPACE_ID, experimentId, entry(), Outcome.OK).block();
        ledger.expireAfterDrain(WORKSPACE_ID, experimentId).block();

        ledger.record(WORKSPACE_ID, experimentId, entry(), Outcome.OK).block();

        assertThat(ttl(experimentId))
                .isPositive()
                .isLessThanOrEqualTo(config.getPendingMessageDuration().toJavaDuration().multipliedBy(6));
    }

    private StreamMessageId entry() {
        return new StreamMessageId(System.currentTimeMillis(), nextEntry.getAndIncrement());
    }

    private void setTotal(UUID experimentId, long total) {
        redissonClient.getAtomicLong(ExperimentExecutionConfig.itemCounterKey(WORKSPACE_ID, experimentId)).set(total)
                .block();
    }

    private Duration ttl(UUID experimentId) {
        var millis = redissonClient.getMap(ExperimentExecutionConfig.itemEntriesKey(WORKSPACE_ID, experimentId))
                .remainTimeToLive()
                .block();
        return Duration.ofMillis(millis);
    }
}
