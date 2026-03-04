package com.comet.opik.domain.experiments.aggregations;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.config.Config;
import reactor.test.StepVerifier;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExperimentAggregationPublisher Tests")
class ExperimentAggregationPublisherTest {

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final String PENDING_SET_KEY = ExperimentDenormalizationConfig.PENDING_SET_KEY;
    private static final String EXPERIMENT_KEY_PREFIX = PENDING_SET_KEY + ":";

    private RedissonReactiveClient redissonClient;
    private ExperimentAggregationPublisher.ExperimentAggregationPublisherImpl publisher;
    private ExperimentDenormalizationConfig config;

    @BeforeEach
    void setUp() {
        REDIS.start();

        Config redissonConfig = new Config();
        redissonConfig.useSingleServer().setAddress(REDIS.getRedisURI());
        redissonClient = Redisson.create(redissonConfig).reactive();

        config = buildConfig(true);
        publisher = new ExperimentAggregationPublisher.ExperimentAggregationPublisherImpl(config, redissonClient);

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
    @DisplayName("When enabled with valid args, should add compound member to ZSET")
    void publish__whenEnabled__shouldAddCompoundMemberToZset() {
        var experimentId = UUID.randomUUID();
        var workspaceId = UUID.randomUUID().toString();
        var userName = "test-user";
        var expectedMember = workspaceId + ":" + experimentId;

        publisher.publish(Set.of(experimentId), workspaceId, userName);

        var index = redissonClient.getScoredSortedSet(PENDING_SET_KEY);

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> StepVerifier.create(index.contains(expectedMember))
                        .assertNext(exists -> assertThat(exists)
                                .as("ZSET should contain compound member 'workspaceId:experimentId'")
                                .isTrue())
                        .verifyComplete());
    }

