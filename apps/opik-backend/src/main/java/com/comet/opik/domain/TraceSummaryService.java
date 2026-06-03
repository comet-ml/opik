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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

@ImplementedBy(TraceSummaryServiceImpl.class)
public interface TraceSummaryService {
    Mono<Void> summarize(List<Trace> traces, String workspaceId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class TraceSummaryServiceImpl implements TraceSummaryService {

    private static final int MAX_CONCURRENCY = 4;

    private static final LlmAsJudgeModelParameters SUMMARY_MODEL = LlmAsJudgeModelParameters.builder()
            .name(OpenRouterModelName.Z_AI_GLM_4_7_FLASH.toString())
            .build();

    private static final String SYSTEM_PROMPT = """
            You are analyzing an AI trace. Explain what is generally happening in this trace \
            and what information the user was after. Be concise.""";

    private final @NonNull ChatCompletionService chatCompletionService;
    private final @NonNull TraceSummaryDAO traceSummaryDAO;

    @Override
    public Mono<Void> summarize(@NonNull List<Trace> traces, @NonNull String workspaceId) {
        if (traces.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(traces)
                .flatMap(trace -> summarizeTrace(trace, workspaceId), MAX_CONCURRENCY)
                .collectList()
                .flatMap(summaries -> {
                    if (summaries.isEmpty()) {
                        log.info("No trace summaries produced for workspace '{}'", workspaceId);
                        return Mono.empty();
                    }
                    log.info("Inserting '{}' trace summaries for workspace '{}'", summaries.size(), workspaceId);
                    return traceSummaryDAO.batchInsert(summaries);
                })
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId))
                .then();
    }

    private Mono<TraceSummary> summarizeTrace(Trace trace, String workspaceId) {
        return Mono.fromCallable(() -> {
            var chatResponse = chatCompletionService.scoreTrace(buildRequest(trace), SUMMARY_MODEL, workspaceId);
            return TraceSummary.builder()
                    .traceId(trace.id())
                    .projectId(trace.projectId())
                    .summary(chatResponse.aiMessage().text())
                    .build();
        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.warn("Failed to summarize traceId '{}' for workspace '{}', skipping",
                            trace.id(), workspaceId, error);
                    return Mono.empty();
                });
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
