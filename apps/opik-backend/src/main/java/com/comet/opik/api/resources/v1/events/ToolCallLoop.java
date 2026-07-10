package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.resources.v1.events.tools.MediaMessageBuilder;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;

import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * Reactive tool-call loop shared by the trace- and thread-level LLM-as-judge scorers.
 *
 * <p>Both scorers' {@code handleToolCalls} need the same per-round shape:
 * <ol>
 *   <li>Append the current AI message to the running message list.</li>
 *   <li>Execute each tool the model invoked in order (concatMap, not flatMap, so the
 *       {@code ToolExecutionResultMessage}s follow their parent AI message in the same
 *       order the model emitted them — OpenAI requires this).</li>
 *   <li>Snapshot the message list, build the follow-up request, score it via the
 *       caller-supplied scorer function.</li>
 *   <li>Recurse on the response.</li>
 * </ol>
 *
 * <p>What differs between the two scorers is just the message type
 * ({@code TraceToScoreLlmAsJudge} vs {@code TraceThreadToScoreLlmAsJudge}) — the loop
 * never inspects that. The caller passes the {@code scoreTrace} lambda which closes
 * over its own message and threads it through to {@code ChatCompletionService}.
 *
 * <p>Stateful inputs ({@code messages}, {@code budget}) are caller-allocated so they
 * outlive the loop and can be inspected after it ends (the trace scorer appends the
 * forcing user-message and re-scores; the thread scorer does the same).
 */
@Slf4j
final class ToolCallLoop {

    static final int MAX_TOOL_CALL_ROUNDS = 10;

    /**
     * Cumulative cap on tool-result string length across the whole tool-call loop. Beyond
     * this, further tool calls return a budget-exhausted sentinel so the judge has to compose
     * its final answer from already-gathered data. Sized for ~2 chars/token (random/code
     * worst case) so even adversarial inputs stay under a 128 K-token window after accounting
     * for system prompt, tool specs, user prompt, and assistant turns:
     *
     * <p>{@code 150_000 chars / 2 chars/tok ≈ 75 K tok} of tool results, leaving ≈ 50 K tok
     * of headroom for the rest of the conversation. Pairs with
     * {@link com.comet.opik.api.resources.v1.events.tools.ReadTool#OUTPUT_SAFETY_CHARS}
     * (per-call cap with auto-tier-downgrade): up to ~7 max-cap reads fit before this cap.
     */
    static final long CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS = 150_000L;

    private static final String BUDGET_EXHAUSTED_MESSAGE = "{\"error\": \"Cumulative tool-output"
            + " budget (%d chars) exhausted for this judgment; further tool calls return this"
            + " error. Respond now with your best assessment from the data already gathered.\"}";

    /**
     * Force closure of the tool-using phase. Without this, the model can return prose that
     * continues the investigation ("Now let me check...") instead of the required JSON, even
     * when the request carries no tool specs. Combined with the provider-native structured-
     * output strategy on {@code structuredRequest}, gives both a soft and a hard signal:
     * "stop calling tools, emit only JSON now".
     */
    private static final String WRAP_UP_USER_MESSAGE = "You have completed your investigation"
            + " using the available tools. Now respond with ONLY the JSON object specified in"
            + " the original instructions. Do not call any more tools. Do not include any"
            + " prose, commentary, or markdown fences — emit only the raw JSON object.";

    /**
     * Budget-triggered wrap-up. Unlike {@link #WRAP_UP_USER_MESSAGE}, this does not claim the
     * investigation is complete — the spend budget cut it short. It tells the model to stop now and
     * give a best-effort verdict from the partial data gathered so far, so the emitted JSON reflects
     * an acknowledged-incomplete assessment rather than a falsely-confident "finished" one.
     */
    private static final String BUDGET_WRAP_UP_USER_MESSAGE = "The evaluation spend budget for this"
            + " judgment has been reached, so you must stop investigating now even though your"
            + " analysis may be incomplete. Do not call any more tools. Based only on the"
            + " information you have already gathered, give your best-effort assessment and respond"
            + " with ONLY the JSON object specified in the original instructions. Do not include any"
            + " prose, commentary, or markdown fences — emit only the raw JSON object.";

