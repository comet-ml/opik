package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

@ImplementedBy(TraceSummaryServiceImpl.class)
public interface TraceSummaryService {
    Mono<Void> summarize(Trace trace, String workspaceId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class TraceSummaryServiceImpl implements TraceSummaryService {

    private static final LlmAsJudgeModelParameters SUMMARY_MODEL = LlmAsJudgeModelParameters.builder()
            .name(OpenRouterModelName.Z_AI_GLM_4_7_FLASH.toString())
            .build();

    private static final String SYSTEM_PROMPT = """
            You are analyzing an AI trace. Explain what is generally happening in this trace \
            and what information the user was after. Be concise.""";

    private final @NonNull ChatCompletionService chatCompletionService;
    private final @NonNull TraceSummaryDAO traceSummaryDAO;

    @Override
    public Mono<Void> summarize(@NonNull Trace trace, @NonNull String workspaceId) {
        // Errors propagate intentionally so the Redis consumer (BaseRedisSubscriber) can retry the message.
        return Mono.fromCallable(() -> {
            var chatResponse = chatCompletionService.scoreTrace(buildRequest(trace), SUMMARY_MODEL, workspaceId);
            return TraceSummary.builder()
                    .traceId(trace.id())
                    .projectId(trace.projectId())
                    .summary(chatResponse.aiMessage().text())
                    .build();
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(summary -> traceSummaryDAO.batchInsert(List.of(summary)))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId))
                .then();
    }

    private ChatRequest buildRequest(Trace trace) {
        var userContent = "Input:%n%s%n%nOutput:%n%s".formatted(asText(trace.input()), asText(trace.output()));
        return ChatRequest.builder()
                .messages(List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userContent)))
                .build();
    }

    private String asText(JsonNode node) {
        return Optional.ofNullable(node).map(JsonNode::toString).orElse("");
    }
}
