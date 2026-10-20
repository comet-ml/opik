package com.comet.opik.infrastructure.llm.antropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire-body table lists its Anthropic model groups by hand, so that moving a rule out of {@link AnthropicModelName}
 * cannot silently move the rows with it. This keeps that list complete: a model added to the enum fails here until
 * it is placed in a group, which gives it rows. Once a rule moves to the catalog, check against the catalog instead.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WireBodyAnthropicGroupsTest {

    private static final Map<List<String>, String> EFFORT_GROUP_BY_LEVELS = Map.of(
            List.of("low", "medium", "high", "xhigh", "max"), "effort-all",
            List.of("low", "medium", "high", "max"), "effort-to-max",
            List.of("low", "medium", "high"), "effort-to-high");

    private JsonNode groups;

    @BeforeAll
    @SneakyThrows
    void loadGroups() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("wire-body/anthropic.yaml")) {
            groups = new ObjectMapper(new YAMLFactory()).readTree(is).get("models");
        }
    }

    @Test
    void everyModelIsInExactlyOneSamplingGroup() {
        var legacy = group("legacy");
        var sampling = group("sampling");
        var noSampling = group("no-sampling");

        assertThat(union(legacy, sampling, noSampling)).containsExactlyInAnyOrderElementsOf(
                AnthropicModelName.allModelIds());
        assertThat(legacy.size() + sampling.size() + noSampling.size())
                .isEqualTo(AnthropicModelName.allModelIds().size());
        assertThat(union(legacy, sampling)).containsExactlyInAnyOrderElementsOf(
                AnthropicModelName.samplingCapableModelIds());
    }

    @Test
    void everyModelIsInTheGroupOfItsEffortLevels() {
        var expected = AnthropicModelName.allModelIds().stream()
                .collect(Collectors.toMap(id -> id, this::expectedEffortGroup));
        var actual = new HashMap<String, String>();
        for (var name : List.of("effort-all", "effort-to-max", "effort-to-high", "effort-none", "legacy")) {
            group(name).forEach(id -> assertThat(actual.put(id, "legacy".equals(name) ? "effort-none" : name))
                    .as("'%s' is in more than one effort group", id)
                    .isNull());
        }

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void thinksByDefaultListsEveryModelOutsideTheThinkingOffByDefaultSet() {
        var expected = new HashSet<>(AnthropicModelName.allModelIds());
        expected.removeAll(AnthropicModelName.thinkingOffByDefaultModelIds());

        assertThat(group("thinks-by-default")).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void thinksOnlyWhenAskedListsEveryModelAnEffortTurnsThinkingOnFor() {
        var expected = AnthropicModelName.allModelIds().stream()
                .filter(AnthropicModelName::thinksOnlyWhenAsked)
                .toList();

        assertThat(group("thinks-only-when-asked")).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void registryOnlyModelsAreNotInTheEnum() {
        assertThat(group("registry-only")).isNotEmpty().doesNotContainAnyElementsOf(AnthropicModelName.allModelIds());
    }

    private String expectedEffortGroup(String id) {
        var levels = AnthropicModelName.effortLevels(id).orElse(null);
        if (levels == null) {
            return "no effort row";
        }
        return levels.isEmpty() ? "effort-none" : EFFORT_GROUP_BY_LEVELS.getOrDefault(levels, "levels " + levels);
    }

    private Set<String> group(String name) {
        var ids = new HashSet<String>();
        groups.path(name).forEach(id -> assertThat(ids.add(id.asText()))
                .as("'%s' is listed twice in group '%s'", id.asText(), name)
                .isTrue());
        return ids;
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        var result = new HashSet<String>();
        for (var set : sets) {
            result.addAll(set);
        }
        return result;
    }
}
