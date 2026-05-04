package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.EntityRef;
import com.comet.opik.api.resources.v1.events.tools.EntityType;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceCompressor;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
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
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

@EagerSingleton
@Slf4j
public class OnlineScoringLlmAsJudgeScorer extends OnlineScoringBaseScorer<TraceToScoreLlmAsJudge> {

    private static final int MAX_TOOL_CALL_ROUNDS = 10;

    /**
     * Per-variable substitution cap for the test-suite-assertion (tool-enabled) path. ≈ 4 KB chars
     * (~ 1 K tokens via the {@code Tokens.estimate} convention) is large enough that small trace
     * input/output blobs render inline (cheap, no tool round-trip) but small enough that a huge
     * trace doesn't blow context — the agent fetches the rest via the {@code read} tool.
     */
    private static final int MAX_PROMPT_FIELD_CHARS = 4_000;

    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final TestSuiteAssertionCounterService testSuiteAssertionCounterService;
    private final SpanService spanService;
    private final ToolRegistry toolRegistry;
    private final TraceCompressor traceCompressor;

    @Inject
    public OnlineScoringLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull TestSuiteAssertionCounterService testSuiteAssertionCounterService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull SpanService spanService,
            @NonNull ToolRegistry toolRegistry,
            @NonNull TraceCompressor traceCompressor) {
        super(config, redisson, feedbackScoreService, traceService,
                LLM_AS_JUDGE, Constants.LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
        this.spanService = spanService;
        this.toolRegistry = toolRegistry;
        this.traceCompressor = traceCompressor;
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
    protected Mono<Void> score(@NonNull TraceToScoreLlmAsJudge message) {
        var trace = message.trace();
        log.info("Message received with traceId '{}', userName '{}', to be scored in '{}'",
                trace.id(), message.userName(), message.llmAsJudgeCode().model().name());

        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.TRACE_ID, trace.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString());

        return Mono.fromCallable(() -> evaluate(message, mdc))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(scores -> storeScores(scores, trace, message.userName(), message.workspaceId()))
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("Scores for traceId '{}' stored successfully:\n\n{}", trace.id(), loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while scoring traceId '{}' with rule '{}': \n\n{}",
                                trace.id(), message.ruleName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    private List<FeedbackScoreBatchItem> evaluate(TraceToScoreLlmAsJudge message, Map<String, String> mdc) {
        var trace = message.trace();
        // This is crucial for logging purposes to identify the rule and trace
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), message.ruleName());

            ChatRequest scoreRequest;
            try {
                String modelName = message.llmAsJudgeCode().model().name();
                if (shouldUseTools(message)) {
                    // Assertion path: cap variable substitutions so huge trace input/output JSON
                    // doesn't pre-load context. The agent has read/jq tools to drill in on demand.
                    String drillDownHint = "use read(type=trace, id=%s, tier=FULL) to see full"
                            .formatted(trace.id());
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace, new InstructionStrategy(),
                            message.promptType(), MAX_PROMPT_FIELD_CHARS, drillDownHint);
                } else {
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType());
                }
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for traceId '{}': \n\n{}",
                        trace.id(), exception.getMessage());
                throw exception;
            }

            ChatRequest structuredRequest = scoreRequest;
            if (shouldUseTools(message)) {
                scoreRequest = addToolSpecs(scoreRequest);
            }

            userFacingLogger.info("Sending traceId '{}' to LLM using the following input:\n\n{}",
                    trace.id(), scoreRequest);

            var chatResponse = aiProxyService.scoreTrace(
                    scoreRequest, message.llmAsJudgeCode().model(), message.workspaceId());
            userFacingLogger.info("Received response for traceId '{}':\n\n{}", trace.id(), chatResponse);

            if (shouldUseTools(message)) {
                chatResponse = handleToolCalls(chatResponse, scoreRequest, structuredRequest, message);
            }

            // When scoreNameMapping is empty (regular online scoring), names pass through unchanged.
            return OnlineScoringEngine.toFeedbackScores(chatResponse).stream()
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
        }
    }

    // Package-private for unit tests.
    ChatRequest addToolSpecs(ChatRequest request) {
        // Preserve all the original request's tunables (response format, temperature, etc.) and
        // only layer the tool specifications on top. ChatRequest rejects setting both
        // `parameters` and `toolSpecifications` directly because tool specs live inside
        // ChatRequestParameters — so we copy the existing parameters via overrideWith and
        // override toolSpecifications, then attach the rebuilt parameters to a fresh request.
        // The naive ChatRequest.builder().messages(...).toolSpecifications(...) version that
        // used to live here silently dropped every other field, leaving the initial scoring
        // call with a different shape from the final structured re-issue at the end of
        // handleToolCalls.
        var parameters = ChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .toolSpecifications(toolRegistry.specs())
                .build();
        return ChatRequest.builder()
                .messages(request.messages())
                .parameters(parameters)
                .build();
    }

    private static boolean shouldUseTools(TraceToScoreLlmAsJudge message) {
        return LlmAsJudgeToolsMode.shouldUseTools(message);
    }

    // Package-private for unit tests.
    ChatResponse handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, TraceToScoreLlmAsJudge message) {

        AiMessage aiMessage = chatResponse.aiMessage();
        if (!aiMessage.hasToolExecutionRequests()) {
            return chatResponse;
        }

        var trace = message.trace();
        var spans = fetchSpans(trace.id(), message.workspaceId(), message.userName());
        var ctx = new TraceToolContext(trace, spans, message.workspaceId(), message.userName());
        // Pre-seed the active trace into the cache so read/jq/search can hit it without re-fetching.
        ctx.cache(new EntityRef(EntityType.TRACE, trace.id().toString()),
                traceCompressor.buildFullJson(trace, spans));
        var messages = new ArrayList<>(toolRequest.messages());

        for (int round = 0; round < MAX_TOOL_CALL_ROUNDS; round++) {
            if (!chatResponse.aiMessage().hasToolExecutionRequests()) {
                break;
            }

            messages.add(chatResponse.aiMessage());

            for (var toolExecRequest : chatResponse.aiMessage().toolExecutionRequests()) {
                log.debug("Tool call round '{}' for traceId '{}': tool '{}'",
                        round, trace.id(), toolExecRequest.name());
                var result = toolRegistry.execute(
                        toolExecRequest.name(), toolExecRequest.arguments(), ctx);
                messages.add(ToolExecutionResultMessage.from(toolExecRequest, result));
            }

            var followUp = toolRequest.toBuilder()
                    .messages(messages)
                    .build();

            chatResponse = aiProxyService.scoreTrace(
                    followUp, message.llmAsJudgeCode().model(), message.workspaceId());
        }

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
