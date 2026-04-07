package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.EvaluatorType;
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

        private EvalSuiteAssertionSampler sampler;

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            var evalSuiteConfig = new EvalSuiteConfig();
            sampler = new EvalSuiteAssertionSampler(
                    datasetItemService, datasetVersionService, onlineScorePublisher, idGenerator,
                    evalSuiteConfig);
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
            assertThat(message.llmAsJudgeCode().schema().getFirst().name()).isEqualTo("check");

            // Verify assertions variable was injected by injectAssertionsVariable
            assertThat(message.llmAsJudgeCode().variables()).containsKey("assertions");
            String assertionsValue = message.llmAsJudgeCode().variables().get("assertions").toString();
            assertThat(assertionsValue).contains("check");
            assertThat(assertionsValue).contains("Is it correct?");

            // Verify score name mapping from schema name → description
            assertThat(message.scoreNameMapping()).containsEntry("check", "Is it correct?");
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
    }

}
