package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceSummaryServiceTest {

    private static final String WORKSPACE_ID = "workspace-" + UUID.randomUUID();

    private TraceSummaryServiceImpl service;

    @Mock
    private ChatCompletionService chatCompletionService;

    @Mock
    private TraceSummaryDAO traceSummaryDAO;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @BeforeEach
    void setUp() {
        service = new TraceSummaryServiceImpl(chatCompletionService, traceSummaryDAO);
    }

    @Test
    @DisplayName("when traces are empty, then do nothing")
    void summarize__whenTracesAreEmpty__thenDoNothing() {
        service.summarize(List.of(), WORKSPACE_ID).block();

        verifyNoInteractions(chatCompletionService);
        verifyNoInteractions(traceSummaryDAO);
    }

    @Test
    @DisplayName("when traces are summarized, then call the model and batch insert the results")
    void summarize__whenTracesAreSummarized__thenCallModelAndInsert() {
        var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\":\"how do I reset my password\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\":\"go to settings\"}"))
                .build();

        when(chatCompletionService.scoreTrace(any(), any(), eq(WORKSPACE_ID)))
                .thenReturn(chatResponse("The user wanted to reset their password."));
        when(traceSummaryDAO.batchInsert(any())).thenReturn(Mono.just(1L));

        service.summarize(List.of(trace), WORKSPACE_ID).block();

        var requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        var modelCaptor = ArgumentCaptor.forClass(LlmAsJudgeModelParameters.class);
        verify(chatCompletionService).scoreTrace(requestCaptor.capture(), modelCaptor.capture(), eq(WORKSPACE_ID));

        assertThat(modelCaptor.getValue().name()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(userText(requestCaptor.getValue()))
                .contains("how do I reset my password")
                .contains("go to settings");

        var summariesCaptor = ArgumentCaptor.forClass(List.class);
        verify(traceSummaryDAO).batchInsert(summariesCaptor.capture());

        List<TraceSummary> summaries = summariesCaptor.getValue();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().traceId()).isEqualTo(trace.id());
        assertThat(summaries.getFirst().projectId()).isEqualTo(trace.projectId());
        assertThat(summaries.getFirst().summary()).isEqualTo("The user wanted to reset their password.");
    }

    @Test
    @DisplayName("when one trace fails, then skip it and insert the rest")
    void summarize__whenOneTraceFails__thenSkipItAndInsertTheRest() {
        var goodTrace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\":\"good-input\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\":\"good-output\"}"))
                .build();
        var badTrace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\":\"bad-input\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\":\"bad-output\"}"))
                .build();

        when(chatCompletionService.scoreTrace(any(), any(), anyString())).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            if (userText(request).contains("bad-input")) {
                throw new RuntimeException("LLM provider error");
            }
            return chatResponse("good summary");
        });
        when(traceSummaryDAO.batchInsert(any())).thenReturn(Mono.just(1L));

        service.summarize(List.of(goodTrace, badTrace), WORKSPACE_ID).block();

        verify(chatCompletionService, times(2)).scoreTrace(any(), any(), eq(WORKSPACE_ID));

        var summariesCaptor = ArgumentCaptor.forClass(List.class);
        verify(traceSummaryDAO).batchInsert(summariesCaptor.capture());

        List<TraceSummary> summaries = summariesCaptor.getValue();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst().traceId()).isEqualTo(goodTrace.id());
        assertThat(summaries.getFirst().summary()).isEqualTo("good summary");
    }

    @Test
    @DisplayName("when all traces fail, then skip the batch insert")
    void summarize__whenAllTracesFail__thenSkipBatchInsert() {
        var trace = podamFactory.manufacturePojo(Trace.class);

        when(chatCompletionService.scoreTrace(any(), any(), anyString()))
                .thenThrow(new RuntimeException("LLM provider error"));

        service.summarize(List.of(trace), WORKSPACE_ID).block();

        verify(traceSummaryDAO, never()).batchInsert(any());
    }

    private ChatResponse chatResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private String userText(ChatRequest request) {
        return request.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(message -> ((UserMessage) message).singleText())
                .findFirst()
                .orElseThrow();
    }
}