    private ToolCallLoop() {
    }

    /**
     * Per-evaluation tool-output budget state. Mutated sequentially via concatMap, so a plain
     * non-volatile {@code long} + {@code boolean} is safe — no concurrent access.
     */
    static final class Budget {
        long cumulative = 0L;
        boolean exhaustedLogged = false;
    }

    /**
     * Run the tool-call loop. Returns the LAST {@link ChatResponse} the loop produced —
     * either the first response with no tool calls (model decided to stop) or the response
     * after {@link #MAX_TOOL_CALL_ROUNDS}. Caller is responsible for the post-loop wrap-up
     * (force-closing the tool-using phase + final structured re-issue).
     *
     * @param initialResponse    response from the initial scoreTrace call that may carry tool calls
     * @param toolRequest        the request shape to reuse for follow-up rounds (carries the
     *                           tool specs)
     * @param followUpParameters parameters to attach to follow-up rounds — typically
     *                           {@code tool_choice=AUTO} so the model can stop when ready
     * @param toolRegistry       dispatcher for tool execution
     * @param scoreTrace         function that calls the LLM. Typically
     *                           {@code Mono.fromCallable(...).subscribeOn(boundedElastic())}
     *                           so the blocking sync chat call doesn't pin the workers pool.
     * @param messages           in-flight message list. The loop appends AI messages + tool
     *                           result messages to it; caller can inspect it after the loop.
     * @param ctx                tool execution context (trace-scoped or thread-scoped)
     * @param budget             cumulative tool-output budget; survives across rounds
     * @param logIdValue         entity identifier embedded in log lines so operators can grep
     *                           by trace/thread id when chasing a specific run
     * @param mdc                MDC tags applied to the internal log statements
     */
    static Mono<ChatResponse> run(
            @NonNull ChatResponse initialResponse,
            @NonNull ChatRequest toolRequest,
            @NonNull ChatRequestParameters followUpParameters,
            @NonNull ToolRegistry toolRegistry,
            @NonNull Function<ChatRequest, Mono<ChatResponse>> scoreTrace,
            @NonNull ArrayList<ChatMessage> messages,
            @NonNull TraceToolContext ctx,
            @NonNull Budget budget,
            @NonNull BudgetGuard costGuard,
            @NonNull String logIdValue,
            @NonNull Map<String, String> mdc,
            @NonNull EvaluationRecorder recorder) {
        return toolCallLoop(0, initialResponse, toolRequest, followUpParameters, toolRegistry,
                scoreTrace, messages, ctx, budget, costGuard, logIdValue, mdc, recorder);
    }

    /**
     * Wraps {@link #run} with the post-loop closure that both scorers need: append a forcing
     * user-message ({@link #WRAP_UP_USER_MESSAGE}), rebuild a request from
     * {@code structuredRequest} (no tool specs) with the snapshotted message list, and re-issue
     * via {@code scoreTrace} to get the final structured JSON.
     *
     * <p>{@code structuredRequest} is the no-tools shape from {@code prepareEvaluation} used
     * for the final structured re-issue; {@code toolRequest} (carrying tool specs) is reused
     * for the in-loop rounds.
     */
    static Mono<ChatResponse> runWithWrapUp(
            @NonNull ChatResponse initialResponse,
            @NonNull ChatRequest toolRequest,
            @NonNull ChatRequest structuredRequest,
            @NonNull ChatRequestParameters followUpParameters,
            @NonNull ToolRegistry toolRegistry,
            @NonNull Function<ChatRequest, Mono<ChatResponse>> scoreTrace,
            @NonNull ArrayList<ChatMessage> messages,
            @NonNull TraceToolContext ctx,
            @NonNull Budget budget,
            @NonNull BudgetGuard costGuard,
            @NonNull String logIdValue,
            @NonNull Map<String, String> mdc,
            @NonNull EvaluationRecorder recorder) {
        return run(initialResponse, toolRequest, followUpParameters, toolRegistry, scoreTrace,
                messages, ctx, budget, costGuard, logIdValue, mdc, recorder)
                .flatMap(loopFinalResponse -> {
                    // Budget-triggered wrap-up gets a distinct instruction: the run was cut short, so
                    // ask for a best-effort verdict from partial data rather than telling the model it
                    // "completed" its investigation. Keyed on wasBudgetEnforced() (the gate actually
                    // abandoned pending tool calls), NOT shouldWrapUp() (mere spend >= limit): a model
                    // that stopped naturally on the turn its cost tipped over the limit still "completed"
                    // its investigation and must get the standard instruction.
                    var wrapUpMessage = costGuard.wasBudgetEnforced()
                            ? BUDGET_WRAP_UP_USER_MESSAGE
                            : WRAP_UP_USER_MESSAGE;
                    messages.add(UserMessage.from(wrapUpMessage));
                    var finalRequest = structuredRequest.toBuilder()
                            .messages(new ArrayList<>(messages))
                            .build();
                    return scoreTrace.apply(finalRequest);
                });
    }

