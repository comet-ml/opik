package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.EvaluatorType;
import com.comet.opik.api.PromptType;
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
import com.comet.opik.utils.TemplateParseUtils;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for eval suite assertion evaluation logic in EvalSuiteAssertionSampler.
 * The SDK's LLMJudge uses Python-style {variable} templates, but the backend's
 * OnlineScoringEngine uses Mustache {{variable}} templates. This test verifies
 * the conversion and substitution pipeline.
 */
class EvalSuiteAssertionSamplerTest {

    // SDK-style template (single braces) - as stored in evaluator config
    private static final String SDK_USER_TEMPLATE = """
            ## Input
            ---BEGIN INPUT---
            {input}
            ---END INPUT---

            ## Output
            ---BEGIN OUTPUT---
            {output}
            ---END OUTPUT---

            ## Assertions
            {assertions}
            """;

    @Nested
    @DisplayName("Assertions JSON injection")
    class AssertionsJsonInjection {

        @Test
        @DisplayName("schema is serialized as JSON for the assertions variable")
        void schemaSerializedAsJson() {
            var schema = List.of(
                    LlmAsJudgeOutputSchema.builder()
                            .name("no_toc")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("The report must not include a table of contents.")
                            .build(),
                    LlmAsJudgeOutputSchema.builder()
                            .name("has_summary")
                            .type(LlmAsJudgeOutputSchemaType.BOOLEAN)
                            .description("The report must include a summary section.")
                            .build());

            String assertionsText = JsonUtils.writeValueAsString(schema);

            assertThat(assertionsText).contains("no_toc");
            assertThat(assertionsText).contains("has_summary");
            assertThat(assertionsText).contains("The report must not include a table of contents.");
            assertThat(assertionsText).contains("The report must include a summary section.");
        }
    }

    @Nested
    @DisplayName("Python template rendering")
    class PythonTemplateRendering {

        @Test
        @DisplayName("Python single-brace placeholders are properly substituted")
        void singleBracePlaceholdersSubstitutedByPython() {
            String pythonTemplate = "Hello {input}, your output is {output}";
            Map<String, String> replacements = Map.of("input", "world", "output", "42");

            String rendered = TemplateParseUtils.render(pythonTemplate, replacements, PromptType.PYTHON);

            assertThat(rendered).isEqualTo("Hello world, your output is 42");
        }

        @Test
        @DisplayName("SDK single-brace placeholders are NOT valid Mustache - left as-is")
        void singleBracePlaceholdersNotSubstitutedByMustache() {
            String sdkTemplate = "Hello {input}, your output is {output}";
            Map<String, String> replacements = Map.of("input", "world", "output", "42");

            String rendered = TemplateParseUtils.render(sdkTemplate, replacements, PromptType.MUSTACHE);

            assertThat(rendered).isEqualTo(sdkTemplate);
        }

        @Test
        @DisplayName("preserves JSON-like braces that don't match variable pattern")
        void preservesJsonBraces() {
            String template = "{\"key\": \"value\"} and {input}";
            Map<String, String> replacements = Map.of("input", "hello");

            String rendered = TemplateParseUtils.render(template, replacements, PromptType.PYTHON);

            assertThat(rendered).contains("hello");
            assertThat(rendered).contains("{\"key\"");
        }

        @Test
        @DisplayName("does not substitute double-brace patterns")
        void doesNotSubstituteDoubleBraces() {
            String template = "{{input}} and {output}";
            Map<String, String> replacements = Map.of("input", "a", "output", "b");

            String rendered = TemplateParseUtils.render(template, replacements, PromptType.PYTHON);

            assertThat(rendered).isEqualTo("{{input}} and b");
        }

        @Test
        @DisplayName("leaves unmatched variables as-is")
        void leavesUnmatchedVariables() {
            String template = "Hello {input}, {missing}";
            Map<String, String> replacements = Map.of("input", "world");

            String rendered = TemplateParseUtils.render(template, replacements, PromptType.PYTHON);

            assertThat(rendered).isEqualTo("Hello world, {missing}");
        }
    }

    @Nested
    @DisplayName("End-to-end variable substitution")
    class EndToEndSubstitution {

        @Test
        @DisplayName("OnlineScoringEngine.toReplacements resolves literal assertions from variables map")
        void toReplacementsHandlesLiteralAssertions() {
            String assertionsText = "[{\"name\":\"check\",\"description\":\"Is correct\"}]";
            var variables = Map.of(
                    "input", "input",
                    "output", "output",
                    "assertions", assertionsText);

            var trace = Trace.builder()
                    .id(Generators.timeBasedEpochGenerator().generate())
                    .projectId(Generators.timeBasedEpochGenerator().generate())
                    .name("test-trace")
                    .startTime(Instant.now())
                    .endTime(Instant.now())
                    .input(JsonUtils.getJsonNodeFromString("{\"messages\": [\"hello\"]}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"world\"}"))
                    .build();

            Map<String, String> replacements = OnlineScoringEngine.toReplacements(variables, trace);

            assertThat(replacements).containsKey("input");
            assertThat(replacements).containsKey("output");
            assertThat(replacements).containsEntry("assertions", assertionsText);
        }

        @Test
        @DisplayName("full pipeline: Python template + JSON assertions renders correctly")
        void fullPipelineProducesCorrectOutput() {
            String pythonTemplate = "Input: {input}, Output: {output}, Assertions: {assertions}";
            String assertionsJson = "[{\"name\":\"no_toc\",\"description\":\"No TOC\"}]";

            Map<String, String> replacements = Map.of(
                    "input", "user question",
                    "output", "ai answer",
                    "assertions", assertionsJson);

            String rendered = TemplateParseUtils.render(pythonTemplate, replacements, PromptType.PYTHON);

            assertThat(rendered).isEqualTo(
                    "Input: user question, Output: ai answer, Assertions: "
                            + "[{\"name\":\"no_toc\",\"description\":\"No TOC\"}]");
        }
    }

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
            assertThat(messages.getFirst().categoryName()).isEqualTo("suite_assertion");
            assertThat(messages.getFirst().trace().id()).isEqualTo(trace.id());
            assertThat(messages.getFirst().ruleName()).isEqualTo("item-evaluator");
            assertThat(messages.getFirst().llmAsJudgeCode().model().name()).isEqualTo("gpt-5-nano");
            assertThat(messages.getFirst().llmAsJudgeCode().schema()).hasSize(1);
            assertThat(messages.getFirst().llmAsJudgeCode().schema().getFirst().name()).isEqualTo("check");
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
    }

}
