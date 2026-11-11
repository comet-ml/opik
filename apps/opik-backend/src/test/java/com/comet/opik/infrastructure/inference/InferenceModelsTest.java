package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.inference.InferenceContract.BATCH_DECIDE;
import static com.comet.opik.infrastructure.inference.InferenceContract.BATCH_GENERATE;
import static com.comet.opik.infrastructure.inference.InferenceContract.GET_JOB;
import static com.comet.opik.infrastructure.inference.InferenceContract.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class InferenceModelsTest {

    private static final String TRACE_ID = "0192f0c4-4d1e-7a6b-9c3d-2e1f0a9b8c7d";
    private static final String REQUEST_ID = "6f1c2c9e-8d0b-4a8e-9a43-3f8f5c6f1a10";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Every response model, so a model added later without unknown-field tolerance fails the walk below. */
    private static final Set<Class<?>> RESPONSE_MODELS = Set.of(
            BatchSubmitResponse.class, JobView.class, JobCounts.class, ItemResult.class, ErrorBody.class,
            ErrorEnvelope.class, ItemResponse.DecideResponse.class, ItemResponse.GenerateResponse.class,
            ItemResponse.DecideResult.class, ItemResponse.GenerateResult.class, Usage.class, Answer.NoulAnswer.class,
            Answer.ChoiceAnswer.class, Answer.ScoreAnswer.class);

    @Test
    void batchDecideRequestSerializesToGoldenJsonValidAgainstContract() {
        var questions = new TreeMap<String, Question>();
        questions.put("agent_outcome.succeeded", Question.NoulQuestion.builder()
                .instructions(TextNode.valueOf("Did the agent accomplish the user's goal?"))
                .criteria(Question.NoulCriteria.builder()
                        .whenTrue(TextNode.valueOf("The goal was met"))
                        .whenFalse(TextNode.valueOf("It was not"))
                        .build())
                .build());
        var choices = new LinkedHashMap<String, JsonNode>();
        choices.put("technical", TextNode.valueOf("Bugs and errors"));
        choices.put("billing", NODES.nullNode());
        choices.put("other", NODES.nullNode());
        questions.put("agent_outcome.topic", Question.ChoiceQuestion.builder()
                .instructions(TextNode.valueOf("Main topic?"))
                .criteria(choices)
                .build());
        questions.put("agent_outcome.quality", Question.ScoreQuestion.builder()
                .instructions(TextNode.valueOf("How good was the answer?"))
                .criteria(List.of(TextNode.valueOf("poor"), TextNode.valueOf("fair"), TextNode.valueOf("good")))
                .build());
        var request = BatchDecideRequest.builder()
                .orgId("workspace-1")
                .workspaceId("workspace-1")
                .requestId(REQUEST_ID)
                .cache(false)
                .items(List.of(BatchDecideItem.builder()
                        .itemId(TRACE_ID)
                        .state(state())
                        .questions(questions)
                        .build()))
                .build();

        var actual = InferenceJson.MAPPER.valueToTree(request);

        assertThat(actual).isEqualTo(resource("batch-decide-request.json"));
        assertThat(InferenceContract.validateEndpoint(BATCH_DECIDE, "request", actual)).isEmpty();
    }

    @Test
    void batchGenerateRequestSerializesToGoldenJsonValidAgainstContract() {
        var schema = NODES.objectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("summary").put("type", "string");
        schema.putArray("required").add("summary");
        var request = BatchGenerateRequest.builder()
                .orgId("workspace-1")
                .workspaceId("workspace-1")
                .requestId(REQUEST_ID)
                .cache(true)
                .items(List.of(BatchGenerateItem.builder()
                        .itemId(TRACE_ID + ":summary")
                        .messages(List.of(
                                Message.builder().role(Message.Role.SYSTEM).content("You summarize agent traces.")
                                        .build(),
                                Message.builder().role(Message.Role.USER)
                                        .content("Summarize this trace:\n{\"name\":\"support-agent\"}").build()))
                        .schema(schema)
                        .maxTokens(256)
                        .temperature(0.0)
                        .build()))
                .build();

        var actual = InferenceJson.MAPPER.valueToTree(request);

        assertThat(actual).isEqualTo(resource("batch-generate-request.json"));
        assertThat(InferenceContract.validateEndpoint(BATCH_GENERATE, "request", actual)).isEmpty();
    }

    static Stream<Arguments> documentedResponses() {
        return Stream.of(
                arguments("batch-submit-response.json", BATCH_DECIDE, BatchSubmitResponse.class,
                        BatchSubmitResponse.builder().jobId("job-7d3f").items(2).build()),
                arguments("batch-submit-response.json", BATCH_GENERATE, BatchSubmitResponse.class,
                        BatchSubmitResponse.builder().jobId("job-7d3f").items(2).build()),
                arguments("job-view-decide.json", GET_JOB, JobView.class, decideJobView()),
                arguments("job-view-generate.json", GET_JOB, JobView.class, generateJobView()),
                arguments("job-view-no-results.json", GET_JOB, JobView.class, noResultsJobView()));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("documentedResponses")
    void documentedResponseIsValidAndDeserializes(String fixture, String endpoint, Class<?> type, Object expected)
            throws Exception {
        var json = resource(fixture);

        assertThat(InferenceContract.validateEndpoint(endpoint, "response", json)).isEmpty();
        assertThat(InferenceJson.MAPPER.treeToValue(json, type)).isEqualTo(expected);
    }

    /**
     * Walks each fixture alongside its Java model, adding an unknown field to every object that is a model (never to a
     * map, where it would be a new entry), so every model at every level must tolerate it. The walk must reach every
     * response model, so a model added later is covered too.
     */
    @Test
    void responseToleratesUnknownFieldsAtEveryLevel() throws Exception {
        var fixtures = Map.of(
                "batch-submit-response.json", BatchSubmitResponse.class,
                "job-view-decide.json", JobView.class,
                "job-view-generate.json", JobView.class,
                "job-view-no-results.json", JobView.class,
                "error-envelope.json", ErrorEnvelope.class);
        var visited = new HashSet<Class<?>>();

        for (var fixture : fixtures.entrySet()) {
            var clean = resource(fixture.getKey());
            var withUnknownFields = clean.deepCopy();
            addUnknownFields(withUnknownFields, fixture.getValue(), visited);

            assertThat(withUnknownFields).as(fixture.getKey()).isNotEqualTo(clean);
            assertThat(InferenceJson.MAPPER.treeToValue(withUnknownFields, fixture.getValue())).as(fixture.getKey())
                    .isEqualTo(InferenceJson.MAPPER.treeToValue(clean, fixture.getValue()));
        }
        assertThat(visited).containsExactlyInAnyOrderElementsOf(RESPONSE_MODELS);
    }

    @Test
    void contractRejectsAnInvalidRequest() {
        var request = (ObjectNode) resource("batch-decide-request.json");
        ((ObjectNode) request.path("items").get(0)).put("item_id", "x".repeat(BatchItem.MAX_ITEM_ID_LENGTH + 1));
        request.put("unexpected", true);

        assertThat(InferenceContract.validateEndpoint(BATCH_DECIDE, "request", request)).hasSizeGreaterThanOrEqualTo(2);
    }

    private static void addUnknownFields(JsonNode node, Type type, Set<Class<?>> visited) {
        if (node == null || node.isNull()) {
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            var raw = (Class<?>) parameterized.getRawType();
            if (Map.class.isAssignableFrom(raw)) {
                node.elements().forEachRemaining(
                        value -> addUnknownFields(value, parameterized.getActualTypeArguments()[1], visited));
            } else if (List.class.isAssignableFrom(raw)) {
                node.elements().forEachRemaining(
                        value -> addUnknownFields(value, parameterized.getActualTypeArguments()[0], visited));
            }
            return;
        }
        var model = concreteModel((Class<?>) type, node);
        if (model == null) {
            return;
        }
        visited.add(model);
        var object = (ObjectNode) node;
        for (var component : model.getRecordComponents()) {
            addUnknownFields(object.get(jsonName(component)), component.getGenericType(), visited);
        }
        object.putObject("x_added_later").put("value", 1).putArray("nested").addObject().put("deep", true);
    }

    /** The record a JSON object deserializes to, resolving sealed types as the mapper does; null for non-models. */
    private static Class<?> concreteModel(Class<?> type, JsonNode node) {
        if (type == ItemResponse.class) {
            return node.path("result").has("answers")
                    ? ItemResponse.DecideResponse.class
                    : ItemResponse.GenerateResponse.class;
        }
        var subTypes = type.getAnnotation(JsonSubTypes.class);
        if (subTypes != null) {
            return Stream.of(subTypes.value())
                    .filter(subType -> subType.name().equals(node.path("type").asText()))
                    .findFirst()
                    .<Class<?>>map(JsonSubTypes.Type::value)
                    .orElseThrow();
        }
        return type.isRecord() ? type : null;
    }

    private static String jsonName(RecordComponent component) {
        var property = component.getAnnotation(JsonProperty.class);
        if (property != null && !property.value().isEmpty()) {
            return property.value();
        }
        return new PropertyNamingStrategies.SnakeCaseStrategy().translate(component.getName());
    }

    private static JsonNode state() {
        var state = NODES.objectNode();
        state.put("name", "support-agent");
        state.putObject("input").put("question", "Where is my refund?");
        state.putObject("output").put("answer", "It was issued on Monday.");
        return state;
    }

    private static JobView decideJobView() {
        var legend = new LinkedHashMap<String, JsonNode>();
        legend.put("1", TextNode.valueOf("poor"));
        legend.put("2", TextNode.valueOf("fair"));
        legend.put("3", NODES.objectNode().put("label", "good"));
        return JobView.builder()
                .jobId("job-7d3f")
                .endpoint(InferenceEndpoint.DECIDE)
                .status(JobStatus.PARTIALLY_FAILED)
                .counts(JobCounts.builder().pending(0).ok(1).failed(1).cancelled(1).build())
                .cancelRequested(false)
                .results(List.of(
                        ItemResult.builder()
                                .itemId(TRACE_ID)
                                .response(ItemResponse.DecideResponse.builder()
                                        .requestId(REQUEST_ID)
                                        .usage(Usage.builder().inputTokens(812).outputTokens(3).build())
                                        .cacheHit(false)
                                        .latencyMs(41)
                                        .result(ItemResponse.DecideResult.builder()
                                                .answers(Map.of(
                                                        "agent_outcome.succeeded",
                                                        Answer.NoulAnswer.builder().noul(0.875).build(),
                                                        "agent_outcome.topic", Answer.ChoiceAnswer.builder()
                                                                .choice("billing")
                                                                .probabilities(Map.of("billing", 0.75, "other", 0.125,
                                                                        "technical", 0.125))
                                                                .confidence(0.75)
                                                                .build(),
                                                        "agent_outcome.quality", Answer.ScoreAnswer.builder()
                                                                .score(2.5)
                                                                .legend(legend)
                                                                .probabilities(Map.of("1", 0.125, "2", 0.25, "3",
                                                                        0.625))
                                                                .confidence(0.625)
                                                                .build()))
                                                .build())
                                        .build())
                                .build(),
                        ItemResult.builder()
                                .itemId("0192f0c4-4d1e-7a6b-9c3d-2e1f0a9b8c7e")
                                .error(ErrorBody.builder().code("input_too_long").message("state is too long")
                                        .retryable(false).build())
                                .build(),
                        ItemResult.builder().itemId("0192f0c4-4d1e-7a6b-9c3d-2e1f0a9b8c7f").build()))
                .nextCursor(3)
                .build();
    }

    private static JobView generateJobView() {
        return JobView.builder()
                .jobId("job-8e4a")
                .endpoint(InferenceEndpoint.GENERATE)
                .status(JobStatus.COMPLETED)
                .counts(JobCounts.builder().pending(0).ok(1).failed(0).cancelled(0).build())
                .cancelRequested(false)
                .results(List.of(ItemResult.builder()
                        .itemId(TRACE_ID + ":trace_summary")
                        .response(ItemResponse.GenerateResponse.builder()
                                .requestId(REQUEST_ID)
                                .usage(Usage.builder().inputTokens(1024).outputTokens(17).build())
                                .cacheHit(true)
                                .latencyMs(380)
                                .result(ItemResponse.GenerateResult.builder()
                                        .output(NODES.objectNode().put("summary",
                                                "Customer asks where their refund is"))
                                        .build())
                                .build())
                        .build()))
                .build();
    }

    private static JobView noResultsJobView() {
        return JobView.builder()
                .jobId("job-8e4a")
                .endpoint(InferenceEndpoint.GENERATE)
                .status(JobStatus.RUNNING)
                .counts(JobCounts.builder().pending(9).ok(1).failed(0).cancelled(0).build())
                .cancelRequested(false)
                .results(List.of())
                .build();
    }
}
