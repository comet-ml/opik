package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Parsing of a judge's answer into feedback scores. Split from {@link OnlineScoringEngineTest} because
 * {@code toFeedbackScores} and {@code logUnreadableResponse} are pure static functions: that class starts
 * MySQL, ClickHouse and Zookeeper for its end-to-end tests, which these assertions do not need.
 */
@DisplayName("OnlineScoringEngine parsing")
class OnlineScoringEngineParsingTest {

    private static final List<LlmAsJudgeOutputSchema> THREE_SCORE_SCHEMA = List.of(
            new LlmAsJudgeOutputSchema("Relevance", LlmAsJudgeOutputSchemaType.INTEGER, "Relevance of the summary"),
            new LlmAsJudgeOutputSchema("Conciseness", LlmAsJudgeOutputSchemaType.DOUBLE, "Conciseness of the summary"),
            new LlmAsJudgeOutputSchema("Technical Accuracy", LlmAsJudgeOutputSchemaType.BOOLEAN,
                    "Technical accuracy of the summary"));

    private static List<LlmAsJudgeOutputSchema> singleScoreSchema(String name) {
        return List.of(new LlmAsJudgeOutputSchema(name, LlmAsJudgeOutputSchemaType.BOOLEAN, "test"));
    }

    private static ChatResponse chatResponse(String aiMessage) {
        return ChatResponse.builder().aiMessage(AiMessage.aiMessage(aiMessage)).build();
    }

    private static Stream<Arguments> feedbackParsingArguments() {
        var validAiMsgTxt = "{\"Relevance\":{\"score\":5,\"reason\":\"The summary directly addresses the approach taken in the study by mentioning the systematic experimentation with varying data mixtures and the manipulation of proportions and sources.\"},"
                + "\"Conciseness\":{\"score\":4.0,\"reason\":\"The summary is mostly concise but could be slightly more streamlined by removing redundant phrases.\"},"
                + "\"Technical Accuracy\":{\"score\":false,\"reason\":\"The summary accurately describes the experimental approach involving data mixtures, proportions, and sources, reflecting the technical details of the study.\"}}";
        var invalidAiMsgTxt = "a" + validAiMsgTxt;
        var emptyAiMsgTxt = "{}";
        var emptyJson = "";
        var flatAiMsgTxt = "{\"user_satisfaction_score\":75.0,\"reason\":\"why\",\"chat_summary\":\"summary\"}";

        return Stream.of(
                arguments(validAiMsgTxt, 3),
                arguments(invalidAiMsgTxt, 0),
                arguments(emptyAiMsgTxt, 0),
                arguments(emptyJson, 0),
                arguments(flatAiMsgTxt, 0));
    }

    @ParameterizedTest
    @MethodSource("feedbackParsingArguments")
    @DisplayName("parse feedback scores from AI response")
    void testToFeedbackScores(String aiMessage, int expectedSize) {
        var feedbackScores = OnlineScoringEngine.toFeedbackScores(chatResponse(aiMessage), THREE_SCORE_SCHEMA)
                .scores();

        assertThat(feedbackScores).hasSize(expectedSize);

        if (expectedSize > 0) {
            var scoresMap = feedbackScores.stream()
                    .collect(Collectors.toMap(FeedbackScoreBatchItem::name, Function.identity()));

            var relevance = scoresMap.get("Relevance");
            assertThat(relevance.value()).isEqualTo(BigDecimal.valueOf(5));
            assertThat(relevance.source()).isEqualTo(ScoreSource.ONLINE_SCORING);

            var conciseness = scoresMap.get("Conciseness");
            assertThat(conciseness.value()).isEqualTo(new BigDecimal("4.0"));
            assertThat(conciseness.source()).isEqualTo(ScoreSource.ONLINE_SCORING);

            var techAccuracy = scoresMap.get("Technical Accuracy");
            assertThat(techAccuracy.value()).isEqualTo(BigDecimal.ZERO);
            assertThat(techAccuracy.source()).isEqualTo(ScoreSource.ONLINE_SCORING);
        }
    }

    @Test
    @DisplayName("skip feedback scores whose value is null in the AI response")
    void whenScoreValueIsNull_thenSkipsThatScoreAndReportsItAsSkipped() {
        var aiMessage = "{\"Relevance\":{\"score\":5,\"reason\":\"applies\"},"
                + "\"Conciseness\":{\"score\":null,\"reason\":\"not applicable to this turn\"},"
                + "\"Technical Accuracy\":{\"score\":4.0,\"reason\":\"good\"}}";

        var parsed = OnlineScoringEngine.toFeedbackScores(chatResponse(aiMessage), THREE_SCORE_SCHEMA);

        assertThat(parsed.scores()).hasSize(2);
        assertThat(parsed.scores()).extracting(FeedbackScoreBatchItem::name)
                .containsExactlyInAnyOrder("Relevance", "Technical Accuracy");
        assertThat(parsed.nullScoreNames()).containsExactly("Conciseness");
    }

