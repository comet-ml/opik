package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.llm.openrouter.decisions.DecisionsQuestion;
import com.comet.opik.infrastructure.llm.openrouter.decisions.DecisionsResponse;
import com.comet.opik.infrastructure.llm.openrouter.decisions.OpenRouterDecisionsClient;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

class DecisionScoringServiceTest {

    private static final int CHARS_PER_TOKEN = 4;

    private final DecisionScoringService service = new DecisionScoringService(
            mock(OpenRouterDecisionsClient.class), mock(LlmProviderFactory.class));

    @Test
    void buildRequestJoinsMessageTextInOrderAndKeepsScoreOrder() {
        var request = service.buildRequest("~typesafe/jev-latest",
                List.of(SystemMessage.from("Context: support chat"),
                        UserMessage.from(TextContent.from("Reply: hello"),
                                ImageContent.from("https://example.com/image.png"))),
                List.of(score("greets", "Does the reply greet the user?"), score("polite", "")));

        assertThat(request.state()).isEqualTo("Context: support chat\n\nReply: hello");
        // A blank description falls back to the score name as the question.
        assertThat(request.questions()).containsExactly(
                Map.entry("q0", DecisionsQuestion.noul("Does the reply greet the user?")),
                Map.entry("q1", DecisionsQuestion.noul("polite")));
    }

    @Test
    void freeTextScoreNamesAreAskedByPositionAndMappedBack() {
        var schema = List.of(score("Greets the user?", "Does it greet?"), score("tón / 語調 #2", "Is it warm?"));

        var request = service.buildRequest("m", List.of(UserMessage.from("hi")), schema);
        var parsed = DecisionScoringService.toFeedbackScores(DecisionsResponse.builder()
                .answers(Map.of("q0", noul(0.9), "q1", noul(0.2)))
                .build(), schema);

        assertThat(request.questions()).containsOnlyKeys("q0", "q1");
        assertThat(parsed.scores()).extracting(FeedbackScoreBatchItem::name, item -> item.value().intValue())
                .containsExactly(tuple("Greets the user?", 1), tuple("tón / 語調 #2", 0));
    }

    @Test
    void exceedsContextAboveTheModelLimit() {
        var schema = List.of(score("q", "?"));
        // Question key and instructions add 2 chars on top of the state.
        var atLimit = service.buildRequest("m",
                List.of(UserMessage.from(
                        "a".repeat(DecisionScoringService.MAX_CONTEXT_TOKENS * CHARS_PER_TOKEN - 2))),
                schema);
        var overLimit = service.buildRequest("m",
                List.of(UserMessage.from(
                        "a".repeat(DecisionScoringService.MAX_CONTEXT_TOKENS * CHARS_PER_TOKEN + 4))),
                schema);

        assertThat(DecisionScoringService.exceedsContext(
                DecisionScoringService.estimateTokens(atLimit, CHARS_PER_TOKEN))).isFalse();
        assertThat(DecisionScoringService.exceedsContext(
                DecisionScoringService.estimateTokens(overLimit, CHARS_PER_TOKEN))).isTrue();
    }

    @Test
    void estimateTokensRejectsANonPositiveRatio() {
        var request = service.buildRequest("m", List.of(UserMessage.from("hi")), List.of(score("q", "?")));

        assertThatThrownBy(() -> DecisionScoringService.estimateTokens(request, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("charsPerToken must be >= 1");
    }

    @ParameterizedTest(name = "probability={0}")
    @ValueSource(doubles = {1.5, -0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void toFeedbackScoresReportsOutOfRangeProbabilityAsUnreadable(double probability) {
        var answers = Map.of("q0", noul(0.8), "q1", noul(probability));
        var schema = List.of(score("valid", "?"), score("invalid", "?"));

        var parsed = DecisionScoringService.toFeedbackScores(
                DecisionsResponse.builder().answers(answers).build(), schema);

        assertThat(parsed.scores()).extracting(FeedbackScoreBatchItem::name).containsExactly("valid");
        assertThat(parsed.unreadableScoreNames()).containsExactly("invalid");
    }

    @Test
    void toFeedbackScoresReportsMissingAndNullAnswersAsUnreadable() {
        var answers = new HashMap<String, DecisionsResponse.Answer>();
        answers.put("q1", noul(null));

        var parsed = DecisionScoringService.toFeedbackScores(DecisionsResponse.builder().answers(answers).build(),
                List.of(score("missing", "?"), score("no_probability", "?")));

        assertThat(parsed.scores()).isEmpty();
        assertThat(parsed.unreadableScoreNames()).containsExactly("missing", "no_probability");
    }

    @Test
    void repeatedScoreNameIsAskedAndStoredOnce() {
        var schema = List.of(score("greets", "Does it greet?"), score("greets", "Is it a greeting?"));

        var request = service.buildRequest("m", List.of(UserMessage.from("hi")), schema);
        var parsed = DecisionScoringService.toFeedbackScores(
                DecisionsResponse.builder().answers(Map.of("q0", noul(0.9))).build(), schema);

        // First entry wins, as in the chat judge's parser.
        assertThat(request.questions()).containsExactly(Map.entry("q0", DecisionsQuestion.noul("Does it greet?")));
        assertThat(parsed.scores()).hasSize(1);
    }

    @Test
    void nonBooleanScoresAreNeitherAskedNorStored() {
        var schema = List.of(score("greets", "Does it greet?"),
                score("quality", "How good is it?", LlmAsJudgeOutputSchemaType.INTEGER),
                score("tone", "How warm is it?", LlmAsJudgeOutputSchemaType.DOUBLE));

        var request = service.buildRequest("m", List.of(UserMessage.from("hi")), schema);
        // Even if the model answered them, numeric scores must not be stored as 0/1.
        var parsed = DecisionScoringService.toFeedbackScores(DecisionsResponse.builder()
                .answers(Map.of("q0", noul(0.9), "q1", noul(0.9), "q2", noul(0.1)))
                .build(), schema);

        assertThat(request.questions()).containsOnlyKeys("q0");
        assertThat(parsed.scores()).extracting(FeedbackScoreBatchItem::name).containsExactly("greets");
        assertThat(DecisionScoringService.unsupportedScoreNames(schema)).containsExactly("quality", "tone");
    }

    @Test
    void summarizeReplacesControlCharactersInScoreNames() {
        var summary = DecisionScoringService.summarize(DecisionsResponse.builder()
                .answers(Map.of("q0", noul(0.93)))
                .build(), List.of(score("forged\nINFO line", "?"), score("unanswered", "?")));

        assertThat(summary).isEqualTo("forged?INFO line=0.93, unanswered=null");
    }

    private static DecisionsResponse.Answer noul(Double probability) {
        return DecisionsResponse.Answer.builder().type(DecisionsQuestion.NOUL_TYPE).noul(probability).build();
    }

    private static LlmAsJudgeOutputSchema score(String name, String description) {
        return score(name, description, LlmAsJudgeOutputSchemaType.BOOLEAN);
    }

    private static LlmAsJudgeOutputSchema score(String name, String description, LlmAsJudgeOutputSchemaType type) {
        return LlmAsJudgeOutputSchema.builder()
                .name(name)
                .type(type)
                .description(description)
                .build();
    }
}
