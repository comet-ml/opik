package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.EvaluatorType;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.infrastructure.EvalSuiteConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.uuid.Generators;
import dev.langchain4j.data.message.ChatMessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("EvalSuiteAssertionSampler Test")
class EvalSuiteAssertionSamplerTest {

    @Nested
    @DisplayName("onTracesCreated integration")
    @ExtendWith(MockitoExtension.class)
    class OnTracesCreated {

        @Mock
        DatasetItemService datasetItemService;

        @Mock
        DatasetVersionService datasetVersionService;

        @Mock
        OnlineScorePublisher onlineScorePublisher;

        @Mock
        IdGenerator idGenerator;

        @Mock
        LlmProviderApiKeyService llmProviderApiKeyService;

        private EvalSuiteAssertionSampler sampler;

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            var evalSuiteConfig = new EvalSuiteConfig();
            var evaluatorMapper = new EvalSuiteEvaluatorMapper(evalSuiteConfig);
            var openAiKey = ProviderApiKey.builder()
                    .provider(LlmProvider.OPEN_AI)
                    .build();
            lenient().when(llmProviderApiKeyService.find(any(String.class)))
                    .thenReturn(new ProviderApiKey.ProviderApiKeyPage(1, 1, 1, List.of(openAiKey), List.of()));
            sampler = new EvalSuiteAssertionSampler(
                    datasetItemService, datasetVersionService, onlineScorePublisher, idGenerator,
                    evalSuiteConfig, evaluatorMapper, llmProviderApiKeyService);
        }

        @Test
        @DisplayName("calls resolveVersionId when version hash is present in trace metadata")
        void callsResolveVersionId() {
            UUID datasetId = Generators.timeBasedEpochGenerator().generate();
            UUID versionId = Generators.timeBasedEpochGenerator().generate();
            String workspaceId = "test-workspace";
            String userName = "test-user";
            String versionHash = "abc123";

            var datasetVersion = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .evaluators(List.of())
                    .build();

            when(datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash))
                    .thenReturn(versionId);
            when(datasetVersionService.getVersionById(workspaceId, datasetId, versionId))
                    .thenReturn(datasetVersion);
            when(datasetItemService.get(any(UUID.class)))
                    .thenReturn(Mono.just(DatasetItem.builder().id(UUID.randomUUID()).build()));

            var datasetItemId = Generators.timeBasedEpochGenerator().generate();
            var metadata = JsonUtils.getJsonNodeFromString(
                    "{\"eval_suite_dataset_id\": \"%s\", \"eval_suite_dataset_version_hash\": \"%s\", \"eval_suite_dataset_item_id\": \"%s\"}"
                            .formatted(datasetId, versionHash, datasetItemId));

