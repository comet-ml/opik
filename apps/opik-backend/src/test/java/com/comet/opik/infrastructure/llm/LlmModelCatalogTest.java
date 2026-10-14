package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.api.ModelParams;
import com.comet.opik.api.ModelParams.EffortParam;
import com.comet.opik.api.ModelParams.NumberParam;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService.CuratedCatalog;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService.ModelEntry;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
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

    private static LlmModelRegistryService service(String localOverridePath) {
        return service("llm-models-test.yaml", localOverridePath);
    }

    private static LlmModelRegistryService service(String generatedResource, String localOverridePath) {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource(generatedResource);
        config.setCuratedResource("llm-models-test-curated.yaml");
        config.setLocalOverridePath(localOverridePath);
        return new LlmModelRegistryService(config);
    }

    private static LlmModelDefinition model(LlmModelRegistryService service, String provider, String id) {
        return service.getRegistry().get(provider).stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private static NumberParam range(boolean accepted, int min, int max, int defaultValue) {
        return new NumberParam(accepted, BigDecimal.valueOf(min), BigDecimal.valueOf(max),
                BigDecimal.valueOf(defaultValue));
    }

    @Nested
    class Resolution {

        @Test
        void aModelWithoutARowTakesItsProviderProfile() {
            var params = model(service(null), "openai", "gpt-4o").params();

            assertThat(params.temperature()).isEqualTo(range(true, 0, 2, 0));
            assertThat(params.effort()).isEqualTo(new EffortParam(false, null, null));
            assertThat(params.routes().get("responses_api").maxOutputTokens()).isEqualTo(range(true, 16, 128000, 4000));
        }

        @Test
        void theRegistryReasoningFlagAddsTheReasoningProfileAndTheRowWins() {
            var params = model(service(null), "openai", "o3").params();

            assertThat(params.temperature()).isEqualTo(new NumberParam(false, null, null, null));
            assertThat(params.effort()).isEqualTo(new EffortParam(true, List.of("low", "medium", "high"), "high"));
        }

        @Test
        void aRowProfileAndParamsOverlayTheProviderProfileFieldByField() {
            var params = model(service(null), "anthropic", "claude-sonnet-4-6").params();

            assertThat(params.temperature()).isEqualTo(range(true, 0, 1, 0));
            assertThat(params.effort())
                    .isEqualTo(new EffortParam(true, List.of("low", "medium", "high", "max"), "high"));
        }

        @Test
        void aCuratedProfileWinsOverTheParamsTheSyncGenerated() {
            var params = model(service("llm-models-test-generated-params.yaml", null), "anthropic",
                    "claude-sonnet-4-6").params();

            assertThat(params.temperature()).isEqualTo(range(true, 0, 1, 0));
            assertThat(params.effort())
                    .isEqualTo(new EffortParam(true, List.of("low", "medium", "high", "max"), "high"));
        }

        @Test
        void aRowWaitingForTheSyncAppliesOnceTheSyncListsTheModel() {
            var params = model(service("llm-models-test-generated-params.yaml", null), "anthropic",
                    "claude-not-yet-synced").params();

            assertThat(params.effort()).isEqualTo(new EffortParam(true, List.of("low"), "high"));
        }

        @Test
        void aModelsRepeatedCuratedRowsKeepTheFieldsOfBoth() {
            var generated = Map.of("anthropic", List.of(ModelEntry.builder().id("claude-x").build()));
            var curated = Map.of("anthropic", List.of(
                    ModelEntry.builder().id("claude-x").label("from the first row")
                            .params(ModelParams.builder().temperature(range(true, 0, 1, 0)).build()).build(),
                    ModelEntry.builder().id("claude-x")
                            .params(ModelParams.builder().effort(new EffortParam(true, List.of("low"), "low")).build())
                            .build()));

            var merged = LlmModelRegistryService.applyCurated(generated, curated).get("anthropic");

            assertThat(merged).hasSize(1);
            assertThat(merged.getFirst().label()).isEqualTo("from the first row");
            assertThat(merged.getFirst().params().temperature()).isEqualTo(range(true, 0, 1, 0));
            assertThat(merged.getFirst().params().effort()).isEqualTo(new EffortParam(true, List.of("low"), "low"));
        }

        @Test
        void aCuratedRowNeverAddsAModel() {
            assertThat(service(null).getRegistry().get("anthropic"))
                    .extracting(LlmModelDefinition::id)
                    .containsExactly("claude-sonnet-4-6");
        }

        @Test
        void aProviderWithoutDefaultsGetsNoParams() {
            assertThat(model(service(null), "vertex-ai", "gemini-2.0-flash-001").params()).isNull();
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

            var claude = model(service, "anthropic", "claude-sonnet-4-6").params();
            assertThat(claude.effort()).isEqualTo(new EffortParam(true, List.of("low"), "high"));
            assertThat(claude.temperature()).isEqualTo(range(true, 0, 1, 0));
            assertThat(model(service, "openai", "o3").params())
                    .isEqualTo(model(service(null), "openai", "o3").params());
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

        var params = ModelParams.builder().temperature(range(true, 0, 2, 0)).routes(routes).build();

        assertThat(params.routes()).isEmpty();
        assertThat(params.settled().routes()).isNull();
    }

    @Test
    void theApiNamesParamsFieldsTheWayItNamesTheOtherFields() {
        var json = JsonUtils.writeValueAsString(model(service(null), "openai", "o3"));

        assertThat(JsonUtils.getJsonNodeFromString(json)).isEqualTo(JsonUtils.getJsonNodeFromString("""
                {
                  "id": "o3",
                  "structured_output": true,
                  "reasoning": true,
                  "params": {
                    "temperature": {"accepted": false},
                    "max_output_tokens": {"accepted": true, "min": 1, "max": 128000, "default": 4000},
                    "effort": {"accepted": true, "levels": ["low", "medium", "high"], "default": "high"},
                    "routes": {
                      "responses_api": {
                        "temperature": {"accepted": false},
                        "max_output_tokens": {"accepted": true, "min": 16, "max": 128000, "default": 4000},
                        "effort": {"accepted": true, "levels": ["low", "medium", "high"], "default": "high"}
                      }
                    }
                  }
                }"""));
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

        @Test
        void everyProfileItNamesExists() {
            var named = new ArrayList<String>();
            catalog.defaults().values().forEach(d -> {
                named.add(d.profile());
                if (d.reasoningProfile() != null) {
                    named.add(d.reasoningProfile());
                }
            });
            catalog.models().values().forEach(rows -> rows.stream()
                    .map(ModelEntry::profile)
                    .filter(profile -> profile != null)
                    .forEach(named::add));

            assertThat(catalog.profiles().keySet()).containsAll(named);
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

            registry.getOrDefault("openai", List.of()).stream()
                    .filter(LlmModelDefinition::reasoning)
                    .filter(model -> !refusedByARow.contains(model.id()))
                    .forEach(model -> assertThat(model.params().effort().levels())
                            .as("openai/%s is a reasoning model without effort levels; give it a curated row with"
                                    + " them, or effort: {accepted: false} if it refuses reasoning_effort",
                                    model.id())
                            .isNotEmpty());
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
