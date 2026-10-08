package com.comet.opik.domain;

import com.comet.opik.infrastructure.AgentInsightsReportConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddParams;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.Codec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private TransactionTemplate transactionTemplate;

    private AgentInsightsReportPublisher publisher(boolean ollieEnabled, boolean agentInsightsEnabled) {
        return publisher(ollieEnabled, agentInsightsEnabled, false);
    }

    private AgentInsightsReportPublisher publisher(boolean ollieEnabled, boolean agentInsightsEnabled,
            boolean guidanceEnabled) {
        var serviceToggles = new ServiceTogglesConfig();
        serviceToggles.setOllieEnabled(ollieEnabled);
        serviceToggles.setAgentInsightsEnabled(agentInsightsEnabled);
        serviceToggles.setAgentInsightsGuidanceEnabled(guidanceEnabled);
        return new AgentInsightsReportPublisher(redisson, new AgentInsightsReportConfig(), serviceToggles,
                idGenerator, transactionTemplate);
    }

    private AgentInsightsReportMessage publishedMessage() {
        ArgumentCaptor<StreamAddParams<Object, Object>> captor = ArgumentCaptor.forClass(StreamAddParams.class);
        verify(stream).add(captor.capture());
        return (AgentInsightsReportMessage) captor.getValue().getEntries()
                .get(AgentInsightsReportConfig.PAYLOAD_FIELD);
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

    @Test
    @DisplayName("Guidance on: the run carries the project guidance and its version, read at enqueue")
    void enqueue__whenGuidanceEnabled__carriesGuidance() {
        when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
        when(redisson.getStream(anyString(), any(Codec.class))).thenReturn(stream);
        when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(1, 0)));
        when(transactionTemplate.inTransaction(eq(WRITE), any()))
                .thenReturn(new AgentInsightsJobDAO.RunGuidance("Only report billing failures", 3));

        StepVerifier.create(publisher(true, true, true)
                .enqueue(UUID.randomUUID(), WORKSPACE_ID, PERIOD_START, PERIOD_END, AgentInsightsMetrics.SCHEDULED))
                .expectNextCount(1)
                .verifyComplete();

        var message = publishedMessage();
        assertThat(message.guidance()).isEqualTo("Only report billing failures");
        assertThat(message.guidanceVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("Guidance off: nothing is read or stamped and the run carries no guidance")
    void enqueue__whenGuidanceDisabled__carriesNoGuidance() {
        when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
        when(redisson.getStream(anyString(), any(Codec.class))).thenReturn(stream);
        when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(1, 0)));

        StepVerifier.create(publisher(true, true, false)
                .enqueue(UUID.randomUUID(), WORKSPACE_ID, PERIOD_START, PERIOD_END, AgentInsightsMetrics.MANUAL))
                .expectNextCount(1)
                .verifyComplete();

        var message = publishedMessage();
        assertThat(message.guidance()).isNull();
        assertThat(message.guidanceVersion()).isNull();
        verifyNoInteractions(transactionTemplate);
    }

    @ParameterizedTest(name = "ollieEnabled={0}, agentInsightsEnabled={1}")
    @MethodSource("disabledToggles")
    @DisplayName("Ollie off, or Agent Insights off: the trigger is dropped without touching Redis")
    void enqueue__whenDisabled__dropsTrigger(boolean ollieEnabled, boolean agentInsightsEnabled) {
        StepVerifier.create(publisher(ollieEnabled, agentInsightsEnabled)
                .enqueue(UUID.randomUUID(), WORKSPACE_ID, PERIOD_START, PERIOD_END, AgentInsightsMetrics.MANUAL))
                .verifyComplete();

        verifyNoInteractions(redisson, idGenerator, transactionTemplate);
    }

    static Stream<Arguments> disabledToggles() {
        return Stream.of(
                Arguments.of(true, false),
                // Agent Insights runs on Ollie pods, so its own toggle cannot turn it on without Ollie.
                Arguments.of(false, true),
                Arguments.of(false, false));
    }
}
