package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.inference.Answer;
import com.comet.opik.infrastructure.inference.ErrorBody;
import com.comet.opik.infrastructure.inference.ItemResponse;
import com.comet.opik.infrastructure.inference.ItemResult;
import com.comet.opik.infrastructure.inference.Question;
import com.comet.opik.infrastructure.inference.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.NODES;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.WORKSPACE_ID;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.generate;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.rendered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class AiLabelResultMapperTest {

    private static final UUID TRACE_ID = UUID.fromString("0192f0c4-4d1e-7a6b-9c3d-2e1f0a9b8c7d");
    private static final String JOB_ID = "job-7d3f";

    /** Three answer types in one task. */
    private static final AiLabelTask OUTCOME = AiLabelTask.builder()
            .name("outcome")
            .endpoint(AiLabelEndpoint.DECIDE)
            .version(2)
            .appliesFrom(AiLabelTaskFixtures.APPLIES_FROM)
            .questions(Map.of(
                    "succeeded", Question.NoulQuestion.builder().instructions(NODES.textNode("Done?")).build(),
                    "topic", Question.ChoiceQuestion.builder().instructions(NODES.textNode("Topic?"))
                            .criteria(Map.of("billing", NODES.nullNode(), "other", NODES.nullNode())).build(),
                    "quality", Question.ScoreQuestion.builder().instructions(NODES.textNode("Quality?"))
                            .criteria(List.of(NODES.textNode("poor"), NODES.textNode("good"))).build()))
            .build();
    private static final AiLabelTask TONE = AiLabelTaskFixtures.decide("tone", false, 1);

    private final AiLabelItemBuilder builder = new AiLabelItemBuilder();
    private final AiLabelResultMapper mapper = new AiLabelResultMapper();

    @Test
    void decideAnswersMapToTypedColumnsAndDone() {
        var item = item(List.of(OUTCOME, TONE));
        var inFlight = item.jobItems(JOB_ID, 1, Map.of("outcome", 2, "tone", 1));
        var legend = new LinkedHashMap<String, JsonNode>();
        legend.put("1", NODES.textNode("poor"));
        legend.put("2", NODES.objectNode().put("label", "good"));
        var answers = Map.<String, Answer>of(
                "outcome.succeeded", Answer.NoulAnswer.builder().noul(0.875).build(),
                "outcome.topic", Answer.ChoiceAnswer.builder().choice("billing")
                        .probabilities(Map.of("billing", 0.75, "other", 0.25)).confidence(0.75).build(),
                "outcome.quality", Answer.ScoreAnswer.builder().score(1.5).legend(legend)
                        .probabilities(Map.of("1", 0.5, "2", 0.5)).confidence(0.5).build(),
                "tone.q00", Answer.NoulAnswer.builder().noul(0.25).build());

        var results = mapper.map(decideResult(item, answers), item.plan(), inFlight);

        var label = AiLabel.builder()
                .workspaceId(WORKSPACE_ID)
                .projectId(item.trace().projectId())
                .traceId(TRACE_ID)
                .probabilities(Map.of())
                .legend(Map.of())
                .output("");
        assertThat(results.labels()).containsExactlyInAnyOrder(
                label.task("outcome").taskVersion(2).key("succeeded").answerType(AiLabel.AnswerType.NOUL)
                        .noul(0.875f).build(),
                label.task("outcome").taskVersion(2).key("topic").answerType(AiLabel.AnswerType.CHOICE)
                        .noul(null).choice("billing").confidence(0.75f)
                        .probabilities(Map.of("billing", 0.75f, "other", 0.25f)).build(),
                label.task("outcome").taskVersion(2).key("quality").answerType(AiLabel.AnswerType.SCORE)
                        .choice(null).score(1.5f).confidence(0.5f).probabilities(Map.of("1", 0.5f, "2", 0.5f))
                        .legend(Map.of("1", "poor", "2", "{\"label\":\"good\"}")).build(),
                AiLabel.builder().workspaceId(WORKSPACE_ID).projectId(item.trace().projectId()).traceId(TRACE_ID)
                        .task("tone").taskVersion(1).key("q00").answerType(AiLabel.AnswerType.NOUL).noul(0.25f)
                        .probabilities(Map.of()).legend(Map.of()).output("").build());
        assertThat(results.statuses()).containsExactlyElementsOf(inFlight.stream()
                .map(AiLabelJobItem::done)
                .toList());
    }

    @Test
    void missingAnswerFailsOnlyItsTask() {
        var item = item(List.of(OUTCOME, TONE));
        var inFlight = item.jobItems(JOB_ID, 1, Map.of("outcome", 1, "tone", 2));

        var results = mapper.map(decideResult(item, Map.of(
                "outcome.succeeded", Answer.NoulAnswer.builder().noul(1.0).build(),
                "tone.q00", Answer.NoulAnswer.builder().noul(0.5).build())), item.plan(), inFlight);

        assertThat(results.labels()).extracting(AiLabel::task, AiLabel::key)
                .containsExactly(tuple("tone", "q00"));
        assertThat(results.statuses()).containsExactly(
                inFlight.get(0).failed(AiLabelResultMapper.MISSING_ANSWER, true),
                inFlight.get(1).done());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void errorFailsEveryTaskWithTheEnvelopesCodeAndRetryability(boolean retryable) {
        var item = item(List.of(OUTCOME, TONE));
        var inFlight = item.jobItems(JOB_ID, 1, Map.of("outcome", 2, "tone", 2));
        var result = ItemResult.builder()
                .itemId(item.plan().itemId())
                .error(ErrorBody.builder().code("deadline_exceeded").message("m").retryable(retryable).build())
                .build();

        var results = mapper.map(result, item.plan(), inFlight);

        assertThat(results.labels()).isEmpty();
        assertThat(results.statuses()).containsExactlyElementsOf(inFlight.stream()
                .map(row -> row.failed("deadline_exceeded", retryable))
                .toList());
    }

    @Test
    void cancelledItemFailsRetryable() {
        var item = item(List.of(generate("summary", false)));
        var inFlight = item.jobItems(JOB_ID, 1, Map.of("summary", 1));

        var results = mapper.map(ItemResult.builder().itemId(item.plan().itemId()).build(), item.plan(), inFlight);

        assertThat(results.labels()).isEmpty();
        assertThat(results.statuses())
                .containsExactly(inFlight.getFirst().failed(AiLabelResultMapper.CANCELLED, true));
    }

    @Test
    void generateOutputMapsToOneCanonicalOutputRow() {
        var item = item(List.of(generate("summary", false)));
        var inFlight = item.jobItems(JOB_ID, 1, Map.of("summary", 1));
        var output = NODES.objectNode().put("summary", "Asks where a refund is").put("a_first", 1);

        var results = mapper.map(generateResult(item, output), item.plan(), inFlight);

        assertThat(results.labels()).containsExactly(AiLabel.builder()
                .workspaceId(WORKSPACE_ID)
                .projectId(item.trace().projectId())
                .task("summary")
                .traceId(TRACE_ID)
                .key(AiLabel.OUTPUT_KEY)
                .taskVersion(3)
                .answerType(AiLabel.AnswerType.OUTPUT)
                .probabilities(Map.of())
                .legend(Map.of())
                .output("{\"a_first\":1,\"summary\":\"Asks where a refund is\"}")
                .build());
        assertThat(results.statuses()).containsExactly(inFlight.getFirst().done());
    }

    @Test
    void generateOutputMissingARequiredPropertyFailsAsMissingAnswer() {
        var item = item(List.of(generate("summary", false)));
        var inFlight = item.jobItems(JOB_ID, 1, Map.of("summary", 1));

        for (var output : List.<JsonNode>of(NODES.objectNode().put("other", "x"), NODES.nullNode(),
                NODES.textNode("summary"))) {
            var results = mapper.map(generateResult(item, output), item.plan(), inFlight);

            assertThat(results.labels()).isEmpty();
            assertThat(results.statuses())
                    .containsExactly(inFlight.getFirst().failed(AiLabelResultMapper.MISSING_ANSWER, true));
        }
    }

    private AiLabelItem item(List<AiLabelTask> tasks) {
        var items = builder.build(WORKSPACE_ID, rendered(TRACE_ID), tasks);
        assertThat(items).hasSize(1);
        return items.getFirst();
    }

    private static ItemResult decideResult(AiLabelItem item, Map<String, Answer> answers) {
        return ItemResult.builder()
                .itemId(item.plan().itemId())
                .response(ItemResponse.DecideResponse.builder()
                        .requestId("r")
                        .usage(Usage.builder().build())
                        .result(ItemResponse.DecideResult.builder().answers(answers).build())
                        .build())
                .build();
    }

    private static ItemResult generateResult(AiLabelItem item, JsonNode output) {
        return ItemResult.builder()
                .itemId(item.plan().itemId())
                .response(ItemResponse.GenerateResponse.builder()
                        .requestId("r")
                        .usage(Usage.builder().build())
                        .result(ItemResponse.GenerateResult.builder().output(output).build())
                        .build())
                .build();
    }
}
