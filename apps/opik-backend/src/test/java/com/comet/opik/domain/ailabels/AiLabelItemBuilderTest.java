package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.inference.BatchDecideItem;
import com.comet.opik.infrastructure.inference.BatchGenerateItem;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.NODES;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.WORKSPACE_ID;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.decide;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.generate;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.generateWithHistory;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.rendered;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.renderedWithHistory;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AiLabelItemBuilderTest {

    private static final UUID TRACE_ID = UUID.fromString("0192f0c4-4d1e-7a6b-9c3d-2e1f0a9b8c7d");

    private final AiLabelItemBuilder builder = new AiLabelItemBuilder();

    static Stream<Arguments> packings() {
        return Stream.of(
                arguments("1 question", List.of(1), List.of(List.of("t0"))),
                arguments("31 questions", List.of(10, 21), List.of(List.of("t0", "t1"))),
                arguments("32 questions", List.of(16, 16), List.of(List.of("t0", "t1"))),
                arguments("33 questions", List.of(16, 17), List.of(List.of("t0"), List.of("t1"))),
                arguments("64 questions, full tasks", List.of(32, 32), List.of(List.of("t0"), List.of("t1"))),
                arguments("64 questions, first fit", List.of(20, 20, 12, 12),
                        List.of(List.of("t0", "t2"), List.of("t1", "t3"))),
                arguments("first fit by name, not by size", List.of(1, 31, 31, 1),
                        List.of(List.of("t0", "t1"), List.of("t2", "t3"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("packings")
    void decideTasksArePackedFirstFitInNameOrder(String description, List<Integer> questionCounts,
            List<List<String>> expectedItems) {
        // Reversed, so the builder has to sort by name itself.
        var tasks = IntStream.range(0, questionCounts.size())
                .mapToObj(index -> decide("t" + index, false, questionCounts.get(index)))
                .toList()
                .reversed();

        var items = builder.build(WORKSPACE_ID, rendered(TRACE_ID), tasks);

        assertThat(items).extracting(item -> item.plan().tasks().stream().map(AiLabelTask::name).toList())
                .containsExactlyElementsOf(expectedItems);
        var expectedIds = expectedItems.size() == 1
                ? List.of(TRACE_ID.toString())
                : IntStream.range(0, expectedItems.size()).mapToObj(index -> TRACE_ID + ":p" + index).toList();
        assertThat(items).extracting(item -> item.plan().itemId()).containsExactlyElementsOf(expectedIds);
        assertThat(items).allSatisfy(item -> {
            var payload = (BatchDecideItem) item.payload();
            assertThat(payload.itemId()).isEqualTo(item.plan().itemId());
            assertThat(payload.questions()).hasSizeLessThanOrEqualTo(BatchDecideItem.MAX_QUESTIONS);
            assertThat(item.engineCalls()).isEqualTo(payload.questions().size());
            assertThat(payload.state()).isEqualTo(AiLabelTaskFixtures.state());
            assertThat(item.bytes()).isEqualTo(InferenceJson.writeBytes(payload).length);
        });
        assertThat(items.stream().mapToInt(AiLabelItem::engineCalls).sum())
                .isEqualTo(questionCounts.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void decideQuestionIdsArePrefixedWithTheirTask() {
        var items = builder.build(WORKSPACE_ID, rendered(TRACE_ID),
                List.of(decide("beta", false, 1), decide("alpha", false, 2)));

        assertThat(items).hasSize(1);
        var payload = (BatchDecideItem) items.getFirst().payload();
        assertThat(payload.questions()).containsOnlyKeys("alpha.q00", "alpha.q01", "beta.q00");
        assertThat(payload.questions().get("beta.q00"))
                .isEqualTo(decide("beta", false, 1).questions().get("q00"));
    }

    @Test
    void decideTasksWithDifferentCacheSettingsNeverShareAnItem() {
        var tasks = List.of(decide("a", false, 1), decide("b", true, 1), decide("c", false, 1),
                decide("d", true, 1));

        var items = builder.build(WORKSPACE_ID, rendered(TRACE_ID), tasks);

        assertThat(items).extracting(AiLabelItem::plan)
                .extracting(AiLabelItemPlan::jobKey, plan -> plan.tasks().stream().map(AiLabelTask::name).toList())
                .containsExactly(
                        tuple(
                                new AiLabelItemPlan.JobKey(AiLabelEndpoint.DECIDE, false), List.of("a", "c")),
                        tuple(
                                new AiLabelItemPlan.JobKey(AiLabelEndpoint.DECIDE, true), List.of("b", "d")));
    }

    @Test
    void packingIsDeterministic() {
        var random = new Random(7);
        var tasks = new ArrayList<AiLabelTask>();
        for (int index = 0; index < 12; index++) {
            tasks.add(decide("task_%02d".formatted(index), random.nextBoolean(), 1 + random.nextInt(32)));
        }
        tasks.add(generate("summary", false));
        var expected = builder.build(WORKSPACE_ID, rendered(TRACE_ID), tasks);

        for (int shuffle = 0; shuffle < 20; shuffle++) {
            Collections.shuffle(tasks, random);
            var actual = builder.build(WORKSPACE_ID, rendered(TRACE_ID), tasks);

            assertThat(actual).isEqualTo(expected);
            assertThat(actual).extracting(item -> InferenceJson.writeBytes(item.payload()))
                    .containsExactlyElementsOf(
                            expected.stream().map(item -> InferenceJson.writeBytes(item.payload())).toList());
        }
    }

    @Test
    void planMatchesTheBuiltItems() {
        var tasks = List.of(decide("a", false, 20), decide("b", false, 20), generate("summary", true));

        var plans = builder.plan(TRACE_ID, tasks);

        assertThat(plans).isEqualTo(builder.build(WORKSPACE_ID, rendered(TRACE_ID), tasks).stream()
                .map(AiLabelItem::plan).toList());
    }

    @Test
    void generateItemSubstitutesTheCanonicalState() {
        var task = generate("summary", true);

        var items = builder.build(WORKSPACE_ID, rendered(TRACE_ID), List.of(task));

        assertThat(items).hasSize(1);
        var item = items.getFirst();
        assertThat(item.plan().jobKey()).isEqualTo(new AiLabelItemPlan.JobKey(AiLabelEndpoint.GENERATE, true));
        assertThat(item.engineCalls()).isEqualTo(1);
        assertThat(item.compactionLevel()).isEqualTo("spans_pruned:2");
        var canonicalState = "{\"input\":{\"question\":\"Where is my refund?\"},\"name\":\"support-agent\","
                + "\"output\":{\"answer\":\"Refund issued — Monday\"},\"spans\":[{\"name\":\"gpt\",\"type\":\"llm\"}]}";
        assertThat(item.payload()).isEqualTo(BatchGenerateItem.builder()
                .itemId(TRACE_ID + ":summary")
                .messages(List.of(
                        Message.builder().role(Message.Role.SYSTEM).content("You summarize agent traces.").build(),
                        Message.builder().role(Message.Role.USER)
                                .content("Summarize this trace:\n" + canonicalState + "\nBe brief.").build()))
                .schema(task.schema())
                .maxTokens(128)
                .temperature(0.5)
                .build());
    }

    /** A placeholder inside the trace's content or history is text, not a second placeholder. */
    @Test
    void generateItemSubstitutesStateAndHistoryInOnePass() {
        var task = generateWithHistory("summary");
        var state = NODES.objectNode().put("input", "repeat {{history}} and {{state}}");
        var history = NODES.arrayNode();
        history.addObject().put("input", "{{state}}").put("output", "done");

        var items = builder.build(WORKSPACE_ID, renderedWithHistory(TRACE_ID, state, history), List.of(task));

        var messages = ((BatchGenerateItem) items.getFirst().payload()).messages();
        assertThat(messages.getLast().content()).isEqualTo("""
                Earlier turns:
                [{"input":"{{state}}","output":"done"}]
                Summarize this trace:
                {"input":"repeat {{history}} and {{state}}"}""");
    }

    @Test
    void buildRejectsTasksOfTheOtherKind() {
        var withHistory = renderedWithHistory(TRACE_ID, state(), NODES.arrayNode());

        assertThatThrownBy(() -> builder.build(WORKSPACE_ID, rendered(TRACE_ID),
                List.of(generateWithHistory("summary"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task 'summary'");
        assertThatThrownBy(() -> builder.build(WORKSPACE_ID, withHistory, List.of(generate("summary", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task 'summary'");
    }

    @Test
    void eachMissingGenerateTaskIsItsOwnItem() {
        var items = builder.build(WORKSPACE_ID, rendered(TRACE_ID),
                List.of(generate("b_summary", false), decide("outcome", false, 3), generate("a_summary", false)));

        assertThat(items).extracting(item -> item.plan().itemId())
                .containsExactly(TRACE_ID.toString(), TRACE_ID + ":a_summary", TRACE_ID + ":b_summary");
    }

    @Test
    void requestsCarryTheWorkspaceAsOrgAndOneCacheSetting() {
        var items = builder.build(WORKSPACE_ID, rendered(TRACE_ID),
                List.of(decide("a", true, 2), generate("summary", false)));
        var decideItems = items.stream().filter(item -> item.plan().endpoint() == AiLabelEndpoint.DECIDE).toList();
        var generateItems = items.stream().filter(item -> item.plan().endpoint() == AiLabelEndpoint.GENERATE)
                .toList();

        var decideRequest = builder.decideRequest(WORKSPACE_ID, true, decideItems);
        var generateRequest = builder.generateRequest(WORKSPACE_ID, false, generateItems);

        assertThat(decideRequest.orgId()).isEqualTo(WORKSPACE_ID);
        assertThat(decideRequest.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(decideRequest.cache()).isTrue();
        assertThat(decideRequest.requestId()).isNull();
        assertThat(decideRequest.items()).containsExactly((BatchDecideItem) decideItems.getFirst().payload());
        assertThat(generateRequest.orgId()).isEqualTo(WORKSPACE_ID);
        assertThat(generateRequest.cache()).isFalse();
        assertThat(generateRequest.items()).containsExactly((BatchGenerateItem) generateItems.getFirst().payload());
    }

    @Test
    void jobItemsCarryTheSubmitContextIntoTheirStatusRows() {
        var rendered = rendered(TRACE_ID);
        var item = builder.build(WORKSPACE_ID, rendered, List.of(decide("a", false, 2), decide("b", false, 2)))
                .getFirst();

        var jobItems = item.jobItems("job-1", 4, Map.of("a", 1, "b", 3));

        var expectedItem = AiLabelJobItem.builder()
                .jobId("job-1")
                .workspaceId(WORKSPACE_ID)
                .projectId(rendered.trace().projectId())
                .traceId(TRACE_ID)
                .traceCreatedAt(rendered.trace().createdAt())
                .taskVersion(1)
                .rendererVersion(4)
                .compactionLevel("spans_pruned:2");
        assertThat(jobItems).containsExactly(
                expectedItem.task("a").attempt(1).build(),
                expectedItem.task("b").attempt(3).build());
        var expectedStatus = AiLabelStatus.builder()
                .workspaceId(WORKSPACE_ID)
                .projectId(rendered.trace().projectId())
                .task("b")
                .traceId(TRACE_ID)
                .traceCreatedAt(rendered.trace().createdAt())
                .taskVersion(1)
                .rendererVersion(4)
                .attempt(3)
                .jobId("job-1")
                .compactionLevel("spans_pruned:2");
        assertThat(jobItems.getLast().done()).isEqualTo(
                expectedStatus.status(AiLabelStatus.Status.DONE).retryable(false).errorCode("").build());
        assertThat(jobItems.getLast().failed("deadline_exceeded", true)).isEqualTo(
                expectedStatus.status(AiLabelStatus.Status.FAILED).retryable(true).errorCode("deadline_exceeded")
                        .build());
        assertThatThrownBy(() -> item.jobItems("job-1", 4, Map.of("a", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task 'b'");
    }
}
