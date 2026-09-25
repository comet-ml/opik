package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateLlmAsJudge;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import dev.langchain4j.data.message.ChatMessageType;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionModelRuleValidatorTest {

    private static final String JEV_MODEL = "~typesafe/jev-latest";

    private static final LlmAsJudgeMessage USER_MESSAGE = LlmAsJudgeMessage.builder()
            .role(ChatMessageType.USER)
            .content("Question: {{question}}\nAnswer: {{answer}}")
            .build();
    private static final LlmAsJudgeOutputSchema BOOLEAN_SCORE = score("answer_relevant",
            LlmAsJudgeOutputSchemaType.BOOLEAN);
    private static final Map<String, String> VARIABLES = Map.of(
            "question", "input.question", "answer", "output.answer");

    private final DecisionModelRuleValidator validator = new DecisionModelRuleValidator();

    @Test
    void acceptsValidTraceAndSpanRules() {
        assertThatCode(() -> validator.validate(traceRule(code(JEV_MODEL, List.of(USER_MESSAGE),
                VARIABLES, List.of(BOOLEAN_SCORE, score("answer_correct", LlmAsJudgeOutputSchemaType.BOOLEAN))))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(AutomationRuleEvaluatorSpanLlmAsJudge.builder()
                .name("rule")
                .code(SpanLlmAsJudgeCode.builder()
                        .model(model(JEV_MODEL))
                        .messages(List.of(USER_MESSAGE))
                        .variables(VARIABLES)
                        .schema(List.of(BOOLEAN_SCORE))
                        .build())
                .build()))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresRulesOnChatModels() {
        // Numeric score and two messages: invalid for Jev, fine for a chat model.
        var code = code("gpt-4o", List.of(USER_MESSAGE, USER_MESSAGE), VARIABLES,
                List.of(score("quality", LlmAsJudgeOutputSchemaType.DOUBLE)));

        assertThatCode(() -> validator.validate(traceRule(code))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void rejectsRulesJevCantRun(String description, LlmAsJudgeCode code, String expectedMessage) {
        assertThatThrownBy(() -> validator.validate(traceRule(code)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(expectedMessage);
        // Updates go through the same checks.
        assertThatThrownBy(() -> validator.validate(AutomationRuleEvaluatorUpdateLlmAsJudge.builder()
                .name("rule")
                .code(code)
                .build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(expectedMessage);
    }

    static Stream<Arguments> rejectsRulesJevCantRun() {
        var systemMessage = LlmAsJudgeMessage.builder().role(ChatMessageType.SYSTEM).content("Be strict").build();
        var imageMessage = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(List.of(
                        LlmAsJudgeMessageContent.builder().type("text").text("Describe").build(),
                        LlmAsJudgeMessageContent.builder().type("image_url").build()))
                .build();
        var traceMessage = LlmAsJudgeMessage.builder().role(ChatMessageType.USER).content("{{trace}}").build();
        return Stream.of(
                Arguments.of("no scores", code(JEV_MODEL, List.of(USER_MESSAGE), VARIABLES, List.of()),
                        "at least one score"),
                Arguments.of("numeric score", code(JEV_MODEL, List.of(USER_MESSAGE), VARIABLES,
                        List.of(BOOLEAN_SCORE, score("quality", LlmAsJudgeOutputSchemaType.INTEGER))),
                        "only support Boolean scores, score 'quality'"),
                Arguments.of("system message", code(JEV_MODEL, List.of(systemMessage, USER_MESSAGE), VARIABLES,
                        List.of(BOOLEAN_SCORE)), "exactly one user message"),
                Arguments.of("system message only", code(JEV_MODEL, List.of(systemMessage), VARIABLES,
                        List.of(BOOLEAN_SCORE)), "exactly one user message"),
                Arguments.of("image content", code(JEV_MODEL, List.of(imageMessage), VARIABLES,
                        List.of(BOOLEAN_SCORE)), "only accept text messages"),
                Arguments.of("{{trace}} variable", code(JEV_MODEL, List.of(traceMessage), Map.of(),
                        List.of(BOOLEAN_SCORE)), "'{{trace}}' variable"));
    }

    @Test
    void rejectsThreadRules() {
        var rule = AutomationRuleEvaluatorTraceThreadLlmAsJudge.builder()
                .name("rule")
                .code(TraceThreadLlmAsJudgeCode.builder()
                        .model(model(JEV_MODEL))
                        .messages(List.of(USER_MESSAGE))
                        .schema(List.of(BOOLEAN_SCORE))
                        .build())
                .build();

        assertThatThrownBy(() -> validator.validate(rule))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Thread rules don't support decisions models");
    }

    private static AutomationRuleEvaluatorLlmAsJudge traceRule(LlmAsJudgeCode code) {
        return AutomationRuleEvaluatorLlmAsJudge.builder()
                .name("rule")
                .code(code)
                .build();
    }

    private static LlmAsJudgeCode code(String model, List<LlmAsJudgeMessage> messages,
            Map<String, String> variables, List<LlmAsJudgeOutputSchema> schema) {
        return LlmAsJudgeCode.builder()
                .model(model(model))
                .messages(messages)
                .variables(variables)
                .schema(schema)
                .build();
    }

    private static LlmAsJudgeModelParameters model(String name) {
        return LlmAsJudgeModelParameters.builder().name(name).build();
    }

    private static LlmAsJudgeOutputSchema score(String name, LlmAsJudgeOutputSchemaType type) {
        return LlmAsJudgeOutputSchema.builder()
                .name(name)
                .type(type)
                .description("Is it " + name + "?")
                .build();
    }
}