            var trace = Trace.builder()
                    .id(Generators.timeBasedEpochGenerator().generate())
                    .projectId(Generators.timeBasedEpochGenerator().generate())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\": [\"hello\"]}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"world\"}"))
                    .metadata(metadata)
                    .build();

            var event = new TracesCreated(List.of(trace), workspaceId, userName);
            sampler.onTracesCreated(event);

            verify(datasetVersionService).resolveVersionId(workspaceId, datasetId, versionHash);
        }

        @Test
        @DisplayName("includes item-level evaluators in scoring messages")
        void includesItemLevelEvaluators() {
            UUID datasetId = Generators.timeBasedEpochGenerator().generate();
            UUID versionId = Generators.timeBasedEpochGenerator().generate();
            UUID datasetItemId = Generators.timeBasedEpochGenerator().generate();
            UUID ruleId = Generators.timeBasedEpochGenerator().generate();
            String workspaceId = "test-workspace";
            String userName = "test-user";
            String versionHash = "abc123";

            var evaluatorConfig = new LlmAsJudgeCode(
                    LlmAsJudgeModelParameters.builder().name("gpt-5-nano").build(),
                    List.of(LlmAsJudgeMessage.builder()
                            .role(ChatMessageType.USER)
                            .content("Evaluate {input}")
                            .build()),
                    Map.of("input", "input"),
                    List.of(LlmAsJudgeOutputSchema.builder()
                            .name("check")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("Is it correct?")
                            .build()));

            var itemEvaluator = EvaluatorItem.builder()
                    .name("item-evaluator")
                    .type(EvaluatorType.LLM_JUDGE)
                    .config(JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(evaluatorConfig)))
                    .build();

            var datasetVersion = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .evaluators(List.of())
                    .build();

            when(datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash))
                    .thenReturn(versionId);
            when(datasetVersionService.getVersionById(workspaceId, datasetId, versionId))
                    .thenReturn(datasetVersion);
            when(datasetItemService.get(datasetItemId))
                    .thenReturn(Mono.just(DatasetItem.builder()
                            .id(datasetItemId)
                            .evaluators(List.of(itemEvaluator))
                            .build()));
            when(idGenerator.generateId()).thenReturn(ruleId);

            var metadata = JsonUtils.getJsonNodeFromString(
                    "{\"eval_suite_dataset_id\": \"%s\", \"eval_suite_dataset_version_hash\": \"%s\", \"eval_suite_dataset_item_id\": \"%s\"}"
                            .formatted(datasetId, versionHash, datasetItemId));

            var trace = Trace.builder()
                    .id(Generators.timeBasedEpochGenerator().generate())
                    .projectId(Generators.timeBasedEpochGenerator().generate())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\": [\"hello\"]}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"world\"}"))
                    .metadata(metadata)
                    .build();

            var event = new TracesCreated(List.of(trace), workspaceId, userName);
            sampler.onTracesCreated(event);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TraceToScoreLlmAsJudge>> captor = ArgumentCaptor.forClass(List.class);
            verify(onlineScorePublisher).enqueueMessage(captor.capture(), eq(AutomationRuleEvaluatorType.LLM_AS_JUDGE));

            List<TraceToScoreLlmAsJudge> messages = captor.getValue();
            assertThat(messages).hasSize(1);

            var message = messages.getFirst();
            assertThat(message.categoryName()).isEqualTo("suite_assertion");
            assertThat(message.trace().id()).isEqualTo(trace.id());
            assertThat(message.ruleName()).isEqualTo("item-evaluator");
            assertThat(message.llmAsJudgeCode().model().name()).isEqualTo("gpt-5-nano");
            assertThat(message.llmAsJudgeCode().schema()).hasSize(1);
            // Schema names are renamed to stable assertion_N keys
            assertThat(message.llmAsJudgeCode().schema().getFirst().name()).isEqualTo("assertion_1");
            // Original name becomes the description for scoreNameMapping
            assertThat(message.llmAsJudgeCode().schema().getFirst().description()).isEqualTo("check");

            // Verify assertions variable was injected with renamed schema
            assertThat(message.llmAsJudgeCode().variables()).containsKey("assertions");
            String assertionsValue = message.llmAsJudgeCode().variables().get("assertions").toString();
            assertThat(assertionsValue).contains("assertion_1");
            assertThat(assertionsValue).contains("check");

            // Verify score name mapping: assertion_1 → original name ("check")
            assertThat(message.scoreNameMapping()).containsEntry("assertion_1", "check");
        }

        @Test
        @DisplayName("combines dataset-level and item-level evaluators")
        void combinesDatasetAndItemEvaluators() {
            UUID datasetId = Generators.timeBasedEpochGenerator().generate();
            UUID versionId = Generators.timeBasedEpochGenerator().generate();
            UUID datasetItemId = Generators.timeBasedEpochGenerator().generate();
            UUID ruleId = Generators.timeBasedEpochGenerator().generate();
            String workspaceId = "test-workspace";
            String userName = "test-user";
            String versionHash = "abc123";

            var evaluatorConfig = new LlmAsJudgeCode(
                    LlmAsJudgeModelParameters.builder().name("gpt-5-nano").build(),
                    List.of(LlmAsJudgeMessage.builder()
                            .role(ChatMessageType.USER)
                            .content("Evaluate {input}")
                            .build()),
                    Map.of("input", "input"),
                    List.of(LlmAsJudgeOutputSchema.builder()
                            .name("check")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("Is it correct?")
                            .build()));

            var datasetEvaluator = EvaluatorItem.builder()
                    .name("dataset-evaluator")
                    .type(EvaluatorType.LLM_JUDGE)
                    .config(JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(evaluatorConfig)))
                    .build();

            var itemEvaluator = EvaluatorItem.builder()
                    .name("item-evaluator")
                    .type(EvaluatorType.LLM_JUDGE)
                    .config(JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(evaluatorConfig)))
                    .build();

            var datasetVersion = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .evaluators(List.of(datasetEvaluator))
                    .build();

            when(datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash))
                    .thenReturn(versionId);
            when(datasetVersionService.getVersionById(workspaceId, datasetId, versionId))
                    .thenReturn(datasetVersion);
            when(datasetItemService.get(datasetItemId))
                    .thenReturn(Mono.just(DatasetItem.builder()
                            .id(datasetItemId)
                            .evaluators(List.of(itemEvaluator))
                            .build()));
            when(idGenerator.generateId()).thenReturn(ruleId);

            var metadata = JsonUtils.getJsonNodeFromString(
                    "{\"eval_suite_dataset_id\": \"%s\", \"eval_suite_dataset_version_hash\": \"%s\", \"eval_suite_dataset_item_id\": \"%s\"}"
                            .formatted(datasetId, versionHash, datasetItemId));

            var trace = Trace.builder()
                    .id(Generators.timeBasedEpochGenerator().generate())
                    .projectId(Generators.timeBasedEpochGenerator().generate())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\": [\"hello\"]}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"world\"}"))
                    .metadata(metadata)
                    .build();

            var event = new TracesCreated(List.of(trace), workspaceId, userName);
            sampler.onTracesCreated(event);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TraceToScoreLlmAsJudge>> captor = ArgumentCaptor.forClass(List.class);
            verify(onlineScorePublisher).enqueueMessage(captor.capture(), eq(AutomationRuleEvaluatorType.LLM_AS_JUDGE));

            List<TraceToScoreLlmAsJudge> messages = captor.getValue();
            assertThat(messages).hasSize(2);
        }

        @Test
        @DisplayName("skips trace when eval_suite_dataset_item_id is missing from metadata")
        void skipsTraceWhenDatasetItemIdMissing() {
            UUID datasetId = Generators.timeBasedEpochGenerator().generate();
            UUID versionId = Generators.timeBasedEpochGenerator().generate();
            String workspaceId = "test-workspace";
            String userName = "test-user";
            String versionHash = "abc123";

            // Version has evaluators — if the skip check is removed, messages would be
            // enqueued and the never() assertion below would catch the regression.
            var evaluatorConfig = new LlmAsJudgeCode(
                    LlmAsJudgeModelParameters.builder().name("gpt-5-nano").build(),
                    List.of(LlmAsJudgeMessage.builder()
                            .role(ChatMessageType.USER)
                            .content("Evaluate {input}")
                            .build()),
                    Map.of("input", "input"),
                    List.of(LlmAsJudgeOutputSchema.builder()
                            .name("check")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("Is it correct?")
                            .build()));

            var datasetEvaluator = EvaluatorItem.builder()
                    .name("version-evaluator")
                    .type(EvaluatorType.LLM_JUDGE)
                    .config(JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(evaluatorConfig)))
                    .build();

            var datasetVersion = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .evaluators(List.of(datasetEvaluator))
                    .build();

            when(datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash))
                    .thenReturn(versionId);
            when(datasetVersionService.getVersionById(workspaceId, datasetId, versionId))
                    .thenReturn(datasetVersion);

            var metadata = JsonUtils.getJsonNodeFromString(
                    "{\"eval_suite_dataset_id\": \"%s\", \"eval_suite_dataset_version_hash\": \"%s\"}"
                            .formatted(datasetId, versionHash));

            var trace = Trace.builder()
                    .id(Generators.timeBasedEpochGenerator().generate())
                    .projectId(Generators.timeBasedEpochGenerator().generate())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\": [\"hello\"]}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"world\"}"))
                    .metadata(metadata)
                    .build();

            var event = new TracesCreated(List.of(trace), workspaceId, userName);
            sampler.onTracesCreated(event);

            verify(onlineScorePublisher, never()).enqueueMessage(any(), any());
            verify(datasetItemService, never()).get(any(UUID.class));
        }

        @Test
        @DisplayName("each trace gets only its own item-level assertions, no leakage between items")
        void assertionsDoNotLeakBetweenItems() {
            UUID datasetId = Generators.timeBasedEpochGenerator().generate();
            UUID versionId = Generators.timeBasedEpochGenerator().generate();
            UUID itemId1 = Generators.timeBasedEpochGenerator().generate();
            UUID itemId2 = Generators.timeBasedEpochGenerator().generate();
            UUID ruleId = Generators.timeBasedEpochGenerator().generate();
            String workspaceId = "test-workspace";
            String userName = "test-user";
            String versionHash = "abc123";

            // Item 1 evaluator: 2 assertions (grammar, spelling)
            var item1EvalConfig = new LlmAsJudgeCode(
                    LlmAsJudgeModelParameters.builder().name("gpt-5-nano").build(),
                    List.of(LlmAsJudgeMessage.builder()
                            .role(ChatMessageType.USER)
                            .content("Evaluate: {assertions}")
                            .build()),
                    Map.of(),
                    List.of(
                            LlmAsJudgeOutputSchema.builder()
                                    .name("grammar_ok")
                                    .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                                    .description("Grammar is correct")
                                    .build(),
                            LlmAsJudgeOutputSchema.builder()
                                    .name("spelling_ok")
                                    .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                                    .description("Spelling is correct")
                                    .build()));

            // Item 2 evaluator: 3 different assertions (tone, length, clarity)
            var item2EvalConfig = new LlmAsJudgeCode(
                    LlmAsJudgeModelParameters.builder().name("gpt-5-nano").build(),
                    List.of(LlmAsJudgeMessage.builder()
                            .role(ChatMessageType.USER)
                            .content("Evaluate: {assertions}")
                            .build()),
                    Map.of(),
                    List.of(
                            LlmAsJudgeOutputSchema.builder()
                                    .name("tone_ok")
                                    .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                                    .description("Tone is appropriate")
                                    .build(),
                            LlmAsJudgeOutputSchema.builder()
                                    .name("length_ok")
                                    .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                                    .description("Length is within bounds")
                                    .build(),
                            LlmAsJudgeOutputSchema.builder()
                                    .name("clarity_ok")
                                    .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                                    .description("Text is clear")
                                    .build()));

            // No dataset-level evaluators — only item-level
            var datasetVersion = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .evaluators(List.of())
                    .build();

            when(datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash))
                    .thenReturn(versionId);
            when(datasetVersionService.getVersionById(workspaceId, datasetId, versionId))
                    .thenReturn(datasetVersion);
            when(datasetItemService.get(itemId1))
                    .thenReturn(Mono.just(DatasetItem.builder()
                            .id(itemId1)
                            .evaluators(List.of(EvaluatorItem.builder()
                                    .name("item1-eval")
                                    .type(EvaluatorType.LLM_JUDGE)
                                    .config(JsonUtils.getJsonNodeFromString(
                                            JsonUtils.writeValueAsString(item1EvalConfig)))
                                    .build()))
                            .build()));
            when(datasetItemService.get(itemId2))
                    .thenReturn(Mono.just(DatasetItem.builder()
                            .id(itemId2)
                            .evaluators(List.of(EvaluatorItem.builder()
                                    .name("item2-eval")
                                    .type(EvaluatorType.LLM_JUDGE)
                                    .config(JsonUtils.getJsonNodeFromString(
                                            JsonUtils.writeValueAsString(item2EvalConfig)))
                                    .build()))
                            .build()));
            when(idGenerator.generateId()).thenReturn(ruleId);

            var trace1 = buildTrace(datasetId, versionHash, itemId1);
            var trace2 = buildTrace(datasetId, versionHash, itemId2);

            var event = new TracesCreated(List.of(trace1, trace2), workspaceId, userName);
            sampler.onTracesCreated(event);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TraceToScoreLlmAsJudge>> captor = ArgumentCaptor.forClass(List.class);
            verify(onlineScorePublisher).enqueueMessage(captor.capture(),
                    eq(AutomationRuleEvaluatorType.LLM_AS_JUDGE));

            List<TraceToScoreLlmAsJudge> messages = captor.getValue();
            assertThat(messages).hasSize(2);

            // Find message for each trace
            var msg1 = messages.stream()
                    .filter(m -> m.trace().id().equals(trace1.id()))
                    .findFirst().orElseThrow();
            var msg2 = messages.stream()
                    .filter(m -> m.trace().id().equals(trace2.id()))
                    .findFirst().orElseThrow();

            // Item 1: should have exactly 2 schema fields renamed to assertion_1, assertion_2
            assertThat(msg1.llmAsJudgeCode().schema()).hasSize(2);
            assertThat(msg1.scoreNameMapping()).hasSize(2);
            // scoreNameMapping maps assertion_N → original schema name
            assertThat(msg1.scoreNameMapping()).containsEntry("assertion_1", "grammar_ok");
            assertThat(msg1.scoreNameMapping()).containsEntry("assertion_2", "spelling_ok");

            // Item 1: assertions variable should contain only its 2 assertions
            String assertions1 = msg1.llmAsJudgeCode().variables().get("assertions");
            assertThat(assertions1).contains("assertion_1");
            assertThat(assertions1).contains("assertion_2");
            assertThat(assertions1).contains("grammar_ok");
            assertThat(assertions1).contains("spelling_ok");
            // Must not contain item 2's assertion descriptions
            assertThat(assertions1).doesNotContain("tone_ok");
            assertThat(assertions1).doesNotContain("length_ok");
            assertThat(assertions1).doesNotContain("clarity_ok");

            // Item 2: should have exactly 3 schema fields renamed to assertion_1..3
            assertThat(msg2.llmAsJudgeCode().schema()).hasSize(3);
            assertThat(msg2.scoreNameMapping()).hasSize(3);
            assertThat(msg2.scoreNameMapping()).containsEntry("assertion_1", "tone_ok");
            assertThat(msg2.scoreNameMapping()).containsEntry("assertion_2", "length_ok");
            assertThat(msg2.scoreNameMapping()).containsEntry("assertion_3", "clarity_ok");

            // Item 2: assertions variable should contain only its 3 assertions
            String assertions2 = msg2.llmAsJudgeCode().variables().get("assertions");
            assertThat(assertions2).contains("assertion_1");
            assertThat(assertions2).contains("assertion_2");
            assertThat(assertions2).contains("assertion_3");
            assertThat(assertions2).contains("tone_ok");
            // Must not contain item 1's assertion descriptions
            assertThat(assertions2).doesNotContain("grammar_ok");
            assertThat(assertions2).doesNotContain("spelling_ok");
        }

        @Test
        @DisplayName("dataset-level evaluator assertions are consistent across all traces")
        void datasetLevelAssertionsConsistentAcrossTraces() {
            UUID datasetId = Generators.timeBasedEpochGenerator().generate();
            UUID versionId = Generators.timeBasedEpochGenerator().generate();
            UUID itemId1 = Generators.timeBasedEpochGenerator().generate();
            UUID itemId2 = Generators.timeBasedEpochGenerator().generate();
            UUID ruleId = Generators.timeBasedEpochGenerator().generate();
            String workspaceId = "test-workspace";
            String userName = "test-user";
            String versionHash = "abc123";

            // Dataset-level evaluator with 2 assertions
            var datasetEvalConfig = new LlmAsJudgeCode(
                    LlmAsJudgeModelParameters.builder().name("gpt-5-nano").build(),
                    List.of(LlmAsJudgeMessage.builder()
                            .role(ChatMessageType.USER)
                            .content("Evaluate: {assertions}")
                            .build()),
                    Map.of(),
                    List.of(
                            LlmAsJudgeOutputSchema.builder()
                                    .name("relevance")
                                    .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                                    .description("Response is relevant")
                                    .build(),
                            LlmAsJudgeOutputSchema.builder()
                                    .name("accuracy")
                                    .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                                    .description("Response is accurate")
                                    .build()));

            var datasetVersion = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .evaluators(List.of(EvaluatorItem.builder()
                            .name("dataset-eval")
                            .type(EvaluatorType.LLM_JUDGE)
                            .config(JsonUtils.getJsonNodeFromString(
                                    JsonUtils.writeValueAsString(datasetEvalConfig)))
                            .build()))
                    .build();

            when(datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash))
                    .thenReturn(versionId);
            when(datasetVersionService.getVersionById(workspaceId, datasetId, versionId))
                    .thenReturn(datasetVersion);
            // Items with no evaluators
            when(datasetItemService.get(itemId1))
                    .thenReturn(Mono.just(DatasetItem.builder().id(itemId1).build()));
            when(datasetItemService.get(itemId2))
                    .thenReturn(Mono.just(DatasetItem.builder().id(itemId2).build()));
            when(idGenerator.generateId()).thenReturn(ruleId);

            var trace1 = buildTrace(datasetId, versionHash, itemId1);
            var trace2 = buildTrace(datasetId, versionHash, itemId2);

            var event = new TracesCreated(List.of(trace1, trace2), workspaceId, userName);
            sampler.onTracesCreated(event);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TraceToScoreLlmAsJudge>> captor = ArgumentCaptor.forClass(List.class);
            verify(onlineScorePublisher).enqueueMessage(captor.capture(),
                    eq(AutomationRuleEvaluatorType.LLM_AS_JUDGE));

            List<TraceToScoreLlmAsJudge> messages = captor.getValue();
            assertThat(messages).hasSize(2);

            // Both traces should have exactly 2 assertions renamed to assertion_1, assertion_2
            for (var msg : messages) {
                assertThat(msg.llmAsJudgeCode().schema()).hasSize(2);
                assertThat(msg.scoreNameMapping()).hasSize(2);
                assertThat(msg.scoreNameMapping()).containsEntry("assertion_1", "relevance");
                assertThat(msg.scoreNameMapping()).containsEntry("assertion_2", "accuracy");

                String assertions = msg.llmAsJudgeCode().variables().get("assertions");
                assertThat(assertions).contains("assertion_1");
                assertThat(assertions).contains("assertion_2");
                assertThat(assertions).contains("relevance");
                assertThat(assertions).contains("accuracy");
            }
        }

        private Trace buildTrace(UUID datasetId, String versionHash, UUID datasetItemId) {
            var metadata = JsonUtils.getJsonNodeFromString(
                    "{\"eval_suite_dataset_id\": \"%s\", \"eval_suite_dataset_version_hash\": \"%s\", \"eval_suite_dataset_item_id\": \"%s\"}"
                            .formatted(datasetId, versionHash, datasetItemId));
            return Trace.builder()
                    .id(Generators.timeBasedEpochGenerator().generate())
                    .projectId(Generators.timeBasedEpochGenerator().generate())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\": [\"hello\"]}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"world\"}"))
                    .metadata(metadata)
                    .build();
        }

        @Test
        @DisplayName("falls back to latest version when eval_suite_dataset_version_hash is missing")
        void fallsBackToLatestVersionWhenVersionHashMissing() {
            UUID datasetId = Generators.timeBasedEpochGenerator().generate();
            UUID versionId = Generators.timeBasedEpochGenerator().generate();
            UUID datasetItemId = Generators.timeBasedEpochGenerator().generate();
            UUID ruleId = Generators.timeBasedEpochGenerator().generate();
            String workspaceId = "test-workspace";
            String userName = "test-user";

            var evaluatorConfig = new LlmAsJudgeCode(
                    LlmAsJudgeModelParameters.builder().name("gpt-5-nano").build(),
                    List.of(LlmAsJudgeMessage.builder()
                            .role(ChatMessageType.USER)
                            .content("Evaluate {input}")
                            .build()),
                    Map.of("input", "input"),
                    List.of(LlmAsJudgeOutputSchema.builder()
                            .name("check")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("Is it correct?")
                            .build()));

            var datasetEvaluator = EvaluatorItem.builder()
                    .name("version-evaluator")
                    .type(EvaluatorType.LLM_JUDGE)
                    .config(JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(evaluatorConfig)))
                    .build();

            var datasetVersion = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .evaluators(List.of(datasetEvaluator))
                    .build();

            when(datasetVersionService.getLatestVersion(datasetId, workspaceId))
                    .thenReturn(Optional.of(datasetVersion));
            when(datasetItemService.get(datasetItemId))
                    .thenReturn(Mono.just(DatasetItem.builder().id(datasetItemId).build()));
            when(idGenerator.generateId()).thenReturn(ruleId);

            var metadata = JsonUtils.getJsonNodeFromString(
                    "{\"eval_suite_dataset_id\": \"%s\", \"eval_suite_dataset_item_id\": \"%s\"}"
                            .formatted(datasetId, datasetItemId));

            var trace = Trace.builder()
                    .id(Generators.timeBasedEpochGenerator().generate())
                    .projectId(Generators.timeBasedEpochGenerator().generate())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\": [\"hello\"]}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"world\"}"))
                    .metadata(metadata)
                    .build();

            var event = new TracesCreated(List.of(trace), workspaceId, userName);
            sampler.onTracesCreated(event);

            verify(datasetVersionService).getLatestVersion(datasetId, workspaceId);
            verify(datasetVersionService, never()).resolveVersionId(any(), any(), any());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TraceToScoreLlmAsJudge>> captor = ArgumentCaptor.forClass(List.class);
            verify(onlineScorePublisher).enqueueMessage(captor.capture(), eq(AutomationRuleEvaluatorType.LLM_AS_JUDGE));

            List<TraceToScoreLlmAsJudge> messages = captor.getValue();
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().ruleName()).isEqualTo("version-evaluator");
        }

        @Test
        @DisplayName("falls back to eval_suite_model from trace metadata when no provider is connected")
        void fallsBackToTraceMetadataModel() {
            UUID datasetId = Generators.timeBasedEpochGenerator().generate();
            UUID versionId = Generators.timeBasedEpochGenerator().generate();
            UUID datasetItemId = Generators.timeBasedEpochGenerator().generate();
            UUID ruleId = Generators.timeBasedEpochGenerator().generate();
            String workspaceId = "test-workspace";
            String userName = "test-user";
            String versionHash = "abc123";
            String traceModel = "custom-model-from-trace";

            // No connected providers
            when(llmProviderApiKeyService.find(workspaceId))
                    .thenReturn(new ProviderApiKey.ProviderApiKeyPage(0, 0, 0, List.of(), List.of()));

            var evaluatorConfig = new LlmAsJudgeCode(
                    LlmAsJudgeModelParameters.builder().name("placeholder").build(),
                    List.of(LlmAsJudgeMessage.builder()
                            .role(ChatMessageType.USER)
                            .content("Evaluate {input}")
                            .build()),
                    Map.of("input", "input"),
                    List.of(LlmAsJudgeOutputSchema.builder()
                            .name("check")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("Is it correct?")
                            .build()));

            var datasetVersion = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .evaluators(List.of(EvaluatorItem.builder()
                            .name("test-evaluator")
                            .type(EvaluatorType.LLM_JUDGE)
                            .config(JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(evaluatorConfig)))
                            .build()))
                    .build();

            when(datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash))
                    .thenReturn(versionId);
            when(datasetVersionService.getVersionById(workspaceId, datasetId, versionId))
                    .thenReturn(datasetVersion);
            when(datasetItemService.get(datasetItemId))
                    .thenReturn(Mono.just(DatasetItem.builder().id(datasetItemId).build()));
            when(idGenerator.generateId()).thenReturn(ruleId);

            var metadata = JsonUtils.getJsonNodeFromString(
                    "{\"eval_suite_dataset_id\": \"%s\", \"eval_suite_dataset_version_hash\": \"%s\", \"eval_suite_dataset_item_id\": \"%s\", \"eval_suite_model\": \"%s\"}"
                            .formatted(datasetId, versionHash, datasetItemId, traceModel));

            var trace = Trace.builder()
                    .id(Generators.timeBasedEpochGenerator().generate())
                    .projectId(Generators.timeBasedEpochGenerator().generate())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\": [\"hello\"]}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"world\"}"))
                    .metadata(metadata)
                    .build();

            var event = new TracesCreated(List.of(trace), workspaceId, userName);
            sampler.onTracesCreated(event);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TraceToScoreLlmAsJudge>> captor = ArgumentCaptor.forClass(List.class);
            verify(onlineScorePublisher).enqueueMessage(captor.capture(), eq(AutomationRuleEvaluatorType.LLM_AS_JUDGE));

            List<TraceToScoreLlmAsJudge> messages = captor.getValue();
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().llmAsJudgeCode().model().name()).isEqualTo(traceModel);
        }

        @Test
        @DisplayName("skips evaluation when no provider connected and no eval_suite_model in metadata")
        void skipsEvaluationWhenNoModelResolved() {
            UUID datasetId = Generators.timeBasedEpochGenerator().generate();
            String workspaceId = "test-workspace";
            String userName = "test-user";

            // No connected providers
            when(llmProviderApiKeyService.find(workspaceId))
                    .thenReturn(new ProviderApiKey.ProviderApiKeyPage(0, 0, 0, List.of(), List.of()));

            // Metadata has dataset info but no eval_suite_model
            var metadata = JsonUtils.getJsonNodeFromString(
                    "{\"eval_suite_dataset_id\": \"%s\", \"eval_suite_dataset_version_hash\": \"abc123\", \"eval_suite_dataset_item_id\": \"%s\"}"
                            .formatted(datasetId, Generators.timeBasedEpochGenerator().generate()));

            var trace = Trace.builder()
                    .id(Generators.timeBasedEpochGenerator().generate())
                    .projectId(Generators.timeBasedEpochGenerator().generate())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\": [\"hello\"]}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"world\"}"))
                    .metadata(metadata)
                    .build();

            var event = new TracesCreated(List.of(trace), workspaceId, userName);
            sampler.onTracesCreated(event);

            verify(onlineScorePublisher, never()).enqueueMessage(any(), any());
            verify(datasetVersionService, never()).resolveVersionId(any(), any(), any());
        }
    }

}
