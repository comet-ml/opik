package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.resources.v1.events.tools.TraceCompressor;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import com.google.inject.ImplementedBy;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(TraceSummaryServiceImpl.class)
public interface TraceSummaryService {
    Mono<Void> summarize(UUID traceId, String workspaceId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class TraceSummaryServiceImpl implements TraceSummaryService {

    private static final LlmAsJudgeModelParameters SUMMARY_MODEL = LlmAsJudgeModelParameters.builder()
            .name(OpenRouterModelName.Z_AI_GLM_4_7_FLASH.toString())
            .build();

    private static final String SYSTEM_PROMPT = """
            You are analyzing an AI trace, given as a possibly-truncated skeleton of the trace and its \
            spans (long values are shortened and marked [TRUNCATED ...]). Summarise concisely: \
            (1) Request — what the caller was trying to accomplish; \
            (2) Structure — the flow of spans/steps and how they connect; \
            (3) Patterns — notable repetition, tool/LLM usage, errors, or inefficiencies.""";

    private final @NonNull ChatCompletionService chatCompletionService;
    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull TraceCompressor traceCompressor;
    private final @NonNull TraceSummaryDAO traceSummaryDAO;

    @Override
    public Mono<Void> summarize(@NonNull UUID traceId, @NonNull String workspaceId) {
        // LLM/DB errors propagate so the Redis consumer (BaseRedisSubscriber) retries; only a missing
        // trace (deleted or not yet visible) is swallowed and skipped.
        return Mono.zip(
                traceService.get(traceId, true),
                spanService.getByTraceIds(Set.of(traceId)).collectList())
                .flatMap(tuple -> {
                    Trace trace = tuple.getT1();
                    List<Span> spans = tuple.getT2();
                    return Mono.fromCallable(() -> {
                        var chatResponse = chatCompletionService.scoreTrace(buildRequest(trace, spans), SUMMARY_MODEL,
                                workspaceId);
                        return TraceSummary.builder()
                                .traceId(trace.id())
                                .projectId(trace.projectId())
                                .summary(chatResponse.aiMessage().text())
                                .build();
                    }).subscribeOn(Schedulers.boundedElastic());
                })
                .flatMap(summary -> traceSummaryDAO.batchInsert(List.of(summary)))
                .onErrorResume(NotFoundException.class, error -> {
                    log.info("Trace '{}' not found for summarization, skipping", traceId);
                    return Mono.empty();
                })
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId))
                .then();
    }

    private ChatRequest buildRequest(Trace trace, List<Span> spans) {
        var payload = traceCompressor.compressForSummary(trace, spans).payload();
        return ChatRequest.builder()
                .messages(List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(payload.toString())))
                .build();
    }
}
