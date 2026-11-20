package com.comet.opik.domain.evaluators;

import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.OnlineScoringStatus;
import com.comet.opik.api.evaluators.OnlineScoringStatus.Reason;
import com.comet.opik.infrastructure.llm.LlmProviderKeyNotConfiguredException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ServerErrorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OnlineScoringStatusServiceTest {

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new LlmProviderKeyNotConfiguredException("API key not configured"),
                        Reason.NOT_CONFIGURED),
                Arguments.of(new ClientErrorException("Incorrect API key provided", 401), Reason.AUTHENTICATION),
                Arguments.of(new ClientErrorException("forbidden", 403), Reason.AUTHENTICATION),
                Arguments.of(new ClientErrorException("model not found", 404), Reason.NOT_FOUND),
                Arguments.of(new ClientErrorException("bad request", 400), Reason.REJECTED),
                Arguments.of(new ClientErrorException("timed out", 408), Reason.TIMEOUT),
                Arguments.of(new ClientErrorException("slow down", 429), Reason.RATE_LIMITED),
                Arguments.of(new ServerErrorException("unavailable", 503), Reason.SERVER_ERROR),
                Arguments.of(new ServerErrorException("unreachable", 502), Reason.UNREACHABLE),
                Arguments.of(new ServerErrorException("no answer in time", 504), Reason.TIMEOUT),
                Arguments.of(new ServerErrorException("unexpected", 500), Reason.SERVER_ERROR),
                Arguments.of(new RuntimeException("Python BE timeout"), Reason.OTHER));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("failures")
    void reasonOfNamesTheCauseOfAFailedAttempt(Throwable failure, Reason expected) {
        assertThat(OnlineScoringStatusService.reasonOf(failure)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = Source.class, names = "EXPERIMENT", mode = EnumSource.Mode.EXCLUDE)
    void recordsNothingForTracesNoOneIsWaitingOn(Source source) {
        var redisClient = mock(RedissonReactiveClient.class);
        var service = new OnlineScoringStatusService(redisClient);
        var trace = Trace.builder().id(UUID.randomUUID()).source(source).startTime(Instant.now()).build();
        var workspaceId = UUID.randomUUID().toString();
        var ruleId = UUID.randomUUID();

        service.recordFailed(trace, workspaceId, ruleId, false, Reason.AUTHENTICATION).block();
        service.recordRetrying(trace, workspaceId, ruleId, false, Reason.SERVER_ERROR, 1, 3).block();
        service.recordSkipped(trace, workspaceId, ruleId, false, Reason.NO_SCORE).block();
        service.clear(trace, workspaceId, ruleId).block();

        verifyNoInteractions(redisClient);
    }

    @Test
    void aStatusRedisFailsToStoreOrClearDoesNotFailTheScoring() {
        var redisClient = mock(RedissonReactiveClient.class);
        RMapReactive<String, String> statuses = mock(RMapReactive.class);
        when(redisClient.<String, String>getMap(anyString(), any(Codec.class))).thenReturn(statuses);
        when(statuses.fastPut(anyString(), anyString())).thenReturn(Mono.error(new RedisException("unavailable")));
        when(statuses.expire(any(Duration.class))).thenReturn(Mono.just(true));
        when(statuses.fastRemove(anyString())).thenReturn(Mono.error(new RedisException("unavailable")));
        var service = new OnlineScoringStatusService(redisClient);
        var trace = Trace.builder().id(UUID.randomUUID()).source(Source.EXPERIMENT).startTime(Instant.now()).build();
        var workspaceId = UUID.randomUUID().toString();
        var ruleId = UUID.randomUUID();

        StepVerifier.create(service.recordFailed(trace, workspaceId, ruleId, false, Reason.AUTHENTICATION))
                .verifyComplete();
        StepVerifier.create(service.clear(trace, workspaceId, ruleId)).verifyComplete();
    }

    @Test
    void statusesRedisFailsToReadAreReportedAsNone() {
        var redisClient = mock(RedissonReactiveClient.class);
        RMapReactive<String, String> statuses = mock(RMapReactive.class);
        when(redisClient.<String, String>getMap(anyString(), any(Codec.class))).thenReturn(statuses);
        when(statuses.readAllValues()).thenReturn(Mono.error(new RedisException("unavailable")));
        var service = new OnlineScoringStatusService(redisClient);

        StepVerifier.create(service.find(UUID.randomUUID().toString(), UUID.randomUUID()))
                .expectNext(List.of())
                .verifyComplete();
    }

    @Test
    void anUnreadableStatusHidesOnlyItself() {
        var redisClient = mock(RedissonReactiveClient.class);
        RMapReactive<String, String> statuses = mock(RMapReactive.class);
        when(redisClient.<String, String>getMap(anyString(), any(Codec.class))).thenReturn(statuses);
        var readable = """
                {"rule_id":"%s","state":"failed","reason":"authentication","updated_at":"2026-11-18T10:00:00Z"}"""
                .formatted(UUID.randomUUID());
        var newerState = """
                {"rule_id":"%s","state":"paused","reason":"other","updated_at":"2026-11-18T10:00:00Z"}"""
                .formatted(UUID.randomUUID());
        when(statuses.readAllValues()).thenReturn(Mono.just(List.of(readable, newerState, "not json")));
        var service = new OnlineScoringStatusService(redisClient);

        var found = service.find(UUID.randomUUID().toString(), UUID.randomUUID()).block();

        assertThat(found).singleElement()
                .extracting(OnlineScoringStatus::state, OnlineScoringStatus::reason)
                .containsExactly(OnlineScoringStatus.State.FAILED, Reason.AUTHENTICATION);
    }
}
