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
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
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
    private final WorkspaceNameService workspaceNameService;
    private final OpikConfiguration opikConfiguration;

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
            @NonNull TraceCompressor traceCompressor,
            @NonNull WorkspaceNameService workspaceNameService,
            @NonNull OpikConfiguration opikConfiguration) {
        super(config, redisson, feedbackScoreService, traceService,
                LLM_AS_JUDGE, Constants.LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
        this.spanService = spanService;
        this.toolRegistry = toolRegistry;
        this.traceCompressor = traceCompressor;
        this.workspaceNameService = workspaceNameService;
        this.opikConfiguration = opikConfiguration;
    }

    /**
     * Resolves the workspaceName for the post-scoring chain. Needed because
     * {@link com.comet.opik.domain.ExperimentService#finishExperiments(Set)} reads
     * {@code WORKSPACE_NAME} from the reactive context, but {@link TraceToScoreLlmAsJudge}
     * only carries {@code workspaceId}. {@link WorkspaceNameService#getWorkspaceName}
     * is {@code @Cacheable} keyed on workspaceId, so subsequent calls per workspace
     * are free. On lookup failure we fall back to {@code workspaceId} so the chain
     * still completes — finishing the experiment matters more than the name being pretty.
     */
    private String resolveWorkspaceName(String workspaceId) {
        try {
            return workspaceNameService.getWorkspaceName(workspaceId,
                    opikConfiguration.getAuthentication().getReactService().url());
        } catch (Exception e) {
            log.warn("Failed to resolve workspaceName for '{}', falling back to using workspace id. Error: {}",
                    workspaceId, e.getMessage());
            return workspaceId;
        }
    }

    @Override
    protected Mono<Void> processEvent(TraceToScoreLlmAsJudge message) {
        UUID experimentId = message.experimentId();
        if (experimentId != null) {
            // Resolve workspaceName lazily on subscription. ExperimentService.finishExperiments
            // (reached via decrementAndFinishIfComplete when the assertion counter hits zero) reads
            // WORKSPACE_NAME from the reactive context; without it the post-scoring chain throws
            // NoSuchElementException, the message isn't ack'd, and Redis Streams retries the whole
            // scoring run — re-running the LLM and re-inserting assertion rows.
            return super.processEvent(message)
                    .then(Mono.fromCallable(() -> resolveWorkspaceName(message.workspaceId()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(workspaceName -> testSuiteAssertionCounterService
                                    .decrementAndFinishIfComplete(message.workspaceId(), experimentId)
                                    .contextWrite(ctx -> ctx
                                            .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                            .put(RequestContext.WORKSPACE_NAME, workspaceName)
                                            .put(RequestContext.USER_NAME, message.userName()))));
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
            ChatRequest structuredRequest;
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
                    // The post-tool-loop wrap-up must use the provider-native structured-output
                    // strategy (e.g. response_format=json_schema on OpenAI). InstructionStrategy
                    // is a soft prompt and Anthropic in particular often returns conversational
                    // prose at the wrap-up turn ("Now let me check..."), which then fails JSON
                    // parsing in toFeedbackScores and yields zero scores.
                    structuredRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType(), MAX_PROMPT_FIELD_CHARS, drillDownHint);
                } else {
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType());
                    structuredRequest = scoreRequest;
                }
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for traceId '{}': \n\n{}",
                        trace.id(), exception.getMessage());
                throw exception;
            }

            if (shouldUseTools(message)) {
                // REQUIRED on the FIRST call only: forces ≥1 tool call before the model can
                // emit an answer. OpenAI judges with tool_choice=AUTO consistently skip the
                // tool loop and answer from visible context, even with explicit "you MUST
                // call tools first" guidance in the system prompt — see SupportedJudgeProvider
                // for the empirical asymmetry. Follow-up rounds in handleToolCalls switch to
                // AUTO so the model can decide when it has enough info to stop investigating;
                // a uniform REQUIRED would loop forever because the wrap-up turn would also
                // be forced to call a tool.
                scoreRequest = addToolSpecs(scoreRequest, ToolChoice.REQUIRED);
            }

            // Guarded behind isInfoEnabled() because summarizeRequest streams over the message
            // list to total up character counts; SLF4J's parameter substitution defers the
            // {} placeholder, but the helper invocation itself is still evaluated eagerly.
            if (userFacingLogger.isInfoEnabled()) {
                userFacingLogger.info("Sending traceId '{}' to LLM: {}",
                        trace.id(), summarizeRequest(scoreRequest, message));
            }

            var chatResponse = aiProxyService.scoreTrace(
                    scoreRequest, message.llmAsJudgeCode().model(), message.workspaceId());
            if (userFacingLogger.isInfoEnabled()) {
                userFacingLogger.info("Received response for traceId '{}': {}",
                        trace.id(), summarizeResponse(chatResponse));
            }

            if (shouldUseTools(message)) {
                chatResponse = handleToolCalls(chatResponse, scoreRequest, structuredRequest, message);
            }

            // When scoreNameMapping is empty (regular online scoring), names pass through unchanged.
            var parsed = OnlineScoringEngine.toFeedbackScores(chatResponse);
            OnlineScoringEngine.logSkippedNullScores(userFacingLogger, parsed, "traceId", trace.id());
            return parsed.scores().stream()
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
    ChatRequest addToolSpecs(ChatRequest request, ToolChoice toolChoice) {
        // Tool specs live inside ChatRequestParameters, so we copy the existing parameters via
        // overrideWith and layer tool specs on top — setting toolSpecifications directly on
        // ChatRequest's builder would conflict with parameters. Using toBuilder() (rather than
        // a fresh builder + .messages()) preserves any other top-level fields on ChatRequest,
        // present or future, guarding against the same "silently dropped fields" regression
        // that previously gave the initial scoring call a different shape from the final
        // structured re-issue in handleToolCalls.
        var parameters = ChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .toolSpecifications(toolRegistry.specs())
                .toolChoice(toolChoice)
                .build();
        return request.toBuilder()
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

        // Subsequent rounds use tool_choice=AUTO so the model can decide when it has enough
        // information to stop investigating. The initial call uses REQUIRED to force ≥1 tool
        // call (see evaluate() — overcomes OpenAI's bias against calling tools when it can
        // satisfy the output schema from visible context). If we kept REQUIRED on follow-ups,
        // the wrap-up turn would loop forever, since every round would be forced to invoke
        // a tool even after the model is ready to emit the final JSON.
        var followUpParameters = ChatRequestParameters.builder()
                .overrideWith(toolRequest.parameters())
                .toolChoice(ToolChoice.AUTO)
                .build();

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
                    .parameters(followUpParameters)
                    .build();

            chatResponse = aiProxyService.scoreTrace(
                    followUp, message.llmAsJudgeCode().model(), message.workspaceId());
        }

        // Force closure of the tool-using phase. Without this, the model can return prose
        // that continues the investigation ("Now let me check...") instead of the required
        // JSON, even when the request carries no tool specs. Combined with the provider-native
        // structured-output strategy on `structuredRequest`, this gives both a soft and a
        // hard signal: "stop calling tools, emit only JSON now".
        messages.add(UserMessage.from(
                "You have completed your investigation using the available tools."
                        + " Now respond with ONLY the JSON object specified in the original instructions."
                        + " Do not call any more tools. Do not include any prose, commentary, or markdown"
                        + " fences — emit only the raw JSON object."));

        var finalRequest = structuredRequest.toBuilder()
                .messages(messages)
                .build();

        return aiProxyService.scoreTrace(
                finalRequest, message.llmAsJudgeCode().model(), message.workspaceId());
    }

    /**
     * Build a sanitized one-line description of the outgoing request for the user-facing
     * log. The full {@code ChatRequest} contains the rendered system prompt, the user
     * message with the trace's input/output, and request parameters — surfacing all of it
     * in a stored log lands trace content (and any tokens or PII it carries) in clear
     * text downstream of whatever sinks the user-facing log feeds. We log shape only.
     */
    private static String summarizeRequest(ChatRequest request, TraceToScoreLlmAsJudge message) {
        int messageCount = request.messages() == null ? 0 : request.messages().size();
        int totalChars = request.messages() == null
                ? 0
                : request.messages().stream().mapToInt(m -> m.toString().length()).sum();
        int toolSpecCount = request.toolSpecifications() == null ? 0 : request.toolSpecifications().size();
        return String.format("model='%s', messages=%d (~%d chars), tools=%d, toolsEnabled=%s",
                message.llmAsJudgeCode().model().name(),
                messageCount, totalChars, toolSpecCount, shouldUseTools(message));
    }

    /**
     * Build a sanitized one-line description of the LLM response. As with the request,
     * the full {@code ChatResponse} contains the assistant text and any tool-call
     * arguments, both of which can echo trace content the model is reasoning about.
     */
    private static String summarizeResponse(ChatResponse response) {
        var ai = response.aiMessage();
        int textLength = ai.text() == null ? 0 : ai.text().length();
        int toolCallCount = ai.toolExecutionRequests() == null ? 0 : ai.toolExecutionRequests().size();
        var finishReason = response.metadata() == null ? null : response.metadata().finishReason();
        return String.format("textChars=%d, toolCalls=%d, finishReason=%s",
                textLength, toolCallCount, finishReason);
    }

    private List<Span> fetchSpans(UUID traceId, String workspaceId, String userName) {
        return spanService.getByTraceIds(Set.of(traceId))
                .collectList()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .block();
    }
}