    @Test
    @DisplayName("parse a flat single-score response by naming it after the only score in the schema")
    void whenResponseIsFlatAndSchemaHasOneScore_thenParsesItUnderThatName() {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("{\"score\": true, \"reason\": [\"conveys the same factual answer\"]}"),
                singleScoreSchema("Meaning Match"));

        assertThat(parsed.scores()).hasSize(1);
        assertThat(parsed.scores().getFirst().name()).isEqualTo("Meaning Match");
        assertThat(parsed.scores().getFirst().value()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(parsed.scores().getFirst().reason()).isEqualTo("conveys the same factual answer");
        assertThat(parsed.scores().getFirst().source()).isEqualTo(ScoreSource.ONLINE_SCORING);
    }

    @Test
    @DisplayName("report a flat null score as skipped rather than dropping it")
    void whenResponseIsFlatWithNullScore_thenReportsItAsSkipped() {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("{\"score\": null, \"reason\": \"not applicable\"}"),
                singleScoreSchema("Meaning Match"));

        assertThat(parsed.scores()).isEmpty();
        assertThat(parsed.nullScoreNames()).containsExactly("Meaning Match");
    }

    @Test
    @DisplayName("do not guess which score a flat response belongs to when the schema has several")
    void whenResponseIsFlatAndSchemaHasSeveralScores_thenParsesNothing() {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("{\"score\": true, \"reason\": \"ambiguous\"}"), THREE_SCORE_SCHEMA);

        assertThat(parsed.scores()).isEmpty();
        assertThat(parsed.nullScoreNames()).isEmpty();
    }

    @Test
    @DisplayName("join an array reason instead of dropping it")
    void whenReasonIsAnArray_thenJoinsItIntoText() {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("{\"Relevance\":{\"score\":4,\"reason\":[\"first point\",\"second point\"]}}"),
                singleScoreSchema("Relevance"));

        assertThat(parsed.scores()).hasSize(1);
        assertThat(parsed.scores().getFirst().reason()).isEqualTo("first point, second point");
    }

    private static Stream<Arguments> quotedScoreValues() {
        return Stream.of(
                arguments("\"0.8\"", new BigDecimal("0.8")),
                arguments("\"1\"", BigDecimal.ONE),
                arguments("\"true\"", BigDecimal.ONE),
                arguments("\"TRUE\"", BigDecimal.ONE),
                arguments("\" false \"", BigDecimal.ZERO));
    }

    @ParameterizedTest
    @MethodSource("quotedScoreValues")
    @DisplayName("parse a score the judge quoted instead of dropping it or storing zero")
    void whenScoreIsQuoted_thenParsesTheValue(String rawScore, BigDecimal expected) {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("{\"S\":{\"score\":%s,\"reason\":\"r\"}}".formatted(rawScore)),
                singleScoreSchema("S"));

        assertThat(parsed.scores()).hasSize(1);
        assertThat(parsed.scores().getFirst().value()).isEqualByComparingTo(expected);
        assertThat(parsed.problem()).isNull();
        assertThat(parsed.unreadableScoreNames()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"high\"", "\"\"", "[]", "{\"nested\":1}"})
    @DisplayName("report an unusable score value instead of silently storing zero")
    void whenScoreValueIsUnusable_thenReportsItInsteadOfStoringZero(String rawScore) {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("{\"S\":{\"score\":%s,\"reason\":\"r\"}}".formatted(rawScore)),
                singleScoreSchema("S"));

        assertThat(parsed.scores()).isEmpty();
        assertThat(parsed.unreadableScoreNames()).containsExactly("S");
        assertThat(parsed.problem()).isNull();
    }

    @Test
    @DisplayName("keep the scores that parsed when a sibling score's value is unusable")
    void whenOneScoreValueIsUnusable_thenKeepsTheOthers() {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("{\"Relevance\":{\"score\":\"high\",\"reason\":\"r1\"},"
                        + "\"Conciseness\":{\"score\":[],\"reason\":\"r2\"},"
                        + "\"Technical Accuracy\":{\"score\":0.9,\"reason\":\"r3\"}}"),
                THREE_SCORE_SCHEMA);

        assertThat(parsed.scores()).extracting(FeedbackScoreBatchItem::name)
                .containsExactly("Technical Accuracy");
        assertThat(parsed.unreadableScoreNames()).containsExactly("Relevance", "Conciseness");
        // Not a whole-answer problem: the shape was understood, two values were not.
        assertThat(parsed.problem()).isNull();
    }

    @Test
    @DisplayName("report a judge answer that is not JSON so the user sees why nothing was scored")
    void whenResponseIsNotJson_thenReportsItAsUnreadable() {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("Sure! Let me evaluate that for you."), singleScoreSchema("S"));

        assertThat(parsed.scores()).isEmpty();
        assertThat(parsed.problem().kind()).isEqualTo(OnlineScoringEngine.ResponseProblem.Kind.NOT_JSON);
    }

    @Test
    @DisplayName("keep the judge's raw answer out of what is reported to the user")
    void whenResponseIsNotJson_thenReportsItsSizeAndNotItsContent() {
        var secret = "Sure! The patient's email is alice@example.com and the token is sk-abc123.";

        var parsed = OnlineScoringEngine.toFeedbackScores(chatResponse(secret), singleScoreSchema("S"));

        assertThat(parsed.problem().kind()).isEqualTo(OnlineScoringEngine.ResponseProblem.Kind.NOT_JSON);
        assertThat(parsed.problem().evidence()).isEqualTo("%,d chars".formatted(secret.length()));
        assertThat(parsed.problem().evidence()).doesNotContain("alice@example.com", "sk-abc123");
    }

    @Test
    @DisplayName("report a judge answer that is JSON but not an object")
    void whenResponseIsJsonButNotAnObject_thenReportsIt() {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("[{\"score\": true}]"), singleScoreSchema("S"));

        assertThat(parsed.scores()).isEmpty();
        assertThat(parsed.problem().kind())
                .isEqualTo(OnlineScoringEngine.ResponseProblem.Kind.NOT_A_JSON_OBJECT);
    }

    @Test
    @DisplayName("report a judge answer whose keys carry no score field")
    void whenResponseHasNoScoreField_thenReportsTheTopLevelKeys() {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("{\"answer_relevance_score\":0.8,\"reason\":\"r\"}"),
                singleScoreSchema("Answer relevance"));

        assertThat(parsed.scores()).isEmpty();
        assertThat(parsed.problem().kind()).isEqualTo(OnlineScoringEngine.ResponseProblem.Kind.NO_SCORE_FIELDS);
        assertThat(parsed.problem().evidence()).contains("answer_relevance_score");
    }

    @Test
    @DisplayName("name the empty field list rather than rendering a blank")
    void whenResponseIsAnEmptyObject_thenNamesTheAbsentFields() {
        var parsed = OnlineScoringEngine.toFeedbackScores(chatResponse("{}"), singleScoreSchema("S"));

        assertThat(parsed.problem().kind()).isEqualTo(OnlineScoringEngine.ResponseProblem.Kind.NO_SCORE_FIELDS);
        assertThat(parsed.problem().evidence()).isEqualTo("(none)");
    }

    @Test
    @DisplayName("cap the reported field names and count the rest")
    void whenResponseHasManyFields_thenCapsTheNamesAndCountsTheRest() {
        var manyFields = IntStream.range(0, 500)
                .mapToObj("\"field_%d\": 1"::formatted)
                .collect(Collectors.joining(",", "{", "}"));

        var evidence = OnlineScoringEngine.toFeedbackScores(chatResponse(manyFields), singleScoreSchema("S"))
                .problem().evidence();

        assertThat(evidence).matches("^('field_\\d+', ){9}'field_\\d+' and 490 more$");
    }

    @Test
    @DisplayName("a readable response reports nothing to the user")
    void whenResponseIsReadable_thenNothingIsReported() {
        var parsed = OnlineScoringEngine.toFeedbackScores(
                chatResponse("{\"S\":{\"score\":true,\"reason\":\"r\"}}"), singleScoreSchema("S"));

        assertThat(parsed.scores()).hasSize(1);
        assertThat(parsed.problem()).isNull();
        assertThat(parsed.unreadableScoreNames()).isEmpty();
    }

    @Test
    @DisplayName("say nothing to the user when the judge's answer was fully readable")
    void logUnreadableResponse_whenNothingIsWrong_thenSaysNothing() {
        var logger = Mockito.mock(Logger.class);

        OnlineScoringEngine.logUnreadableResponse(logger,
                new OnlineScoringEngine.ParsedFeedbackScores(List.of(), List.of("Skipped"), List.of(), null),
                "traceId", "t-1");

        Mockito.verifyNoInteractions(logger);
    }

    @Test
    @DisplayName("warn the user naming the score whose value could not be read")
    void logUnreadableResponse_whenValueUnusable_thenWarnsNamingTheScore() {
        var logger = Mockito.mock(Logger.class);

        OnlineScoringEngine.logUnreadableResponse(logger,
                new OnlineScoringEngine.ParsedFeedbackScores(List.of(), List.of(), List.of("Tone"), null),
                "traceId", "t-1");

        Mockito.verify(logger).warn(Mockito.contains("Could not read the score value"),
                Mockito.eq("'Tone'"), Mockito.eq("traceId"), Mockito.eq("t-1"));
    }

    @Test
    @DisplayName("warn the user with the reason when nothing could be scored")
    void logUnreadableResponse_whenAnswerUnusable_thenWarnsWithTheReason() {
        var logger = Mockito.mock(Logger.class);
        var problem = new OnlineScoringEngine.ResponseProblem(
                OnlineScoringEngine.ResponseProblem.Kind.NOT_JSON, "Sure! Let me help.");

        OnlineScoringEngine.logUnreadableResponse(logger,
                new OnlineScoringEngine.ParsedFeedbackScores(List.of(), List.of(), List.of(), problem),
                "traceId", "t-1");

        Mockito.verify(logger).warn(Mockito.contains("Nothing was scored"), Mockito.eq("traceId"),
                Mockito.eq("t-1"), Mockito.contains("was not valid JSON"));
    }
}