    private static Mono<ChatResponse> toolCallLoop(int round, ChatResponse currentResponse,
            ChatRequest toolRequest, ChatRequestParameters followUpParameters,
            ToolRegistry toolRegistry, Function<ChatRequest, Mono<ChatResponse>> scoreTrace,
            ArrayList<ChatMessage> messages, TraceToolContext ctx, Budget budget, BudgetGuard costGuard,
            String logIdValue, Map<String, String> mdc, EvaluationRecorder recorder) {
        // Model stopped on its own (no tool requests) — append its terminal AiMessage and finish,
        // regardless of round count or spend budget. There are no pending tool calls to leave
        // unfulfilled here, so appending is always safe; the downstream wrap-up (runWithWrapUp) then
        // re-issues the structured request WITH the assistant's last turn in the conversation
        // history. Checked before the round/budget gate below so a response that both finishes
        // naturally AND tips over the budget still keeps its final reasoning turn (otherwise the
        // wrap-up "emit only JSON" message would be appended in a vacuum).
        if (!currentResponse.aiMessage().hasToolExecutionRequests()) {
            messages.add(currentResponse.aiMessage());
            return Mono.just(currentResponse);
        }
        // Between agent turns: stop starting new turns at the round cap or once the spend budget is
        // reached, and let runWithWrapUp do its final tools-stripped call (the intended, bounded
        // overshoot). Don't append: the response here carries unfulfilled tool_execution_requests
        // (we're abandoning them). An AiMessage with tool_calls but no matching
        // ToolExecutionResultMessage produces a malformed sequence that OpenAI / Anthropic reject.
        // runWithWrapUp will bridge via the forcing user message.
        if (round >= MAX_TOOL_CALL_ROUNDS || costGuard.shouldWrapUp()) {
            if (costGuard.shouldWrapUp() && round < MAX_TOOL_CALL_ROUNDS) {
                // The spend budget (not the round cap) is cutting this agentic run short, here at the
                // authoritative point the gate abandons pending tool calls. This drives all three
                // budget signals off one event: markBudgetEnforced() is the source the wrap-up
                // instruction and the scorer's user-facing warn key off, and flagBudgetExceeded() tags
                // the monitoring trace even if the wrap-up/scoring chain errors afterwards. A natural
                // stop that merely crossed spend reaches the no-tool branch above (never here), so it is
                // not mislabelled by any of the three. Idempotent — the gate trips once.
                try (var logContext = wrapWithMdc(mdc)) {
                    // debug (the user-facing warn in the scorer is the primary signal); wrapped in MDC so
                    // it carries the same workspace_id / rule_id tags as the other tool-loop log lines.
                    log.debug("Evaluation spend budget reached for '{}' (spent '{}' of '{}' USD); wrapping up",
                            logIdValue, costGuard.spentUsd(), costGuard.limitUsd());
                }
                costGuard.markBudgetEnforced();
                recorder.flagBudgetExceeded();
            }
            return Mono.just(currentResponse);
        }

        // Defer all side effects (messages.add, tool executions, follow-up scoreTrace) until
        // subscription. The early returns above are pure Mono.just and stay outside the defer.
        return Mono.defer(() -> {
            messages.add(currentResponse.aiMessage());

            // concatMap (not flatMap) so tool executions in this round preserve order — the
            // ToolExecutionResultMessages must follow their parent AiMessage in the same
            // order the model emitted them. OpenAI rejects out-of-order tool results.
            Flux<ToolExecutionResultMessage> roundResults = Flux
                    .fromIterable(currentResponse.aiMessage().toolExecutionRequests())
                    .concatMap(toolExecRequest -> executeToolOrBudgetExhausted(round, toolExecRequest,
                            toolRegistry, ctx, budget, logIdValue, mdc, recorder));

            return roundResults
                    .doOnNext(messages::add)
                    // After every ToolExecutionResultMessage for this round is in place (so no
                    // tool_call is left unanswered), append any media the tools staged as ONE
                    // multimodal UserMessage. Ordering — assistant(tool_calls) → tool results →
                    // user(media) — is valid for OpenAI/Anthropic and mirrors the wrap-up user
                    // message. Runs inside the executed branch only, so the MAX_TOOL_CALL_ROUNDS
                    // early-return never leaves staged media dangling.
                    .then(Mono.fromRunnable(() -> {
                        if (ctx.hasPendingMedia()) {
                            messages.add(MediaMessageBuilder.build(ctx.drainPendingMedia()));
                        }
                    }))
                    .then(Mono.defer(() -> {
                        // Defensive copy: ChatRequestBuilder stores the list by reference, so a later
                        // iteration mutating `messages` would retroactively change what an async chat
                        // client sees in this round's request. Snapshot per round.
                        var followUp = toolRequest.toBuilder()
                                .messages(new ArrayList<>(messages))
                                .parameters(followUpParameters)
                                .build();
                        return scoreTrace.apply(followUp);
                    }))
                    .flatMap(nextResponse -> toolCallLoop(round + 1, nextResponse, toolRequest,
                            followUpParameters, toolRegistry, scoreTrace, messages, ctx, budget,
                            costGuard, logIdValue, mdc, recorder));
        });
    }

