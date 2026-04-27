package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

@EagerSingleton
@Slf4j
public class OnlineScoringLlmAsJudgeScorer extends OnlineScoringBaseScorer<TraceToScoreLlmAsJudge> {

    private static final int MAX_TOOL_CALL_ROUNDS = 10;

    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final TestSuiteAssertionCounterService testSuiteAssertionCounterService;
    private final SpanService spanService;

    @Inject
    public OnlineScoringLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull TestSuiteAssertionCounterService testSuiteAssertionCounterService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull SpanService spanService) {
        super(config, redisson, feedbackScoreService, traceService,
                LLM_AS_JUDGE, Constants.LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
        this.spanService = spanService;
    }

    @Override
    protected Mono<Void> processEvent(TraceToScoreLlmAsJudge message) {
        UUID experimentId = message.experimentId();
        if (experimentId != null) {
            return super.processEvent(message)
                    .then(testSuiteAssertionCounterService.decrementAndFinishIfComplete(
                            message.workspaceId(), experimentId)
                            .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                    .put(RequestContext.USER_NAME, message.userName())));
        }
        return super.processEvent(message);
    }

    @Override
    protected void score(@NonNull TraceToScoreLlmAsJudge message) {
        var trace = message.trace();
        log.info("Message received with traceId '{}', userName '{}', to be scored in '{}'",
                trace.id(), message.userName(), message.llmAsJudgeCode().model().name());

        try (var logContext = wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.TRACE_ID, trace.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString()))) {

            userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), message.ruleName());

            ChatRequest scoreRequest;
            try {
                String modelName = message.llmAsJudgeCode().model().name();
                var strategy = llmProviderFactory.getStructuredOutputStrategy(modelName);
                scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                        message.llmAsJudgeCode(), trace, strategy, message.promptType());
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for traceId '{}': \n\n{}",
                        trace.id(), exception.getMessage());
                throw exception;
            }

            ChatRequest structuredRequest = scoreRequest;
            if (message.experimentId() != null) {
                scoreRequest = addToolSpecs(scoreRequest);
            }

            userFacingLogger.info("Sending traceId '{}' to LLM using the following input:\n\n{}",
                    trace.id(), scoreRequest);

            ChatResponse chatResponse;
            try {
                chatResponse = aiProxyService.scoreTrace(
                        scoreRequest, message.llmAsJudgeCode().model(), message.workspaceId());
                userFacingLogger.info("Received response for traceId '{}':\n\n{}", trace.id(), chatResponse);
            } catch (Exception exception) {
                String errorMessage = Optional.ofNullable(exception.getCause())
                        .map(Throwable::getMessage)
                        .orElse(exception.getMessage());

                userFacingLogger.error("Unexpected error while scoring traceId '{}' with rule '{}': \n\n{}",
                        trace.id(), message.ruleName(), errorMessage);
                throw exception;
            }

            // Handle tool calls if the LLM wants to inspect spans
            chatResponse = handleToolCalls(chatResponse, scoreRequest, structuredRequest, message);

            try {
                List<FeedbackScoreBatchItem> scores = OnlineScoringEngine.toFeedbackScores(chatResponse).stream()
                        .map(item -> {
                            String scoreName = item.name();
                            if (message.scoreNameMapping().containsKey(scoreName)) {
                                scoreName = message.scoreNameMapping().get(scoreName);
                            }
                            return (FeedbackScoreBatchItem) item.toBuilder()
                                    .name(scoreName)
                                    .categoryName(message.categoryName())
                                    .id(trace.id())
                                    .projectId(trace.projectId())
                                    .projectName(trace.projectName())
                                    .build();
                        })
                        .toList();
                var loggedScores = storeScores(scores, trace, message.userName(), message.workspaceId());
                userFacingLogger.info("Scores for traceId '{}' stored successfully:\n\n{}", trace.id(), loggedScores);
            } catch (Exception exception) {
                userFacingLogger.error("Unexpected error while storing scores for traceId '{}'", trace.id());
                throw exception;
            }
        }
    }

    private ChatRequest addToolSpecs(ChatRequest request) {
        return request.toBuilder()
                .parameters(null)
                .responseFormat(null)
                .toolSpecifications(TraceSpanToolDefinition.ALL_TOOLS)
                .build();
    }

    private ChatResponse handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, TraceToScoreLlmAsJudge message) {

        AiMessage aiMessage = chatResponse.aiMessage();
        if (!aiMessage.hasToolExecutionRequests()) {
            return chatResponse;
        }

        var trace = message.trace();
        var spans = fetchSpans(trace.id(), message.workspaceId(), message.userName());
        var messages = new ArrayList<>(toolRequest.messages());

        for (int round = 0; round < MAX_TOOL_CALL_ROUNDS; round++) {
            if (!chatResponse.aiMessage().hasToolExecutionRequests()) {
                break;
            }

            messages.add(chatResponse.aiMessage());

            for (var toolExecRequest : chatResponse.aiMessage().toolExecutionRequests()) {
                log.info("Tool call round '{}' for traceId '{}': tool '{}'",
                        round, trace.id(), toolExecRequest.name());
                var result = TraceSpanToolDefinition.executeTool(
                        toolExecRequest.name(), toolExecRequest.arguments(), spans);
                messages.add(ToolExecutionResultMessage.from(toolExecRequest, result));
            }

            var followUp = toolRequest.toBuilder()
                    .messages(messages)
                    .build();

            chatResponse = aiProxyService.scoreTrace(
                    followUp, message.llmAsJudgeCode().model(), message.workspaceId());
        }

        // Tool mode disables responseFormat, so the final text response may contain
        // invalid JSON. Re-send with the original structured output format to ensure
        // the response is properly formatted.
        var finalRequest = structuredRequest.toBuilder()
                .messages(messages)
                .build();

        return aiProxyService.scoreTrace(
                finalRequest, message.llmAsJudgeCode().model(), message.workspaceId());
    }

    private List<Span> fetchSpans(UUID traceId, String workspaceId, String userName) {
        return spanService.getByTraceIds(Set.of(traceId))
                .collectList()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .block();
    }
}
