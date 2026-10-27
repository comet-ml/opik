package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.api.ModelParams;
import com.comet.opik.api.ModelParams.EffortParam;
import com.comet.opik.api.ModelParams.NumberParam;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService.CuratedCatalog;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService.ModelEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.collections4.CollectionUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmModelCatalogTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private static final ModelParams O3 = params("""
            temperature: {accepted: false}
            maxOutputTokens: {accepted: true, min: 1, max: 128000, default: 4000}
            effort: {accepted: true, levels: [low, medium, high], default: high}
            routes:
              responses_api:
                temperature: {accepted: false}
                maxOutputTokens: {accepted: true, min: 16, max: 128000, default: 4000}
                effort: {accepted: true, levels: [low, medium, high], default: high}
            """);

    private static final ModelParams OPENAI_PROFILE = params("""
            temperature: {accepted: true, min: 0, max: 2, default: 0}
            maxOutputTokens: {accepted: true, min: 1, max: 128000, default: 4000}
            effort: {accepted: false}
            routes:
              responses_api:
                temperature: {accepted: true, min: 0, max: 2, default: 0}
                maxOutputTokens: {accepted: true, min: 16, max: 128000, default: 4000}
                effort: {accepted: false}
            """);

    private static final ModelParams OPENAI_REASONING_PROFILE = params("""
            temperature: {accepted: false}
            maxOutputTokens: {accepted: true, min: 1, max: 128000, default: 4000}
            effort: {accepted: false}
            routes:
              responses_api:
                temperature: {accepted: false}
                maxOutputTokens: {accepted: true, min: 16, max: 128000, default: 4000}
                effort: {accepted: false}
            """);

    private static final ModelParams ANTHROPIC_PROFILE = params("""
            temperature: {accepted: false}
            effort: {accepted: true, levels: [low, medium, high], default: high}
            """);

    private static final ModelParams CLAUDE_SONNET_4_6 = params("""
            temperature: {accepted: true, min: 0, max: 1, default: 0}
            effort: {accepted: true, levels: [low, medium, high, max], default: high}
            """);

    private static LlmModelRegistryService service(String localOverridePath) {
        return service("llm-models-test.yaml", localOverridePath);
    }

    private static LlmModelRegistryService service(String generatedResource, String localOverridePath) {
        return service(generatedResource, "llm-models-test-curated.yaml", localOverridePath);
    }

    private static LlmModelRegistryService service(String generatedResource, String curatedResource,
            String localOverridePath) {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource(generatedResource);
        config.setCuratedResource(curatedResource);
        config.setLocalOverridePath(localOverridePath);
        return new LlmModelRegistryService(config);
    }

    private static LlmModelDefinition model(LlmModelRegistryService service, String provider, String id) {
        return service.getRegistry().get(provider).stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private static ModelParams params(String yaml) {
        try {
            return YAML_MAPPER.readValue(yaml, ModelParams.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    class Resolution {

        @Test
        void aModelWithoutARowTakesItsProviderProfile() {
            assertThat(model(service(null), "openai", "gpt-4o").params()).isEqualTo(OPENAI_PROFILE);
        }

        @Test
        void theRegistryReasoningFlagAddsTheReasoningProfileAndTheRowWins() {
            assertThat(model(service(null), "openai", "o3").params()).isEqualTo(O3);
        }

        @Test
        void aRowProfileAndParamsOverlayTheProviderProfileFieldByField() {
            assertThat(model(service(null), "anthropic", "claude-sonnet-4-6").params()).isEqualTo(CLAUDE_SONNET_4_6);
        }

        @Test
        void aCuratedProfileWinsOverTheParamsTheSyncGenerated() {
            var service = service("llm-models-test-generated-params.yaml", null);

            assertThat(model(service, "anthropic", "claude-sonnet-4-6").params()).isEqualTo(CLAUDE_SONNET_4_6);
        }

        @Test
        void aRowWaitingForTheSyncAppliesOnceTheSyncListsTheModel() {
            var service = service("llm-models-test-generated-params.yaml", null);

            assertThat(model(service, "anthropic", "claude-not-yet-synced").params()).isEqualTo(params("""
                    temperature: {accepted: false}
                    effort: {accepted: true, levels: [low], default: high}
                    """));
        }

        @Test
        void aModelsRepeatedCuratedRowsKeepTheFieldsOfBoth() {
            var service = service("llm-models-test.yaml", "llm-models-test-curated-repeated-row.yaml", null);

            assertThat(service.getRegistry().get("anthropic")).containsExactly(LlmModelDefinition.builder()
                    .id("claude-sonnet-4-6")
                    .label("from the first row")
                    .params(params("""
                            temperature: {accepted: true, min: 0, max: 1, default: 0}
                            effort: {accepted: true, levels: [low], default: low}
                            """))
                    .build());
        }

        @Test
        void aCuratedRowNeverAddsAModel() {
            assertThat(service(null).getRegistry().get("anthropic")).containsExactly(LlmModelDefinition.builder()
                    .id("claude-sonnet-4-6")
                    .params(CLAUDE_SONNET_4_6)
                    .build());
        }

        @Test
        void aCuratedFileNamingAProfileItDoesNotDefineStopsTheLoad() {
            assertThatThrownBy(
                    () -> service("llm-models-test.yaml", "llm-models-test-curated-unknown-profile.yaml", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing-reasoning")
                    .hasMessageContaining("missing-row-profile")
                    .hasMessageContaining("missing-id-profile");
        }

        @Test
        void anIdTakesTheProfileOfTheFirstPatternItMatches() {
            var service = service("llm-models-test.yaml", "llm-models-test-curated-id-profiles.yaml", null);

            assertThat(service.paramsOf("gemini", "gemini-1.5-flash")).isEqualTo(params("""
                    temperature: {accepted: true}
                    """));
            assertThat(service.paramsOf("gemini", "gemini-9-pro")).isEqualTo(params("""
                    temperature: {accepted: false}
                    topP: {accepted: false}
                    """));
            assertThat(service.paramsOf("gemini", "gemma-9-it")).isEqualTo(params("""
                    temperature: {accepted: false}
                    """));
        }

        @Test
        void aRowProfileWinsOverTheProfileItsIdMatches() {
            var service = service("llm-models-test.yaml", "llm-models-test-curated-id-profiles.yaml", null);

            assertThat(service.curatedParamsOf("gemini", "gemini-1.5-pinned")).isEqualTo(params("""
                    temperature: {accepted: false}
                    """));
        }

        @Test
        void aLocalOverrideNamingAnUnknownProfileKeepsItsOwnParams(@TempDir Path tempDir) throws IOException {
            var override = tempDir.resolve("override.yaml");
            Files.writeString(override, """
                    anthropic:
                      - id: "claude-sonnet-4-6"
                        profile: "no-such-profile"
                        params:
                          effort: {default: low}
                    """);

            assertThat(model(service(override.toString()), "anthropic", "claude-sonnet-4-6").params())
                    .isEqualTo(CLAUDE_SONNET_4_6.toBuilder()
                            .effort(CLAUDE_SONNET_4_6.effort().toBuilder().defaultLevel("low").build())
                            .build());
        }

        @Test
        void withoutTheCuratedLayerAModelGetsTheParamsTheShippedLayerWouldGiveIt() {
            var withoutCurated = service("llm-models-test.yaml", "", null);
            var withShipped = service("llm-models-test.yaml", "llm-models-curated.yaml", null);

            var params = withoutCurated.paramsOf("anthropic", "claude-sonnet-4-6");

            assertThat(params).isEqualTo(withShipped.paramsOf("anthropic", "claude-sonnet-4-6"));
            assertThat(params.temperature().accepted())
                    .as("only the row's own profile accepts it; the anthropic profile refuses it")
                    .isTrue();
            assertThat(model(withoutCurated, "anthropic", "claude-sonnet-4-6").params())
                    .as("the API serves what the rules read")
                    .isEqualTo(params);
        }

        @Test
        void withoutTheCuratedLayerARowWithSomeParamsKeepsTheShippedRulesForTheRest(@TempDir Path tempDir)
                throws IOException {
            var override = tempDir.resolve("override.yaml");
            try (var fixture = getClass().getClassLoader()
                    .getResourceAsStream("llm-models-override-claude-no-sampling.yaml")) {
                Files.copy(fixture, override);
            }
            var withoutCurated = service("llm-models-test.yaml", "", override.toString());
            var withShipped = service("llm-models-test.yaml", "llm-models-curated.yaml", override.toString());

            var params = withoutCurated.paramsOf("anthropic", "claude-sonnet-4-6");

            assertThat(params).isEqualTo(withShipped.paramsOf("anthropic", "claude-sonnet-4-6"));
            assertThat(params.topP().accepted()).as("the override refuses it").isFalse();
            assertThat(params.temperature().accepted()).as("the shipped row accepts it").isTrue();
            assertThat(params.effort().levels()).containsExactly("low", "medium", "high", "max");
            assertThat(params.thinksByDefault()).isFalse();
            assertThat(model(withoutCurated, "anthropic", "claude-sonnet-4-6").params()).isEqualTo(params);
        }

        @Test
        void curatedParamsFollowAReasoningFlagThatAReloadChanges(@TempDir Path tempDir) throws IOException {
            var override = tempDir.resolve("override.yaml");
            Files.writeString(override, """
                    openai:
                      - id: "gpt-4o"
                        reasoning: false
                    """);
            var service = service(override.toString());
            assertThat(service.curatedParamsOf("openai", "gpt-4o")).isEqualTo(OPENAI_PROFILE);

            Files.writeString(override, """
                    openai:
                      - id: "gpt-4o"
                        reasoning: true
                    """);
            service.reload();

            assertThat(service.curatedParamsOf("openai", "gpt-4o")).isEqualTo(OPENAI_REASONING_PROFILE);
        }

        @Test
        void aProviderWithoutDefaultsGetsNoParams() {
            assertThat(model(service(null), "vertex-ai", "gemini-2.0-flash-001").params()).isNull();
        }

        @Test
        void theProfilesAreWhatAnUnlistedIdOfEachProviderGets() {
            var service = service(null);

            assertThat(service.getProfiles())
                    .isEqualTo(Map.of("openai", OPENAI_PROFILE, "anthropic", ANTHROPIC_PROFILE));
            assertThat(service.paramsOf("anthropic", "an-id-no-layer-lists")).isEqualTo(ANTHROPIC_PROFILE);
        }

        @Test
        void aLocalOverrideWinsOverTheCuratedRowOnlyWhereItSetsAField(@TempDir Path tempDir) throws IOException {
            var override = tempDir.resolve("override.yaml");
            Files.writeString(override, """
                    anthropic:
                      - id: "claude-sonnet-4-6"
                        params:
                          effort: {levels: [low]}
                    openai:
                      - id: "o3"
                        label: "o3 from override"
                    """);
            var service = service(override.toString());

            assertThat(model(service, "anthropic", "claude-sonnet-4-6").params()).isEqualTo(params("""
                    temperature: {accepted: true, min: 0, max: 1, default: 0}
                    effort: {accepted: true, levels: [low], default: high}
                    """));
            assertThat(model(service, "openai", "o3")).isEqualTo(LlmModelDefinition.builder()
                    .id("o3")
                    .label("o3 from override")
                    .structuredOutput(true)
                    .reasoning(true)
                    .params(O3)
                    .build());
        }

        @Test
        void aRowsOnlyRouteReachesEveryRouteTheApiServes(@TempDir Path tempDir) throws IOException {
            var override = tempDir.resolve("override.yaml");
            Files.writeString(override, """
                    openai:
                      - id: "o3"
                        params: {onlyRoute: responses_api}
                    """);

            assertThat(model(service(override.toString()), "openai", "o3").params()).isEqualTo(O3.toBuilder()
                    .onlyRoute(ModelParams.RESPONSES_API_ROUTE)
                    .routes(Map.of(ModelParams.RESPONSES_API_ROUTE,
                            O3.routes().get(ModelParams.RESPONSES_API_ROUTE).toBuilder()
                                    .onlyRoute(ModelParams.RESPONSES_API_ROUTE)
                                    .build()))
                    .build());
        }
    }

    @Test
    void paramsKeepTheirOwnCopiesOfLevelsAndRoutesInOrder() {
        var levels = new ArrayList<>(List.of("low", "high"));
        var routes = new LinkedHashMap<String, ModelParams>();
        routes.put("b_route", ModelParams.builder().build());
        routes.put("a_route", ModelParams.builder().build());

        var params = ModelParams.builder().effort(new EffortParam(true, levels, "low")).routes(routes).build();
        levels.add("medium");
        routes.put("c_route", ModelParams.builder().build());

        assertThat(params.effort().levels()).containsExactly("low", "high");
        assertThat(params.routes().keySet()).containsExactly("b_route", "a_route");
        assertThatThrownBy(() -> params.effort().levels().add("max")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> params.routes().put("d_route", ModelParams.builder().build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRouteWithoutParamsIsDropped() {
        var routes = new LinkedHashMap<String, ModelParams>();
        routes.put("responses_api", null);

        var params = ModelParams.builder()
                .temperature(new NumberParam(true, BigDecimal.ZERO, BigDecimal.TWO, BigDecimal.ZERO))
                .routes(routes)
                .build();

        assertThat(params.routes()).isEmpty();
        assertThat(params.settled().routes()).isNull();
    }

    @Nested
    class BundledCatalog {

        private final CuratedCatalog catalog = read("llm-models-curated.yaml", new TypeReference<>() {
        });
        private final Map<String, List<ModelEntry>> generated = read("llm-models-default.yaml",
                new TypeReference<>() {
                });
        private final Map<String, List<LlmModelDefinition>> registry = new LlmModelRegistryService(
                new LlmModelRegistryConfig()).getRegistry();

        private static <T> T read(String resource, TypeReference<T> type) {
            try (InputStream is = LlmModelCatalogTest.class.getClassLoader().getResourceAsStream(resource)) {
                return YAML_MAPPER.readValue(is, type);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        // A row the sync has since listed passes too: sync PRs are made by a bot, and the loader logs that its
        // awaitingSync can be removed.
        @Test
        void everyRowNamesAListedModelUnlessItWaitsForTheSync() {
            catalog.models().forEach((provider, rows) -> rows.stream()
                    .filter(row -> !Boolean.TRUE.equals(row.awaitingSync()))
                    .forEach(row -> assertThat(generated.getOrDefault(provider, List.of()))
                            .as("%s/%s is not listed by the sync; mark it awaitingSync if it is written ahead of it",
                                    provider, row.id())
                            .extracting(ModelEntry::id)
                            .contains(row.id())));
        }

        @Test
        void noModelHasTwoRows() {
            catalog.models().forEach((provider, rows) -> assertThat(rows)
                    .as(provider)
                    .extracting(ModelEntry::id)
                    .doesNotHaveDuplicates());
        }

        @Test
        void everyModelOfAProviderWithDefaultsResolvesToConsistentParams() {
            catalog.defaults().keySet().forEach(provider -> registry.getOrDefault(provider, List.of())
                    .forEach(model -> {
                        var label = provider + "/" + model.id();
                        assertThat(model.params()).as(label).isNotNull();
                        allVariants(model.params()).forEach(params -> assertConsistent(label, params));
                    }));
        }

        // The consistency check can't catch a reasoning model served with no effort control, since refusing
        // effort is consistent; a row has to say so on purpose.
        @Test
        void everyOpenAiReasoningModelOffersEffortUnlessARowRefusesIt() {
            var refusedByARow = catalog.models().getOrDefault("openai", List.of()).stream()
                    .filter(row -> row.params() != null && row.params().effort() != null
                            && Boolean.FALSE.equals(row.params().effort().accepted()))
                    .map(ModelEntry::id)
                    .collect(Collectors.toSet());

            var withoutLevels = registry.getOrDefault("openai", List.of()).stream()
                    .filter(LlmModelDefinition::reasoning)
                    .filter(model -> !refusedByARow.contains(model.id()))
                    .filter(model -> CollectionUtils.isEmpty(model.params().effort().levels()))
                    .map(LlmModelDefinition::id)
                    .toList();

            assertThat(withoutLevels)
                    .as("OpenAI reasoning models without effort levels; give each a curated row with them, or"
                            + " effort: {accepted: false} if it refuses reasoning_effort")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(value = {"gpt-5.2, null", "gpt-5.4, EFFORT_NONE_ONLY", "gpt-6-luna, EFFORT_NONE_ONLY",
                "gpt-6-astra, REFUSED", "gpt-6.1-sol, REFUSED", "gpt-4o, null"}, nullValues = "null")
        void theListedOpenAiModelsCarryHowChatCompletionsTakesTheirTools(String id,
                ModelParams.ChatCompletionsTools expected) {
            var model = registry.get("openai").stream().filter(listed -> listed.id().equals(id)).findFirst()
                    .orElseThrow();

            assertThat(model.params().tools() == null ? null : model.params().tools().chatCompletions())
                    .isEqualTo(expected);
        }

        // A judge whose key has its own base URL calls tools on these models with reasoning_effort "none".
        @Test
        void everyOpenAiModelThatTakesToolsOnlyWithoutReasoningCanTurnReasoningOff() {
            var cannotTurnItOff = registry.getOrDefault("openai", List.of()).stream()
                    .filter(model -> model.params().tools() != null && model.params().tools()
                            .chatCompletions() == ModelParams.ChatCompletionsTools.EFFORT_NONE_ONLY)
                    .filter(model -> !model.params().effort().levels().contains("none"))
                    .map(LlmModelDefinition::id)
                    .toList();

            assertThat(cannotTurnItOff).isEmpty();
        }

        private static Stream<ModelParams> allVariants(ModelParams params) {
            var routes = params.routes() == null ? Stream.<ModelParams>empty() : params.routes().values().stream();
            return Stream.concat(Stream.of(params), routes);
        }

        private static void assertConsistent(String label, ModelParams params) {
            Stream.of(params.temperature(), params.topP(), params.topK(), params.frequencyPenalty(),
                    params.presencePenalty(), params.maxOutputTokens())
                    .filter(range -> range != null && Boolean.TRUE.equals(range.accepted()))
                    .forEach(range -> {
                        if (range.min() != null && range.max() != null) {
                            assertThat(range.min()).as(label).isLessThanOrEqualTo(range.max());
                        }
                        if (range.defaultValue() != null && range.min() != null) {
                            assertThat(range.defaultValue()).as(label).isGreaterThanOrEqualTo(range.min());
                        }
                        if (range.defaultValue() != null && range.max() != null) {
                            assertThat(range.defaultValue()).as(label).isLessThanOrEqualTo(range.max());
                        }
                    });
            var effort = params.effort();
            if (effort != null && effort.accepted()) {
                assertThat(effort.levels()).as(label).isNotEmpty();
                assertThat(effort.levels()).as(label).contains(effort.defaultLevel());
            }
        }
    }
}
