package com.comet.opik.domain.evaluators;

import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.OnlineScoringStatus;
import com.comet.opik.api.evaluators.OnlineScoringStatus.Reason;
import com.comet.opik.api.evaluators.OnlineScoringStatus.State;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.llm.LlmProviderKeyNotConfiguredException;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ServerErrorException;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.redisson.Redisson;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnlineScoringStatusServiceTest {

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private RedissonReactiveClient redisClient;

    @BeforeAll
    void setUpAll() {
        redis.start();
        var redissonConfig = new Config();
        redissonConfig.useSingleServer().setAddress(redis.getRedisURI());
        redisClient = Redisson.create(redissonConfig).reactive();
    }

    @AfterAll
    void tearDownAll() {
        redisClient.shutdown();
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new LlmProviderKeyNotConfiguredException(randomMessage()), Reason.NOT_CONFIGURED),
                Arguments.of(new ClientErrorException(randomMessage(), 401), Reason.AUTHENTICATION),
                Arguments.of(new ClientErrorException(randomMessage(), 403), Reason.AUTHENTICATION),
                Arguments.of(new ClientErrorException(randomMessage(), 404), Reason.NOT_FOUND),
                Arguments.of(new ClientErrorException(randomMessage(), 400), Reason.REJECTED),
                Arguments.of(new ClientErrorException(randomMessage(), 408), Reason.TIMEOUT),
                Arguments.of(new ClientErrorException(randomMessage(), 429), Reason.RATE_LIMITED),
                Arguments.of(new ServerErrorException(randomMessage(), 503), Reason.SERVER_ERROR),
                Arguments.of(new ServerErrorException(randomMessage(), 502), Reason.UNREACHABLE),
                Arguments.of(new ServerErrorException(randomMessage(), 504), Reason.TIMEOUT),
                Arguments.of(new ServerErrorException(randomMessage(), 500), Reason.SERVER_ERROR),
                Arguments.of(new RuntimeException(randomMessage()), Reason.OTHER));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("failures")
    void reasonOfNamesTheCauseOfAFailedAttempt(Throwable failure, Reason expected) {
        assertThat(OnlineScoringStatusService.reasonOf(failure)).isEqualTo(expected);
    }

    @Test
    void everyRuleStatusIsFoundOldestFirst() {
        var service = newService(Duration.days(1));
        var trace = experimentTrace();
        var workspaceId = UUID.randomUUID().toString();
        var maxAttempts = RandomUtils.secure().randomInt(2, 11);
        var retrying = expected(State.RETRYING, true)
                .attempt(RandomUtils.secure().randomInt(1, maxAttempts))
                .maxAttempts(maxAttempts)
                .build();
        var failed = expected(State.FAILED, false).build();
        var skipped = expected(State.SKIPPED, false).build();
        var before = Instant.now();

        service.recordRetrying(trace, workspaceId, retrying.ruleId(), retrying.suiteAssertion(), retrying.reason(),
                retrying.attempt(), retrying.maxAttempts()).block();
        service.recordFailed(trace, workspaceId, failed.ruleId(), failed.suiteAssertion(), failed.reason()).block();
        service.recordSkipped(trace, workspaceId, skipped.ruleId(), skipped.suiteAssertion(), skipped.reason())
                .block();
        var found = service.find(workspaceId, trace.id()).block();

        assertThat(found)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("updatedAt")
                .containsExactly(retrying, failed, skipped);
        assertThat(found).extracting(OnlineScoringStatus::updatedAt)
                .allSatisfy(updatedAt -> assertThat(updatedAt).isBetween(before, Instant.now()));
    }

    @Test
    void aRulesLatestStatusReplacesItsEarlierOne() {
        var service = newService(Duration.days(1));
        var trace = experimentTrace();
        var workspaceId = UUID.randomUUID().toString();
        var failed = expected(State.FAILED, false).build();

        service.recordRetrying(trace, workspaceId, failed.ruleId(), false, randomReason(), 1, 3).block();
        service.recordFailed(trace, workspaceId, failed.ruleId(), false, failed.reason()).block();

        assertThat(service.find(workspaceId, trace.id()).block())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("updatedAt")
                .containsExactly(failed);
    }

    @Test
    void clearingARuleKeepsTheOtherRulesStatuses() {
        var service = newService(Duration.days(1));
        var trace = experimentTrace();
        var workspaceId = UUID.randomUUID().toString();
        var cleared = UUID.randomUUID();
        var kept = expected(State.FAILED, false).build();

        service.recordFailed(trace, workspaceId, cleared, false, randomReason()).block();
        service.recordFailed(trace, workspaceId, kept.ruleId(), false, kept.reason()).block();
        service.clear(trace, workspaceId, cleared).block();

        assertThat(service.find(workspaceId, trace.id()).block())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("updatedAt")
                .containsExactly(kept);
    }

    @Test
    void statusesExpireAfterTheConfiguredTtl() {
        var ttl = Duration.hours(RandomUtils.secure().randomInt(1, 49));
        var service = newService(ttl);
        var trace = experimentTrace();
        var workspaceId = UUID.randomUUID().toString();

        service.recordFailed(trace, workspaceId, UUID.randomUUID(), false, randomReason()).block();

        assertThat(redisClient.getKeys().remainTimeToLive(keyOf(workspaceId, trace.id())).block())
                .isBetween(ttl.toMilliseconds() - 60_000, ttl.toMilliseconds());
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = Source.class, names = "EXPERIMENT", mode = EnumSource.Mode.EXCLUDE)
    void recordsNothingForTracesNoOneIsWaitingOn(Source source) {
        var service = newService(Duration.days(1));
        var trace = experimentTrace().toBuilder().source(source).build();
        var workspaceId = UUID.randomUUID().toString();
        var ruleId = UUID.randomUUID();

        service.recordFailed(trace, workspaceId, ruleId, false, randomReason()).block();
        service.recordRetrying(trace, workspaceId, ruleId, false, randomReason(), 1, 3).block();
        service.recordSkipped(trace, workspaceId, ruleId, false, randomReason()).block();
        service.clear(trace, workspaceId, ruleId).block();

        assertThat(redisClient.getKeys().countExists(keyOf(workspaceId, trace.id())).block()).isZero();
    }

    @Test
    void anUnreadableStatusHidesOnlyItself() {
        var service = newService(Duration.days(1));
        var trace = experimentTrace();
        var workspaceId = UUID.randomUUID().toString();
        var readable = expected(State.FAILED, false).build();
        service.recordFailed(trace, workspaceId, readable.ruleId(), false, readable.reason()).block();
        var newerState = JsonUtils.writeValueAsString(Map.of(
                "rule_id", UUID.randomUUID(),
                "state", RandomStringUtils.secure().nextAlphabetic(10),
                "reason", Reason.OTHER.getValue(),
                "updated_at", Instant.now()));
        redisClient.<String, String>getMap(keyOf(workspaceId, trace.id()), StringCodec.INSTANCE)
                .putAll(Map.of(
                        UUID.randomUUID().toString(), newerState,
                        UUID.randomUUID().toString(), RandomStringUtils.secure().nextAlphanumeric(20)))
                .block();

        assertThat(service.find(workspaceId, trace.id()).block())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("updatedAt")
                .containsExactly(readable);
    }

    @Test
    void aStatusRedisFailsToStoreOrClearDoesNotFailTheScoring() {
        var failingClient = mock(RedissonReactiveClient.class);
        RMapReactive<String, String> statuses = mock(RMapReactive.class);
        when(failingClient.<String, String>getMap(anyString(), any(Codec.class))).thenReturn(statuses);
        when(statuses.fastPut(anyString(), anyString()))
                .thenReturn(Mono.error(new RedisException(randomMessage())));
        when(statuses.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));
        when(statuses.fastRemove(anyString())).thenReturn(Mono.error(new RedisException(randomMessage())));
        var service = new OnlineScoringStatusService(failingClient,
                OnlineScoringConfig.builder().scoringStatusTtl(Duration.days(1)).build());
        var trace = experimentTrace();
        var workspaceId = UUID.randomUUID().toString();
        var ruleId = UUID.randomUUID();

        StepVerifier.create(service.recordFailed(trace, workspaceId, ruleId, false, randomReason()))
                .verifyComplete();
        StepVerifier.create(service.clear(trace, workspaceId, ruleId)).verifyComplete();
    }

    @Test
    void statusesRedisFailsToReadAreReportedAsNone() {
        var failingClient = mock(RedissonReactiveClient.class);
        RMapReactive<String, String> statuses = mock(RMapReactive.class);
        when(failingClient.<String, String>getMap(anyString(), any(Codec.class))).thenReturn(statuses);
        when(statuses.readAllValues()).thenReturn(Mono.error(new RedisException(randomMessage())));
        var service = new OnlineScoringStatusService(failingClient,
                OnlineScoringConfig.builder().scoringStatusTtl(Duration.days(1)).build());

        StepVerifier.create(service.find(UUID.randomUUID().toString(), UUID.randomUUID()))
                .expectNext(List.of())
                .verifyComplete();
    }

    private OnlineScoringStatusService newService(Duration ttl) {
        return new OnlineScoringStatusService(redisClient,
                OnlineScoringConfig.builder().scoringStatusTtl(ttl).build());
    }

    private Trace experimentTrace() {
        return podamFactory.manufacturePojo(Trace.class).toBuilder().source(Source.EXPERIMENT).build();
    }

    private Reason randomReason() {
        return podamFactory.manufacturePojo(Reason.class);
    }

    private OnlineScoringStatus.OnlineScoringStatusBuilder expected(State state, boolean suiteAssertion) {
        return OnlineScoringStatus.builder()
                .ruleId(UUID.randomUUID())
                .suiteAssertion(suiteAssertion)
                .state(state)
                .reason(randomReason());
    }

    private static String keyOf(String workspaceId, UUID traceId) {
        return "online_scoring_status:" + workspaceId + ":" + traceId;
    }

    private static String randomMessage() {
        return RandomStringUtils.secure().nextAlphanumeric(20);
    }
}
