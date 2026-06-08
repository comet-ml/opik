package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.utils.JsonUtils;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OnlineScoringSpanLlmAsJudgeScorer admission weighting")
class OnlineScoringSpanLlmAsJudgeScorerTest {

    @Mock
    private OnlineScoringConfig onlineScoringConfig;
    @Mock
    private ServiceTogglesConfig serviceTogglesConfig;
    @Mock
    private RedissonReactiveClient redissonClient;
    @Mock
    private FeedbackScoreService feedbackScoreService;
    @Mock
    private ChatCompletionService aiProxyService;
    @Mock
    private TraceService traceService;
    @Mock
    private LlmProviderFactory llmProviderFactory;

    private MockedStatic<UserFacingLoggingFactory> mockedFactory;
    private OnlineScoringSpanLlmAsJudgeScorer scorer;

    @BeforeEach
    void setUp() {
        mockedFactory = mockStatic(UserFacingLoggingFactory.class);
        mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                .thenReturn(mock(Logger.class));

        var streamConfig = OnlineScoringConfig.StreamConfiguration.builder()
                .scorer("span_llm_as_judge")
                .streamName("stream_scoring_span_llm_as_judge")
                .codec("java")
                .build();
        lenient().when(onlineScoringConfig.getStreams()).thenReturn(List.of(streamConfig));
        lenient().when(onlineScoringConfig.getConsumerGroupName()).thenReturn("online_scoring");

        scorer = new OnlineScoringSpanLlmAsJudgeScorer(onlineScoringConfig, serviceTogglesConfig, redissonClient,
                feedbackScoreService, aiProxyService, traceService, llmProviderFactory);
    }

    @AfterEach
    void tearDown() {
        if (mockedFactory != null) {
            mockedFactory.close();
        }
    }

    @Test
    void estimateInFlightBytesSumsSpanInputOutputAndMetadata() {
        var input = JsonUtils.getJsonNodeFromString("{\"q\":\"hello\"}");
        var output = JsonUtils.getJsonNodeFromString("{\"a\":\"world\"}");
        var metadata = JsonUtils.getJsonNodeFromString("{\"m\":\"meta\"}");
        var span = mock(Span.class);
        when(span.input()).thenReturn(input);
        when(span.output()).thenReturn(output);
        when(span.metadata()).thenReturn(metadata);
        var message = mock(SpanToScoreLlmAsJudge.class);
        when(message.span()).thenReturn(span);

        long expected = input.toString().length() + output.toString().length() + metadata.toString().length();
        assertThat(scorer.estimateInFlightBytes(message)).isEqualTo(expected);
    }

    @Test
    void estimateInFlightBytesSkipsNullMetadata() {
        var input = JsonUtils.getJsonNodeFromString("{\"q\":\"hello\"}");
        var span = mock(Span.class);
        when(span.input()).thenReturn(input);
        when(span.output()).thenReturn(null);
        when(span.metadata()).thenReturn(null);
        var message = mock(SpanToScoreLlmAsJudge.class);
        when(message.span()).thenReturn(span);

        assertThat(scorer.estimateInFlightBytes(message)).isEqualTo(input.toString().length());
    }

    @Test
    void admissionControlFollowsServiceToggle() {
        assertThat(scorer.isAdmissionControlEnabled()).isFalse();

        when(serviceTogglesConfig.isMemoryAwareScoringBoundEnabled()).thenReturn(true);
        assertThat(scorer.isAdmissionControlEnabled()).isTrue();
    }

    @Test
    void admissionAttributesTagWorkspaceAndRule() {
        var ruleId = UUID.randomUUID();
        var message = mock(SpanToScoreLlmAsJudge.class);
        when(message.workspaceId()).thenReturn("ws-1");
        when(message.ruleId()).thenReturn(ruleId);

        var attributes = scorer.admissionAttributes(message);

        assertThat(attributes.get(AttributeKey.stringKey("workspace_id"))).isEqualTo("ws-1");
        assertThat(attributes.get(AttributeKey.stringKey("rule_id"))).isEqualTo(ruleId.toString());
    }
}
