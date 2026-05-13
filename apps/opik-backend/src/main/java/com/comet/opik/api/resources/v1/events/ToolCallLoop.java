package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
            @NonNull String logIdValue,
            @NonNull Map<String, String> mdc) {
        return toolCallLoop(0, initialResponse, toolRequest, followUpParameters, toolRegistry,
                scoreTrace, messages, ctx, budget, logIdValue, mdc);
    }

    private static Mono<ChatResponse> toolCallLoop(int round, ChatResponse currentResponse,
            ChatRequest toolRequest, ChatRequestParameters followUpParameters,
            ToolRegistry toolRegistry, Function<ChatRequest, Mono<ChatResponse>> scoreTrace,
            ArrayList<ChatMessage> messages, TraceToolContext ctx, Budget budget,
            String logIdValue, Map<String, String> mdc) {
        if (round >= MAX_TOOL_CALL_ROUNDS) {
            return Mono.just(currentResponse);
        }
        if (!currentResponse.aiMessage().hasToolExecutionRequests()) {
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
                            toolRegistry, ctx, budget, logIdValue, mdc));

            return roundResults
                    .doOnNext(messages::add)
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
                            logIdValue, mdc));
        });
    }

    private static Mono<ToolExecutionResultMessage> executeToolOrBudgetExhausted(int round,
            ToolExecutionRequest toolExecRequest, ToolRegistry toolRegistry, TraceToolContext ctx,
            Budget budget, String logIdValue, Map<String, String> mdc) {
        // Re-apply MDC so the slf4j tags (workspace_id, trace/thread id, rule_id) follow the
        // tool-loop log lines — the reactive chain may have hopped threads since the scorer's
        // sync prep step set MDC.
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
        }
        return toolRegistry.execute(toolExecRequest.name(), toolExecRequest.arguments(), ctx)
                .map(result -> {
                    budget.cumulative += result.length();
                    return ToolExecutionResultMessage.from(toolExecRequest, result);
                });
    }
}
