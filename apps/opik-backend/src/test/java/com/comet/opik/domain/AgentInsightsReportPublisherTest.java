package com.comet.opik.domain;

import com.comet.opik.infrastructure.AgentInsightsReportConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.Codec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every Agent Insights trigger (cron, manual, auto-first-run) enqueues through the publisher, so this is where
 * the {@code ollieEnabled} / {@code agentInsightsEnabled} pair decides whether a run can happen at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent Insights Report Publisher")
class AgentInsightsReportPublisherTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final Instant PERIOD_END = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant PERIOD_START = PERIOD_END.minus(1, ChronoUnit.DAYS);

    @Mock
    private RedissonReactiveClient redisson;
    @Mock
    private RStreamReactive<Object, Object> stream;
    @Mock
    private IdGenerator idGenerator;
    @Mock
    private AgentInsightsRunGuidanceService runGuidanceService;

    private AgentInsightsReportPublisher publisher(boolean ollieEnabled, boolean agentInsightsEnabled) {
        var serviceToggles = new ServiceTogglesConfig();
        serviceToggles.setOllieEnabled(ollieEnabled);
        serviceToggles.setAgentInsightsEnabled(agentInsightsEnabled);
        return new AgentInsightsReportPublisher(redisson, new AgentInsightsReportConfig(), serviceToggles,
                idGenerator, runGuidanceService);
    }

    @Test
    @DisplayName("Ollie on, Agent Insights on: the trigger is published")
    void enqueue__whenOllieAndAgentInsightsEnabled__publishes() {
        var reportId = UUID.randomUUID();
        when(idGenerator.generateId()).thenReturn(reportId);
        when(redisson.getStream(anyString(), any(Codec.class))).thenReturn(stream);
        when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(1, 0)));

        StepVerifier.create(publisher(true, true)
                .enqueue(UUID.randomUUID(), WORKSPACE_ID, PERIOD_START, PERIOD_END, AgentInsightsMetrics.MANUAL))
                .expectNext(reportId.toString())
                .verifyComplete();

        verify(stream).add(any());
    }

    @ParameterizedTest(name = "ollieEnabled={0}, agentInsightsEnabled={1}")
    @MethodSource("disabledToggles")
    @DisplayName("Ollie off, or Agent Insights off: the trigger is dropped without touching Redis")
    void enqueue__whenDisabled__dropsTrigger(boolean ollieEnabled, boolean agentInsightsEnabled) {
        StepVerifier.create(publisher(ollieEnabled, agentInsightsEnabled)
                .enqueue(UUID.randomUUID(), WORKSPACE_ID, PERIOD_START, PERIOD_END, AgentInsightsMetrics.MANUAL))
                .verifyComplete();

        verifyNoInteractions(redisson, idGenerator, runGuidanceService);
    }

    static Stream<Arguments> disabledToggles() {
        return Stream.of(
                Arguments.of(true, false),
                // Agent Insights runs on Ollie pods, so its own toggle cannot turn it on without Ollie.
                Arguments.of(false, true),
                Arguments.of(false, false));
    }
}