    @Test
    @DisplayName("When enabled, should set a score in the future for the ZSET member")
    void publish__whenEnabled__shouldSetFutureScoreInZset() {
        var experimentId = UUID.randomUUID();
        var workspaceId = UUID.randomUUID().toString();
        var expectedMember = workspaceId + ":" + experimentId;
        long beforePublish = System.currentTimeMillis();

        publisher.publish(Set.of(experimentId), workspaceId, "user");

        var index = redissonClient.getScoredSortedSet(PENDING_SET_KEY);

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> StepVerifier.create(index.getScore(expectedMember))
                        .assertNext(score -> assertThat(score.longValue())
                                .as("ZSET score should be in the future (now + debounceDelay)")
                                .isGreaterThan(beforePublish))
                        .verifyComplete());
    }

    @Test
    @DisplayName("When enabled, should store userName in hash bucket")
    void publish__whenEnabled__shouldStoreUserNameInHash() {
        var experimentId = UUID.randomUUID();
        var workspaceId = UUID.randomUUID().toString();
        var userName = "test-user";
        var expectedMember = workspaceId + ":" + experimentId;

        publisher.publish(Set.of(experimentId), workspaceId, userName);

        var bucket = redissonClient.<String, String>getMap(EXPERIMENT_KEY_PREFIX + expectedMember);

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> StepVerifier.create(bucket.get("userName"))
                        .assertNext(stored -> assertThat(stored)
                                .as("Hash should store userName")
                                .isEqualTo(userName))
                        .verifyComplete());
    }

    @Test
    @DisplayName("When disabled, should skip publishing entirely")
    void publish__whenDisabled__shouldSkipPublish() {
        config = buildConfig(false);
        publisher = new ExperimentAggregationPublisher.ExperimentAggregationPublisherImpl(config, redissonClient);

        var experimentId = UUID.randomUUID();

        publisher.publish(Set.of(experimentId), UUID.randomUUID().toString(), "user");

        var index = redissonClient.getScoredSortedSet(PENDING_SET_KEY);

        StepVerifier.create(index.size())
                .assertNext(size -> assertThat(size)
                        .as("ZSET should be empty when disabled")
                        .isEqualTo(0))
                .verifyComplete();
    }

    @Test
    @DisplayName("When experimentIds is empty, should skip publishing")
    void publish__whenEmptyExperimentIds__shouldSkipPublish() {
        publisher.publish(Set.of(), UUID.randomUUID().toString(), "user");

        var index = redissonClient.getScoredSortedSet(PENDING_SET_KEY);

        StepVerifier.create(index.size())
                .assertNext(size -> assertThat(size)
                        .as("ZSET should be empty when no experimentIds provided")
                        .isEqualTo(0))
                .verifyComplete();
    }

    @Test
    @DisplayName("Same experimentId in different workspaces should create separate ZSET entries")
    void publish__sameExperimentIdDifferentWorkspaces__shouldCreateSeparateZsetEntries() {
        var experimentId = UUID.randomUUID();
        var workspaceId1 = UUID.randomUUID().toString();
        var workspaceId2 = UUID.randomUUID().toString();

        publisher.publish(Set.of(experimentId), workspaceId1, "user1");
        publisher.publish(Set.of(experimentId), workspaceId2, "user2");

        var member1 = workspaceId1 + ":" + experimentId;
        var member2 = workspaceId2 + ":" + experimentId;
        var index = redissonClient.getScoredSortedSet(PENDING_SET_KEY);

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> StepVerifier.create(index.size())
                        .assertNext(size -> assertThat(size)
                                .as("ZSET should have 2 entries — one per workspace")
                                .isEqualTo(2))
                        .verifyComplete());

        StepVerifier.create(index.contains(member1))
                .expectNext(true)
                .verifyComplete();

        StepVerifier.create(index.contains(member2))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("Multiple publishes for the same experiment should keep a single ZSET entry with updated score")
    void publish__multiplePublishesSameExperiment__shouldKeepSingleEntryWithUpdatedScore() {
        var experimentId = UUID.randomUUID();
        var workspaceId = UUID.randomUUID().toString();
        var member = workspaceId + ":" + experimentId;
        var index = redissonClient.getScoredSortedSet(PENDING_SET_KEY);

        publisher.publish(Set.of(experimentId), workspaceId, "user");

        // Wait for the first publish to persist before reading the score
        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> Boolean.TRUE.equals(index.contains(member).block()));

        var firstScore = index.getScore(member).block();

        publisher.publish(Set.of(experimentId), workspaceId, "user");

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    StepVerifier.create(index.size())
                            .assertNext(size -> assertThat(size)
                                    .as("ZSET should still have exactly 1 entry after second publish")
                                    .isEqualTo(1))
                            .verifyComplete();

                    StepVerifier.create(index.getScore(member))
                            .assertNext(secondScore -> assertThat(secondScore)
                                    .as("Score should be >= first score after second publish")
                                    .isGreaterThanOrEqualTo(firstScore))
                            .verifyComplete();
                });
    }

    @Test
    @DisplayName("When enabled, should set TTL on hash bucket approximately equal to 2x debounceDelay")
    void publish__whenEnabled__shouldSetTtlOnHashBucket() {
        var experimentId = UUID.randomUUID();
        var workspaceId = UUID.randomUUID().toString();
        var member = workspaceId + ":" + experimentId;
        long expectedMaxTtlMs = config.getDebounceDelay().toMilliseconds() * 2;

        publisher.publish(Set.of(experimentId), workspaceId, "user");

        var bucket = redissonClient.getMap(EXPERIMENT_KEY_PREFIX + member);

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> StepVerifier.create(bucket.remainTimeToLive())
                        .assertNext(ttl -> assertThat(ttl)
                                .as("Hash bucket TTL should be set and positive")
                                .isGreaterThan(0L)
                                .as("Hash bucket TTL should not exceed 2x debounceDelay")
                                .isLessThanOrEqualTo(expectedMaxTtlMs))
                        .verifyComplete());
    }

    private static ExperimentDenormalizationConfig buildConfig(boolean enabled) {
        var config = new ExperimentDenormalizationConfig();
        config.setEnabled(enabled);
        config.setStreamName("experiment_denormalization_stream");
        config.setConsumerGroupName("experiment_denormalization");
        config.setConsumerBatchSize(100);
        config.setPoolingInterval(Duration.milliseconds(500));
        config.setLongPollingDuration(Duration.seconds(5));
        config.setDebounceDelay(Duration.seconds(2));
        config.setJobLockTime(Duration.seconds(4));
        config.setJobLockWaitTime(Duration.milliseconds(300));
        config.setAggregationLockTime(Duration.seconds(120));
        config.setClaimIntervalRatio(10);
        config.setPendingMessageDuration(Duration.minutes(10));
        config.setMaxRetries(3);
        return config;
    }
}
