package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.v1.events.tools.MediaCategory;
import com.comet.opik.api.resources.v1.events.tools.MediaPayload;
import com.comet.opik.api.resources.v1.events.tools.ToolExecutor;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.llm.LlmProviderFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallLoopTest {

    private static final String TOOL_NAME = "noop";

    @Test
    void earlyReturnWhenInitialResponseHasNoToolCalls() {
        // No tool execution requests on the initial response -> the loop must return it as-is
        // without invoking scoreTrace. Used by both scorers: if the model emits the final JSON
        // immediately (no tool calls), we skip the loop. The terminal AiMessage IS appended to
        // `messages` so the downstream wrap-up can reuse the assistant's final turn in its
        // re-issue conversation history.
        ChatResponse initial = ChatResponse.builder()
                .aiMessage(AiMessage.from("final-answer"))
                .build();

        AtomicInteger scoreInvocations = new AtomicInteger();
        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        var budget = new ToolCallLoop.Budget();

        ChatResponse result = ToolCallLoop.run(
                initial, baseRequest(), followUpParams(), registry(stubTool(TOOL_NAME, "ok")),
                req -> {
                    scoreInvocations.incrementAndGet();
                    return Mono.just(initial);
                },
                messages, ctx(), budget, BudgetGuard.UNLIMITED, "trace-id", Map.of(), EvaluationRecorder.NOOP).block();

        assertThat(result).isSameAs(initial);
        assertThat(scoreInvocations.get()).isZero();
        // UserMessage("score") + the terminal AiMessage = 2 entries.
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) messages.get(1)).text()).isEqualTo("final-answer");
        assertThat(budget.cumulative).isZero();
    }

    @Test
    void capsAtMaxToolCallRoundsWhenModelKeepsRequestingTools() {
        // Model keeps emitting tool calls every round. The loop must terminate at
        // MAX_TOOL_CALL_ROUNDS (10) — scoreTrace is invoked once per round (10 in-loop
        // follow-up calls), and the cap-round response is returned without trying an 11th.
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("t").name(TOOL_NAME).arguments("{}").build();
        ChatResponse toolCallingResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq))).build();

        AtomicInteger scoreInvocations = new AtomicInteger();
        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        var budget = new ToolCallLoop.Budget();

        ChatResponse result = ToolCallLoop.run(
                toolCallingResponse, baseRequest(), followUpParams(), registry(stubTool(TOOL_NAME, "ok")),
                req -> {
                    scoreInvocations.incrementAndGet();
                    return Mono.just(toolCallingResponse);
                },
                messages, ctx(), budget, BudgetGuard.UNLIMITED, "trace-id", Map.of(), EvaluationRecorder.NOOP).block();

        assertThat(result).isSameAs(toolCallingResponse);
        // 10 in-loop follow-up scoreTrace calls — one per round before the cap kicks in.
        assertThat(scoreInvocations.get()).isEqualTo(ToolCallLoop.MAX_TOOL_CALL_ROUNDS);
    }

    @Test
    void wrapsUpEarlyOnceSpendBudgetIsReached() {
        // The model would keep calling tools forever, but a tiny per-evaluation spend budget is set.
        // Each scoreTrace response carries token usage that, once charged through the guard, exceeds
        // the budget after the very first follow-up call. The loop must then stop starting new turns
        // (shouldWrapUp() gate) instead of running to MAX_TOOL_CALL_ROUNDS.
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("t").name(TOOL_NAME).arguments("{}").build();
        ChatResponse toolCallingResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq)))
                // gpt-4o priced at $2.5/1M in + $10/1M out -> ~$1.25 for this call, far above the limit.
                .tokenUsage(OpenAiTokenUsage.builder()
                        .inputTokenCount(100_000)
                        .outputTokenCount(100_000)
                        .totalTokenCount(200_000)
                        .build())
                .build();

        var factory = mock(LlmProviderFactory.class);
        when(factory.getResolvedModelInfo("gpt-4o"))
                .thenReturn(new LlmProviderFactory.ResolvedModelInfo("gpt-4o", "openai"));
        var costGuard = BudgetGuard.create(new BigDecimal("0.01"), "gpt-4o", factory);

        AtomicInteger scoreInvocations = new AtomicInteger();
        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        var budget = new ToolCallLoop.Budget();

        // The scorer wraps each call in guard.track(...); mirror that here so the loop's gate sees spend.
        ChatResponse result = ToolCallLoop.run(
                toolCallingResponse, baseRequest(), followUpParams(), registry(stubTool(TOOL_NAME, "ok")),
                req -> {
                    scoreInvocations.incrementAndGet();
                    return costGuard.track(Mono.just(toolCallingResponse));
                },
                messages, ctx(), budget, costGuard, "trace-id", Map.of(), EvaluationRecorder.NOOP).block();

        assertThat(result).isSameAs(toolCallingResponse);
        // Round 0 fires one follow-up (charged, now over budget); round 1's gate trips and stops.
        assertThat(scoreInvocations.get()).isEqualTo(1);
        assertThat(scoreInvocations.get()).isLessThan(ToolCallLoop.MAX_TOOL_CALL_ROUNDS);
        assertThat(costGuard.shouldWrapUp()).isTrue();
    }

    @Test
    void budgetTriggeredWrapUpUsesBudgetSpecificInstruction() {
        // When the spend budget cut the run short, the final re-issue must NOT tell the model it
        // "completed" its investigation — it gets the budget-specific, best-effort-from-partial-data
        // instruction instead.
        var costGuard = tightBudgetGuard();
        var messages = runToWrapUp(costGuard);

        assertThat(costGuard.shouldWrapUp()).isTrue();
        assertThat(costGuard.wasBudgetEnforced()).isTrue();
        assertThat(lastUserMessageText(messages))
                .contains("evaluation spend budget")
                .doesNotContain("completed your investigation");
    }

    @Test
    void naturalWrapUpUsesCompletionInstruction() {
        // No budget (UNLIMITED guard never trips): the loop caps naturally and the standard
        // "investigation complete, emit JSON" wrap-up instruction is used.
        var messages = runToWrapUp(BudgetGuard.UNLIMITED);

        assertThat(lastUserMessageText(messages)).contains("completed your investigation");
    }

    @Test
    void appendsTerminalAiMessageWhenBudgetTripsOnANaturalStop() {
        // Regression: when a follow-up round both finishes naturally (no tool calls) AND tips spend
        // over the budget, the terminal AiMessage must still be appended so the wrap-up re-issues
        // with the model's final reasoning. The budget gate must not swallow a no-tool-call response.
        var costGuard = tightBudgetGuard();
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("t").name(TOOL_NAME).arguments("{}").build();
        ChatResponse round0 = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq))).build();
        // Follow-up response: no tool calls (natural stop) + usage that trips the $0.01 budget.
        ChatResponse naturalStop = ChatResponse.builder()
                .aiMessage(AiMessage.from("final reasoning"))
                .tokenUsage(OpenAiTokenUsage.builder()
                        .inputTokenCount(100_000).outputTokenCount(100_000).totalTokenCount(200_000).build())
                .build();

        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        var budget = new ToolCallLoop.Budget();

        ChatResponse result = ToolCallLoop.run(
                round0, baseRequest(), followUpParams(), registry(stubTool(TOOL_NAME, "ok")),
                req -> costGuard.track(Mono.just(naturalStop)),
                messages, ctx(), budget, costGuard, "trace-id", Map.of(), EvaluationRecorder.NOOP).block();

        assertThat(result).isSameAs(naturalStop);
        assertThat(costGuard.shouldWrapUp()).isTrue();
        // Spend crossed the limit, but the model stopped on its own via the no-tool branch — the budget
        // gate never abandoned pending tool calls, so this is NOT flagged as a budget-enforced cut-off.
        assertThat(costGuard.wasBudgetEnforced()).isFalse();
        // The natural-stop terminal AiMessage is retained as the last message (not dropped by the gate).
        assertThat(messages.getLast()).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) messages.getLast()).text()).isEqualTo("final reasoning");
    }

    @Test
    void naturalStopThatMerelyCrossesBudgetStillUsesCompletionInstruction() {
        // A model that finishes on its own (no tool calls) on the exact turn whose cost tips spend over
        // the budget "completed" its investigation — spend just happens to be over. The wrap-up must use
        // the standard completion instruction, and the guard must not report the budget as enforced, so
        // the message / user warn / budget_exceeded tag all agree (they no longer diverge on this path).
        var costGuard = tightBudgetGuard();
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("t").name(TOOL_NAME).arguments("{}").build();
        ChatResponse round0 = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq))).build();
        ChatResponse naturalStop = ChatResponse.builder()
                .aiMessage(AiMessage.from("final reasoning"))
                .tokenUsage(OpenAiTokenUsage.builder()
                        .inputTokenCount(100_000).outputTokenCount(100_000).totalTokenCount(200_000).build())
                .build();

        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        ToolCallLoop.runWithWrapUp(
                round0, baseRequest(), baseRequest(), followUpParams(), registry(stubTool(TOOL_NAME, "ok")),
                req -> costGuard.track(Mono.just(naturalStop)),
                messages, ctx(), new ToolCallLoop.Budget(), costGuard, "trace-id", Map.of(),
                EvaluationRecorder.NOOP).block();

        assertThat(costGuard.shouldWrapUp()).isTrue();
        assertThat(costGuard.wasBudgetEnforced()).isFalse();
        assertThat(lastUserMessageText(messages))
                .contains("completed your investigation")
                .doesNotContain("evaluation spend budget");
    }

    @Test
    void flagsBudgetExceededOnTheRecorderWhenTheBudgetTripsMidLoop() {
        // The recorder must be flagged at the point the budget gate fires (so the monitoring trace is
        // tagged even if the chain errors afterwards), not inferred later by the scorer.
        var costGuard = tightBudgetGuard();
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("t").name(TOOL_NAME).arguments("{}").build();
        ChatResponse toolCalling = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq)))
                .tokenUsage(OpenAiTokenUsage.builder()
                        .inputTokenCount(100_000).outputTokenCount(100_000).totalTokenCount(200_000).build())
                .build();

        EvaluationRecorder recorder = mock(EvaluationRecorder.class);
        when(recorder.recordToolCall(anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(2));

        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        ToolCallLoop.run(toolCalling, baseRequest(), followUpParams(), registry(stubTool(TOOL_NAME, "ok")),
                req -> costGuard.track(Mono.just(toolCalling)),
                messages, ctx(), new ToolCallLoop.Budget(), costGuard, "trace-id", Map.of(), recorder).block();

        verify(recorder).flagBudgetExceeded();
        // The recorder tag and the guard's cut-short flag are set together at the same gate.
        assertThat(costGuard.wasBudgetEnforced()).isTrue();
    }

    @Test
    void doesNotFlagBudgetExceededOnANaturalStopUnderBudget() {
        // Model finishes on its own while under budget — the budget gate never fires, so the recorder
        // must NOT be flagged (a natural stop is not a budget wrap-up).
        ChatResponse naturalStop = ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
        EvaluationRecorder recorder = mock(EvaluationRecorder.class);

        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        ToolCallLoop.run(naturalStop, baseRequest(), followUpParams(), registry(stubTool(TOOL_NAME, "ok")),
                req -> Mono.just(naturalStop), messages, ctx(), new ToolCallLoop.Budget(),
                BudgetGuard.UNLIMITED, "trace-id", Map.of(), recorder).block();

        verify(recorder, never()).flagBudgetExceeded();
    }

    @Test
    void budgetExhaustionReturnsSentinelWithoutDispatchingThroughRegistry() {
        // First tool call returns a payload just under the cap; the second round's tool
        // dispatch must see cumulative >= cap and skip the registry, returning the
        // budget-exhausted sentinel as the tool result. The registry counter proves we
        // never invoke the executor a second time.
        AtomicInteger registryDispatches = new AtomicInteger();
        long longResultLen = ToolCallLoop.CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS;
        String longResult = "x".repeat((int) longResultLen);

        ToolExecutor counting = new ToolExecutor() {
            @Override
            public String name() {
                return TOOL_NAME;
            }

            @Override
            public ToolSpecification spec() {
                return stubSpec(TOOL_NAME);
            }

            @Override
            public Mono<String> execute(String arguments, TraceToolContext c) {
                registryDispatches.incrementAndGet();
                return Mono.just(longResult);
            }
        };

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("t").name(TOOL_NAME).arguments("{}").build();
        ChatResponse toolCallingResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq))).build();
        ChatResponse finalResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("done")).build();

        // Round 0 -> tool dispatched (registry counter = 1, budget filled).
        // Follow-up scoreTrace returns another tool-calling response, driving round 1.
        // Round 1 -> budget is already at cap, dispatch is skipped (counter still 1),
        // sentinel is returned. Follow-up scoreTrace then returns finalResponse — no
        // tool calls — and the loop exits.
        var responses = new ArrayList<>(List.of(toolCallingResponse, finalResponse));
        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        var budget = new ToolCallLoop.Budget();

        ChatResponse result = ToolCallLoop.run(
                toolCallingResponse, baseRequest(), followUpParams(), registry(counting),
                req -> Mono.just(responses.removeFirst()),
                messages, ctx(), budget, BudgetGuard.UNLIMITED, "trace-id", Map.of(), EvaluationRecorder.NOOP).block();

        assertThat(result).isSameAs(finalResponse);
        assertThat(registryDispatches.get()).isEqualTo(1);
        // Round 1's tool result is the sentinel — find it in the message list and confirm.
        var sentinelHits = messages.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage trm
                        && trm.text().contains("budget")
                        && trm.text().contains("exhausted"))
                .count();
        assertThat(sentinelHits).isEqualTo(1L);
        assertThat(budget.exhaustedLogged).isTrue();
    }

    @Test
    void followUpRequestsCarryDefensiveMessageCopies() {
        // Per-round follow-up requests must snapshot the message list, NOT share a reference
        // to the in-flight one. Otherwise a later .add into `messages` (either by the loop
        // itself in a subsequent round, or by the caller after the loop ends) would
        // retroactively mutate what an async chat client sees for an earlier round's request.
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("t").name(TOOL_NAME).arguments("{}").build();
        ChatResponse round0 = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq))).build();
        ChatResponse round1 = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq))).build();
        ChatResponse done = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok")).build();

        var capturedRequests = new ArrayList<ChatRequest>();
        var responses = new ArrayList<>(List.of(round1, done));
        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        var budget = new ToolCallLoop.Budget();

        Function<ChatRequest, Mono<ChatResponse>> scoreTrace = req -> {
            capturedRequests.add(req);
            return Mono.just(responses.removeFirst());
        };

        ToolCallLoop.run(round0, baseRequest(), followUpParams(), registry(stubTool(TOOL_NAME, "res")),
                scoreTrace, messages, ctx(), budget, BudgetGuard.UNLIMITED, "trace-id", Map.of(),
                EvaluationRecorder.NOOP).block();

        // Two follow-up calls fired: one after round 0's tools, one after round 1's.
        assertThat(capturedRequests).hasSize(2);
        // Final loop state: original UserMessage + 2 rounds × (AiMessage + ToolResult) +
        // the terminal "done" AiMessage appended in the no-tool-calls early return = 6.
        assertThat(messages).hasSize(6);

        // Each captured request must hold a distinct list instance — not an alias of the
        // caller-side `messages` and not an alias of any earlier captured request.
        assertThat(capturedRequests.get(0).messages()).isNotSameAs(messages);
        assertThat(capturedRequests.get(1).messages()).isNotSameAs(messages);
        assertThat(capturedRequests.get(0).messages())
                .isNotSameAs(capturedRequests.get(1).messages());

        // The first captured request was sent after round 0 only, so its snapshot must be
        // strictly smaller than the second one's snapshot.
        assertThat(capturedRequests.get(0).messages())
                .hasSizeLessThan(capturedRequests.get(1).messages().size());

        // Snapshot the sizes, then mutate `messages` after the loop completes. The captured
        // requests' message lists must remain stable — that's the real defensive-copy contract.
        int size0 = capturedRequests.get(0).messages().size();
        int size1 = capturedRequests.get(1).messages().size();
        messages.add(UserMessage.from("post-loop mutation"));
        assertThat(capturedRequests.get(0).messages()).hasSize(size0);
        assertThat(capturedRequests.get(1).messages()).hasSize(size1);
    }

    @Test
    void multipleToolCallsInOneRoundExecuteInOrder() {
        // OpenAI requires tool-result messages to follow their parent AiMessage in the same
        // order the model emitted them. concatMap (used internally) preserves that ordering;
        // verify by emitting three tool calls in a single round and asserting the result
        // messages land in the messages list in that exact order.
        var orderedNames = List.of("a", "b", "c");
        var toolReqs = orderedNames.stream()
                .map(n -> ToolExecutionRequest.builder().id(n).name(n).arguments("{}").build())
                .toList();

        ChatResponse round0 = ChatResponse.builder()
                .aiMessage(AiMessage.from(toolReqs)).build();
        ChatResponse done = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok")).build();

        Set<ToolExecutor> tools = Set.of(stubTool("a", "result-a"), stubTool("b", "result-b"),
                stubTool("c", "result-c"));
        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        var budget = new ToolCallLoop.Budget();

        ToolCallLoop.run(round0, baseRequest(), followUpParams(), new ToolRegistry(tools),
                req -> Mono.just(done), messages, ctx(), budget, BudgetGuard.UNLIMITED, "trace-id", Map.of(),
                EvaluationRecorder.NOOP).block();

        // Expected messages in order: original UserMessage, AiMessage(3 tool calls),
        // ToolResult(a), ToolResult(b), ToolResult(c). Pull the names off the tool results
        // in order to verify concatMap kept the model's order.
        var resultNames = messages.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).toolName())
                .toList();
        assertThat(resultNames).containsExactly("a", "b", "c");
    }

    @Test
    void stagedMediaIsAppendedAsUserMessageAfterToolResults() {
        // A tool (get_attachment) that stages media must result in ONE multimodal UserMessage
        // appended AFTER the round's ToolExecutionResultMessage(s) — the assistant(tool_calls)
        // → tool results → user(media) ordering both providers require. The text receipt the
        // tool returns is the tool-result message; the media rides in the trailing UserMessage.
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("t").name(TOOL_NAME).arguments("{}").build();
        ChatResponse round0 = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq))).build();
        ChatResponse done = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok")).build();

        ToolExecutor mediaTool = new ToolExecutor() {
            @Override
            public String name() {
                return TOOL_NAME;
            }

            @Override
            public ToolSpecification spec() {
                return stubSpec(TOOL_NAME);
            }

            @Override
            public Mono<String> execute(String arguments, TraceToolContext c) {
                String fileName = "img-" + RandomStringUtils.secure().nextAlphanumeric(8) + ".png";
                String base64 = RandomStringUtils.secure().nextAlphanumeric(16);
                c.stageMedia(MediaPayload.ofBase64(fileName, "image/png", MediaCategory.IMAGE, 0L, base64));
                return Mono.just("{\"loaded\":true}");
            }
        };

        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        var budget = new ToolCallLoop.Budget();

        ToolCallLoop.run(round0, baseRequest(), followUpParams(), registry(mediaTool),
                req -> Mono.just(done), messages, ctx(), budget, BudgetGuard.UNLIMITED, "trace-id", Map.of(),
                EvaluationRecorder.NOOP).block();

        // Order: UserMessage(score), AiMessage(tool calls), ToolResult, UserMessage(media),
        // terminal AiMessage(done) = 5.
        assertThat(messages).hasSize(5);
        assertThat(messages.get(2)).isInstanceOf(ToolExecutionResultMessage.class);
        assertThat(messages.get(3)).isInstanceOf(UserMessage.class);
        UserMessage mediaMessage = (UserMessage) messages.get(3);
        assertThat(mediaMessage.contents()).anyMatch(content -> content instanceof ImageContent);
    }

    @Test
    void noMediaMessageAppendedWhenToolsStageNothing() {
        // Tools that stage no media must not introduce a trailing UserMessage — the drain is a
        // no-op when nothing was staged.
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("t").name(TOOL_NAME).arguments("{}").build();
        ChatResponse round0 = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq))).build();
        ChatResponse done = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok")).build();

        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        var budget = new ToolCallLoop.Budget();

        ToolCallLoop.run(round0, baseRequest(), followUpParams(), registry(stubTool(TOOL_NAME, "res")),
                req -> Mono.just(done), messages, ctx(), budget, BudgetGuard.UNLIMITED, "trace-id", Map.of(),
                EvaluationRecorder.NOOP).block();

        // UserMessage(score), AiMessage(tool calls), ToolResult, terminal AiMessage(done) = 4.
        // No extra UserMessage between the tool result and the terminal AiMessage.
        assertThat(messages).hasSize(4);
        assertThat(messages.stream().filter(m -> m instanceof UserMessage)).hasSize(1);
    }

    // --- helpers ---

    // A guard with a $0.01 limit and zero recorded spend: NOT over budget at return time — it only
    // trips after the first tracked follow-up response (which carries ~200k tokens) is charged.
    private static BudgetGuard tightBudgetGuard() {
        var factory = mock(LlmProviderFactory.class);
        when(factory.getResolvedModelInfo("gpt-4o"))
                .thenReturn(new LlmProviderFactory.ResolvedModelInfo("gpt-4o", "openai"));
        return BudgetGuard.create(new BigDecimal("0.01"), "gpt-4o", factory);
    }

    // Runs runWithWrapUp with a model that always requests a tool, so the loop only ends via the
    // budget gate (over-budget guard) or the MAX_ROUNDS cap (UNLIMITED). Returns the message list
    // whose trailing UserMessage is the wrap-up instruction that was chosen.
    private ArrayList<ChatMessage> runToWrapUp(BudgetGuard costGuard) {
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("t").name(TOOL_NAME).arguments("{}").build();
        ChatResponse toolCallingResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq)))
                .tokenUsage(OpenAiTokenUsage.builder()
                        .inputTokenCount(100_000).outputTokenCount(100_000).totalTokenCount(200_000).build())
                .build();
        var messages = new ArrayList<ChatMessage>(List.of(UserMessage.from("score")));
        var budget = new ToolCallLoop.Budget();
        ToolCallLoop.runWithWrapUp(
                toolCallingResponse, baseRequest(), baseRequest(), followUpParams(),
                registry(stubTool(TOOL_NAME, "ok")),
                req -> costGuard.track(Mono.just(toolCallingResponse)),
                messages, ctx(), budget, costGuard, "trace-id", Map.of(), EvaluationRecorder.NOOP).block();
        return messages;
    }

    private static String lastUserMessageText(List<ChatMessage> messages) {
        return messages.stream()
                .filter(UserMessage.class::isInstance)
                .map(message -> ((UserMessage) message).singleText())
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private static ChatRequest baseRequest() {
        return ChatRequest.builder()
                .messages(UserMessage.from("score"))
                .toolSpecifications(stubSpec(TOOL_NAME))
                .build();
    }

    private static ChatRequestParameters followUpParams() {
        return ChatRequestParameters.builder().toolChoice(ToolChoice.AUTO).build();
    }

    private static ToolRegistry registry(ToolExecutor tool) {
        return new ToolRegistry(Set.of(tool));
    }

    private static TraceToolContext ctx() {
        Trace trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName("p-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .name("trace-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .startTime(Instant.now())
                .build();
        return TraceToolContext.forActiveTrace(trace, List.of(),
                "ws-" + RandomStringUtils.secure().nextAlphanumeric(8),
                "user-" + RandomStringUtils.secure().nextAlphanumeric(8));
    }

    private static ToolSpecification stubSpec(String name) {
        return ToolSpecification.builder()
                .name(name)
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    private static ToolExecutor stubTool(String toolName, String result) {
        return new ToolExecutor() {
            @Override
            public String name() {
                return toolName;
            }

            @Override
            public ToolSpecification spec() {
                return stubSpec(toolName);
            }

            @Override
            public Mono<String> execute(String arguments, TraceToolContext c) {
                return Mono.just(result);
            }
        };
    }
}
