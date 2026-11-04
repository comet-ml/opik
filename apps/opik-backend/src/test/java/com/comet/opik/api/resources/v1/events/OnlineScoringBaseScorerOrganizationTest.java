package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("The organization a scoring message carries reaches the scorer's context")
class OnlineScoringBaseScorerOrganizationTest {

    private final ContextCapturingScorer scorer = new ContextCapturingScorer();

    @Test
    @DisplayName("A message's organization is put in the context, next to its workspace")
    void restoresTheOrganization() {
        scorer.processEvent(message("organization-1")).block();

        assertThat(scorer.context().<String>getOrEmpty(RequestContext.ORGANIZATION_ID)).contains("organization-1");
        assertThat(scorer.context().<String>get(RequestContext.WORKSPACE_ID)).isEqualTo("workspace");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("A message with no organization is scored without one, as before")
    void scoresWithoutAnOrganization(String organizationId) {
        scorer.processEvent(message(organizationId)).block();

        assertThat(scorer.context().hasKey(RequestContext.ORGANIZATION_ID)).isFalse();
        assertThat(scorer.context().<String>get(RequestContext.WORKSPACE_ID)).isEqualTo("workspace");
    }

    private static TraceThreadToScoreLlmAsJudge message(String organizationId) {
        return TraceThreadToScoreLlmAsJudge.builder()
                .threadIds(List.of("thread-1"))
                .ruleId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .code(PodamFactoryUtils.newPodamFactory().manufacturePojo(TraceThreadLlmAsJudgeCode.class))
                .workspaceId("workspace")
                .userName("user")
                .organizationId(organizationId)
                .build();
    }

    private static class ContextCapturingScorer extends OnlineScoringBaseScorer<TraceThreadToScoreLlmAsJudge> {

        private final AtomicReference<ContextView> context = new AtomicReference<>();

        ContextCapturingScorer() {
            super(OnlineScoringConfig.builder()
                    .consumerGroupName("test")
                    .streams(List.of(OnlineScoringConfig.StreamConfiguration.builder()
                            .scorer(AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE.getType())
                            .streamName("test-stream")
                            .codec(RedisStreamCodec.JAVA.getName())
                            .build()))
                    .build(),
                    mock(RedissonReactiveClient.class),
                    mock(FeedbackScoreService.class),
                    mock(TraceService.class),
                    mock(SpanService.class),
                    AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE,
                    "context_capturing");
        }

        @Override
        protected Mono<Void> score(TraceThreadToScoreLlmAsJudge message) {
            return Mono.deferContextual(ctx -> {
                context.set(ctx);
                return Mono.empty();
            });
        }

        ContextView context() {
            return context.get();
        }
    }
}
