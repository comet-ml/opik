package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.AutomationRuleEvaluatorService;
import com.comet.opik.domain.ChatCompletionService;
import com.comet.opik.domain.FeedbackScoreService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.EventBus;
import dev.ai4j.openai4j.chat.Role;
import dev.ai4j.openai4j.chat.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("LlmAsJudge Message Render")
public class LlmAsJudgeMessageRenderTest {
    @Mock
    AutomationRuleEvaluatorService ruleEvaluatorService;
    @Mock
    ChatCompletionService aiProxyService;
    @Mock
    FeedbackScoreService feedbackScoreService;
    @Mock
    EventBus eventBus;
    OnlineScoringEventListener onlineScoringEventListener;

    AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode evaluatorCode;
    Trace trace;

    String messageToTest = "Summary: {{summary}}\\nInstruction: {{instruction}}\\n\\n";
    String testEvaluator = """
            {
              "model": {
                  "name": "gpt-4o",
                  "temperature": 0.3
              },
              "messages": [
                {
                  "role": "USER",
                  "content": "%s"
                },
                {
                  "role": "SYSTEM",
                  "content": "You're a helpful AI, be cordial."
                }
              ],
              "variables": {
                  "summary": "input.questions.question1",
                  "instruction": "output.output",
                  "nonUsed": "input.questions.question2",
                  "toFail1": "metadata.nonexistent.path"
              },
              "schema": [
                { "name": "Relevance",           "type": "INTEGER",   "description": "Relevance of the summary" },
                { "name": "Conciseness",         "type": "DOUBLE",    "description": "Conciseness of the summary" },
                { "name": "Technical Accuracy",  "type": "BOOLEAN",   "description": "Technical accuracy of the summary" }
              ]
            }
            """
            .formatted(messageToTest).trim();
    String summaryStr = "What was the approach to experimenting with different data mixtures?";
    String outputStr = "The study employed a systematic approach to experiment with varying data mixtures by manipulating the proportions and sources of datasets used for model training.";
    String input = """
            {
                "questions": {
                    "question1": "%s",
                    "question2": "Whatever, we wont use it anyway"
                 },
                "pdf_url": "https://arxiv.org/pdf/2406.04744",
                "title": "CRAG -- Comprehensive RAG Benchmark"
            }
            """.formatted(summaryStr).trim();
    String output = """
            {
                "output": "%s"
            }
            """.formatted(outputStr).trim();

    @BeforeEach
    void setUp() throws JsonProcessingException {
        MockitoAnnotations.openMocks(this);
        Mockito.doNothing().when(eventBus).register(Mockito.any());
        onlineScoringEventListener = new OnlineScoringEventListener(eventBus, ruleEvaluatorService,
                aiProxyService, feedbackScoreService);

        var mapper = new ObjectMapper();
        evaluatorCode = mapper.readValue(testEvaluator, AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode.class);
        trace = Trace.builder().input(mapper.readTree(input)).output(mapper.readTree(output)).build();
    }

    @Test
    @DisplayName("parse variable mapping into a usable one")
    void when__parseRuleVariables() {
        var variableMappings = LlmAsJudgeMessageRender.variableMapping(evaluatorCode.variables());

        assertThat(variableMappings).hasSize(4);

        var varSummary = variableMappings.get(0);
        assertThat(varSummary.traceSection()).isEqualTo(LlmAsJudgeMessageRender.TraceSection.INPUT);
        assertThat(varSummary.jsonPath()).isEqualTo("$.questions.question1");

        var varInstruction = variableMappings.get(1);
        assertThat(varInstruction.traceSection()).isEqualTo(LlmAsJudgeMessageRender.TraceSection.OUTPUT);
        assertThat(varInstruction.jsonPath()).isEqualTo("$.output");

        var varNonUsed = variableMappings.get(2);
        assertThat(varNonUsed.traceSection()).isEqualTo(LlmAsJudgeMessageRender.TraceSection.INPUT);
        assertThat(varNonUsed.jsonPath()).isEqualTo("$.questions.question2");

        var varToFail = variableMappings.get(3);
        assertThat(varToFail.traceSection()).isEqualTo(LlmAsJudgeMessageRender.TraceSection.METADATA);
        assertThat(varToFail.jsonPath()).isEqualTo("$.nonexistent.path");
    }

    @Test
    @DisplayName("render message templates with a trace")
    void when__renderTemplate() {
        var renderedMessages = LlmAsJudgeMessageRender.renderMessages(trace, evaluatorCode);

        assertThat(renderedMessages).hasSize(2);

        var userMessage = (UserMessage) renderedMessages.get(0);
        assertThat(userMessage.role()).isEqualTo(Role.USER);
        assertThat(userMessage.content().toString()).contains(summaryStr);
        assertThat(userMessage.content().toString()).contains(outputStr);

        var systemMessage = renderedMessages.get(1);
        assertThat(systemMessage.role()).isEqualTo(Role.SYSTEM);
    }

}
