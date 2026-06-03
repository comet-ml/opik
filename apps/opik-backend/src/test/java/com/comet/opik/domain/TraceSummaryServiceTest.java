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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @DisplayName("when a trace is summarized, then call the model and insert the result")
    void summarize__whenTraceIsSummarized__thenCallModelAndInsert() {
        var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\":\"how do I reset my password\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\":\"go to settings\"}"))
                .build();

        when(chatCompletionService.scoreTrace(any(), any(), eq(WORKSPACE_ID)))
                .thenReturn(chatResponse("The user wanted to reset their password."));
        when(traceSummaryDAO.batchInsert(any())).thenReturn(Mono.just(1L));

        service.summarize(trace, WORKSPACE_ID).block();

        var requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        var modelCaptor = ArgumentCaptor.forClass(LlmAsJudgeModelParameters.class);
        verify(chatCompletionService).scoreTrace(requestCaptor.capture(), modelCaptor.capture(), eq(WORKSPACE_ID));

        assertThat(modelCaptor.getValue().name()).isEqualTo("z-ai/glm-4.7-flash");
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
    @DisplayName("when the model call fails, then the error propagates and nothing is inserted")
    void summarize__whenModelFails__thenErrorPropagatesAndNoInsert() {
        var trace = podamFactory.manufacturePojo(Trace.class);

        when(chatCompletionService.scoreTrace(any(), any(), anyString()))
                .thenThrow(new RuntimeException("LLM provider error"));

        assertThatThrownBy(() -> service.summarize(trace, WORKSPACE_ID).block())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM provider error");

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
