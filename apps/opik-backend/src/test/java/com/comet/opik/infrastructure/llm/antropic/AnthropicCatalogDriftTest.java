package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ModelParams;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils.bundledRegistry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requests still follow AnthropicModelName's sets while the catalog only serves its rows, and the daily sync updates
 * neither from the other, so a change to one has to come with the same change to the other.
 */
class AnthropicCatalogDriftTest {

    private static final Map<String, ModelParams> CATALOG = bundledRegistry().getRegistry()
            .getOrDefault(LlmProvider.ANTHROPIC.getValue(), List.of()).stream()
            .collect(Collectors.toMap(LlmModelDefinition::id, LlmModelDefinition::params));

    private static Stream<String> modelIds() {
        var ids = new TreeSet<>(AnthropicModelName.allModelIds());
        ids.addAll(CATALOG.keySet());
        return ids.stream();
    }

    @ParameterizedTest
    @MethodSource("modelIds")
    void takesSamplingParamsExactlyWhenTheEnumSaysSo(String id) {
        var takesSampling = AnthropicModelName.samplingCapableModelIds().contains(id);

        assertThat(params(id).temperature().accepted()).as("%s: temperature accepted", id).isEqualTo(takesSampling);
        assertThat(params(id).topP().accepted()).as("%s: top_p accepted", id).isEqualTo(takesSampling);
    }

    @ParameterizedTest
    @MethodSource("modelIds")
    void thinksByDefaultExactlyWhenTheEnumSaysSo(String id) {
        var thinks = !AnthropicModelName.thinkingOffByDefaultModelIds().contains(id);

        assertThat(params(id).thinksByDefault()).as("%s: thinks by default", id).isEqualTo(thinks);
    }

    @ParameterizedTest
    @MethodSource("modelIds")
    void offersExactlyTheEffortLevelsTheEnumAllows(String id) {
        var levels = AnthropicModelName.effortLevels(id).orElse(AnthropicModelName.ALL_EFFORT_LEVELS);
        var effort = params(id).effort();

        assertThat(effort.accepted()).as("%s: effort accepted", id).isEqualTo(!levels.isEmpty());
        assertThat(Objects.requireNonNullElse(effort.levels(), List.of()))
                .as("%s: effort levels", id)
                .containsExactlyElementsOf(levels);
    }

    private static ModelParams params(String id) {
        var params = CATALOG.get(id);
        assertThat(params).as("%s: the registry lists no params for it", id).isNotNull();
        return params;
    }
}