    private static Mono<ToolExecutionResultMessage> executeToolOrBudgetExhausted(int round,
            ToolExecutionRequest toolExecRequest, ToolRegistry toolRegistry, TraceToolContext ctx,
            Budget budget, String logIdValue, Map<String, String> mdc, EvaluationRecorder recorder) {
        // Re-apply MDC so the slf4j tags (workspace_id, trace/thread id, rule_id) follow the
        // tool-loop log lines — the reactive chain may have hopped threads since the scorer's
        // sync prep step set MDC. The toolRegistry.execute() call lives INSIDE this scope so
        // its synchronous logs (e.g. ToolRegistry's "Unknown tool requested by judge" warn)
        // carry the same tags. Async logs emitted from inside the tool's subscribed Mono still
        // won't have MDC — that needs reactor-context propagation (a larger fix).
        try (var logContext = wrapWithMdc(mdc)) {
            log.debug("Tool call round '{}' for '{}': tool '{}'",
                    round, logIdValue, toolExecRequest.name());
            if (budget.cumulative >= CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS) {
                if (!budget.exhaustedLogged) {
                    log.warn("Tool-output budget '{}' chars exhausted for '{}';"
                            + " subsequent tool calls return budget-exhausted sentinel",
                            CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS, logIdValue);
                    budget.exhaustedLogged = true;
                }
                return Mono.just(ToolExecutionResultMessage.from(toolExecRequest,
                        BUDGET_EXHAUSTED_MESSAGE.formatted(CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS)));
            }
            // Record the execution as a monitoring tool span (OPIK-6994); NOOP when tracing is off.
            // Wraps execution so the span captures arguments/result/timing without altering the
            // budget accounting below.
            return recorder.recordToolCall(toolExecRequest.name(), toolExecRequest.arguments(),
                    toolRegistry.execute(toolExecRequest.name(), toolExecRequest.arguments(), ctx))
                    .map(result -> {
                        budget.cumulative += result.length();
                        return ToolExecutionResultMessage.from(toolExecRequest, result);
                    });
        }
    }
}
