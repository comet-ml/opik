package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.v1.events.tools.ToolExecutor;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

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
                messages, ctx(), budget, "trace-id", Map.of()).block();

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
                messages, ctx(), budget, "trace-id", Map.of()).block();

        assertThat(result).isSameAs(toolCallingResponse);
        // 10 in-loop follow-up scoreTrace calls — one per round before the cap kicks in.
        assertThat(scoreInvocations.get()).isEqualTo(ToolCallLoop.MAX_TOOL_CALL_ROUNDS);
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
                messages, ctx(), budget, "trace-id", Map.of()).block();

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
                scoreTrace, messages, ctx(), budget, "trace-id", Map.of()).block();

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
                req -> Mono.just(done), messages, ctx(), budget, "trace-id", Map.of()).block();

        // Expected messages in order: original UserMessage, AiMessage(3 tool calls),
        // ToolResult(a), ToolResult(b), ToolResult(c). Pull the names off the tool results
        // in order to verify concatMap kept the model's order.
        var resultNames = messages.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).toolName())
                .toList();
        assertThat(resultNames).containsExactly("a", "b", "c");
    }

    // --- helpers ---

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
                .projectName("p")
                .name("trace")
                .startTime(Instant.now())
                .build();
        return TraceToolContext.forActiveTrace(trace, List.of(), "ws", "user");
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
