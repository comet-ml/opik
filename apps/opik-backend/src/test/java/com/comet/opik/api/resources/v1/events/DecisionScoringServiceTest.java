package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.llm.openrouter.decisions.DecisionsQuestion;
import com.comet.opik.infrastructure.llm.openrouter.decisions.DecisionsResponse;
import com.comet.opik.infrastructure.llm.openrouter.decisions.OpenRouterDecisionsClient;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionScoringServiceTest {

    private final OnlineScoringConfig onlineScoringConfig = mock(OnlineScoringConfig.class);
    private final DecisionScoringService service = new DecisionScoringService(
            mock(OpenRouterDecisionsClient.class), mock(LlmProviderFactory.class), onlineScoringConfig);

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
                Map.entry("greets", DecisionsQuestion.noul("Does the reply greet the user?")),
                Map.entry("polite", DecisionsQuestion.noul("polite")));
    }

    @Test
    void exceedsContextAboveTheModelLimit() {
        when(onlineScoringConfig.getAgenticToolsCharsPerToken()).thenReturn(4);
        var schema = List.of(score("q", "?"));
        // Question key and instructions add 2 chars on top of the state.
        var atLimit = service.buildRequest("m",
                List.of(UserMessage.from("a".repeat(DecisionScoringService.MAX_CONTEXT_TOKENS * 4 - 2))), schema);
        var overLimit = service.buildRequest("m",
                List.of(UserMessage.from("a".repeat(DecisionScoringService.MAX_CONTEXT_TOKENS * 4 + 4))), schema);

        assertThat(service.exceedsContext(atLimit)).isFalse();
        assertThat(service.exceedsContext(overLimit)).isTrue();
    }

    @Test
    void toFeedbackScoresReportsUnreadableAnswers() {
        var answers = new HashMap<String, DecisionsResponse.Answer>();
        answers.put("valid", noul(0.8));
        answers.put("no_probability", noul(null));
        answers.put("above_one", noul(1.5));
        answers.put("negative", noul(-0.1));
        answers.put("nan", noul(Double.NaN));
        var schema = List.of(score("valid", "?"), score("missing", "?"), score("no_probability", "?"),
                score("above_one", "?"), score("negative", "?"), score("nan", "?"));

        var parsed = DecisionScoringService.toFeedbackScores(
                DecisionsResponse.builder().answers(answers).build(), schema);

        assertThat(parsed.scores()).extracting(FeedbackScoreBatchItem::name).containsExactly("valid");
        assertThat(parsed.unreadableScoreNames())
                .containsExactly("missing", "no_probability", "above_one", "negative", "nan");
    }

    private static DecisionsResponse.Answer noul(Double probability) {
        return DecisionsResponse.Answer.builder().type(DecisionsQuestion.NOUL_TYPE).noul(probability).build();
    }

    private static LlmAsJudgeOutputSchema score(String name, String description) {
        return LlmAsJudgeOutputSchema.builder()
                .name(name)
                .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                .description(description)
                .build();
    }
}
