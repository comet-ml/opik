package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.StructuredOutputStrategy;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.LogContextAware;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE;

@EagerSingleton
@Slf4j
public class OnlineScoringTraceThreadLlmAsJudgeScorer extends OnlineScoringBaseScorer<TraceThreadToScoreLlmAsJudge> {

    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final TraceThreadService traceThreadService;
    private final ProjectService projectService;
    private final AutomationRuleEvaluatorService automationRuleEvaluatorService;

    @Inject
    public OnlineScoringTraceThreadLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull TraceService traceService,
            @NonNull TraceThreadService traceThreadService,
            @NonNull ProjectService projectService,
            @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService) {
        super(config, redisson, feedbackScoreService, traceService, TRACE_THREAD_LLM_AS_JUDGE,
                Constants.TRACE_THREAD_LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.llmProviderFactory = llmProviderFactory;
        this.traceThreadService = traceThreadService;
        this.projectService = projectService;
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringTraceThreadLlmAsJudgeScorer.class);
    }

    /**
     * Use AI Proxy to score the trace thread and store it as a feedback scores.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param message a Redis message with the trace thread to score with an Evaluator code, workspace and username.
     */
    @Override
    protected void score(@NonNull TraceThreadToScoreLlmAsJudge message) {

        log.info("Message received with projectId: '{}', ruleId: '{}', threadIds: '{}' for workspace '{}'",
                message.projectId(), message.ruleId(), message.threadIds(), message.workspaceId());

        Flux.fromIterable(message.threadIds())
                .flatMap(threadId -> processThreadScores(message, threadId))
                .then(Mono.defer(
                        () -> traceThreadService.setScoredAt(message.projectId(), message.threadIds(), Instant.now())))
                .contextWrite(context -> context.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName())
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                .thenReturn(message)
                .subscribe(
                        unused -> log.info("Processed trace threads for projectId '{}', ruleId '{}' for workspace '{}'",
                                message.projectId(), message.ruleId(), message.workspaceId()),
                        error -> {
                            log.error(
                                    "Error processing trace thread for projectId '{}', ruleId '{}' for workspace '{}': {}",
                                    message.projectId(), message.ruleId(), message.workspaceId(), error.getMessage());
                            log.error("Error processing trace thread scoring", error);
                        });
    }

    private Mono<Void> processThreadScores(TraceThreadToScoreLlmAsJudge message, String currentThreadId) {
        return retrieveFullThreadContext(currentThreadId, new AtomicReference<>(null), message.projectId())
                .sort(Comparator.comparing(Trace::id))
                .collectList()
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("No traces found for threadId '{}' in projectId '{}'. Skipping scoring.",
                            currentThreadId, message.projectId());
                    return Mono.empty();
                }))
                .flatMap(traces -> traceThreadService.getThreadModelId(message.projectId(), currentThreadId)
                        .flatMap(threadModelId -> Mono.fromCallable(() -> {
                            try (var logContext = LogContextAware.wrapWithMdc(Map.of(
                                    UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                                    UserLog.WORKSPACE_ID, message.workspaceId(),
                                    UserLog.THREAD_MODEL_ID, threadModelId.toString(),
                                    UserLog.RULE_ID, message.ruleId().toString()))) {

                                // Process python scoring for the thread
                                processScoring(message, traces, threadModelId, currentThreadId);

                                return threadModelId;
                            }
                        }).subscribeOn(Schedulers.boundedElastic())))
                .then();
    }

    /**
     * Processes the scoring for a given thread using the Python evaluator.
     *
     * @param message the message containing project ID, rule ID, and other details
     * @param traces the list of traces associated with the thread
     * @param threadModelId the ID of the thread model
     * @param threadId the ID of the thread
     */
    private void processScoring(TraceThreadToScoreLlmAsJudge message, List<Trace> traces,
            UUID threadModelId, String threadId) {

        final AutomationRuleEvaluator<?, ?> rule;

        try {
            rule = automationRuleEvaluatorService.findById(message.ruleId(), Set.of(message.projectId()),
                    message.workspaceId());
        } catch (NotFoundException ex) {
            log.warn(
                    "Automation rule with ID '{}' not found in projectId '{}' for workspace '{}'. Skipping scoring for threadId '{}'.",
                    message.ruleId(), message.projectId(), message.workspaceId(), threadId);
            return;
        }

        // This is crucial for logging purposes to identify the rule and trace
        userFacingLogger.info("Evaluating threadId: '{}' sampled by ruleName: '{}'", threadId, rule.getName());
        log.info("Evaluating threadId: '{}' sampled by ruleName: '{}'", threadId, rule.getName());

        Project project = projectService.get(message.projectId(), message.workspaceId());

        ChatRequest scoreRequest;
        try {
            String modelName = message.code().model().name();
            var llmProvider = llmProviderFactory.getLlmProvider(modelName);
            var strategy = StructuredOutputStrategy.getStrategy(llmProvider, modelName);
            scoreRequest = OnlineScoringEngine.prepareThreadLlmRequest(message.code(), traces, strategy);
        } catch (Exception exception) {
            userFacingLogger.error("Error preparing LLM request for threadId: '{}': \n\n{}",
                    threadId, exception.getMessage());
            throw exception;
        }

        userFacingLogger.info("Sending threadId: '{}' to LLM using the following input:\n\n{}",
                threadId, scoreRequest);
        log.info("Sending threadId: '{}' to LLM using the following input:\n\n{}", threadId, scoreRequest);

        ChatResponse chatResponse;
        try {
            chatResponse = aiProxyService.scoreTrace(
                    scoreRequest, message.code().model(), message.workspaceId());
            userFacingLogger.info("Received response for threadId: '{}':\n\n{}", threadId, chatResponse);
        } catch (Exception exception) {
            userFacingLogger.error("Unexpected error while scoring threadId: '{}' with ruleName: '{}': \n\n{}",
                    threadId, rule.getName(), Optional.ofNullable(exception.getCause())
                            .map(Throwable::getMessage)
                            .orElse(exception.getMessage()));
            throw exception;
        }

        try {
            List<FeedbackScoreBatchItemThread> scores = OnlineScoringEngine.toFeedbackScores(chatResponse).stream()
                    .map(item -> FeedbackScoresMapper.INSTANCE.map(
                            item.toBuilder()
                                    .id(threadModelId)
                                    .projectId(message.projectId())
                                    .projectName(project.name())
                                    .source(ScoreSource.ONLINE_SCORING)
                                    .build(),
                            threadId))
                    .toList();

            var loggedScores = storeThreadScores(scores, threadId, message.userName(), message.workspaceId());
            userFacingLogger.info("Scores for threadId '{}' stored successfully:\n\n{}", threadId, loggedScores);
        } catch (Exception exception) {
            userFacingLogger.error("Unexpected error while storing scores for threadId '{}' with ruleName '{}'",
                    threadId, rule.getName());
            throw exception;
        }
    }
}
