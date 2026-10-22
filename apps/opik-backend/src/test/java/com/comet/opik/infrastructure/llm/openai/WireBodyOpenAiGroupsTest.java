package com.comet.opik.infrastructure.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI rows of the wire-body table pick their models by the registry's reasoning flag, so each group has to
 * agree with that flag in the table's own registry file ({@code wire-body/llm-models.yaml}); a row would otherwise
 * pin a reasoning model's body as a non-reasoning one. Once the rule moves to the catalog, check against the catalog.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WireBodyOpenAiGroupsTest {

    private static final Set<String> ENUM_IDS = Arrays.stream(OpenaiModelName.values())
            .map(OpenaiModelName::toString)
            .collect(Collectors.toUnmodifiableSet());

    private final Set<String> reasoningIds = new HashSet<>();
    private final Set<String> otherIds = new HashSet<>();

    @BeforeAll
    void loadRegistry() {
        var ids = new HashSet<String>();
        for (var model : read("wire-body/llm-models.yaml").get("openai")) {
            var id = model.get("id").asText();
            assertThat(ids.add(id)).as("'%s' is listed twice in the registry file", id).isTrue();
            (model.path("reasoning").asBoolean() ? reasoningIds : otherIds).add(id);
        }
    }

    @Test
    void bothRoutesListTheSameGroups() {
        var chat = read("wire-body/openai.yaml").get("models");
        var responses = read("wire-body/openai-responses.yaml").get("models");
        var groupNames = groupNames(chat);

        assertThat(groupNames(responses)).containsExactlyInAnyOrderElementsOf(groupNames);
        groupNames.forEach(name -> assertThat(group(responses, name))
                .as("group '%s'", name)
                .containsExactlyInAnyOrderElementsOf(group(chat, name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wire-body/openai.yaml", "wire-body/openai-responses.yaml"})
    void everyGroupAgreesWithTheRegistry(String rowsFile) {
        var groups = read(rowsFile).get("models");
        var reasoning = group(groups, "reasoning");
        var reasoningNoneLevel = group(groups, "reasoning-none-level");
        var nonReasoning = group(groups, "non-reasoning");
        var registryOnly = group(groups, "registry-only");
        var enumOnly = group(groups, "enum-only");

        assertThat(reasoning).doesNotContainAnyElementsOf(registryOnly).doesNotContainAnyElementsOf(reasoningNoneLevel);
        assertThat(union(union(reasoning, registryOnly), reasoningNoneLevel))
                .containsExactlyInAnyOrderElementsOf(reasoningIds);
        assertThat(nonReasoning).containsExactlyInAnyOrderElementsOf(otherIds);
        assertThat(registryOnly).isNotEmpty().doesNotContainAnyElementsOf(ENUM_IDS);
        assertThat(enumOnly).isNotEmpty()
                .isSubsetOf(ENUM_IDS)
                .doesNotContainAnyElementsOf(reasoningIds)
                .doesNotContainAnyElementsOf(otherIds);
    }

    private static Set<String> groupNames(JsonNode groups) {
        var names = new HashSet<String>();
        groups.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> group(JsonNode groups, String name) {
        var ids = new HashSet<String>();
        groups.path(name).forEach(id -> assertThat(ids.add(id.asText()))
                .as("'%s' is listed twice in group '%s'", id.asText(), name)
                .isTrue());
        return ids;
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        var result = new HashSet<>(first);
        result.addAll(second);
        return result;
    }

    @SneakyThrows
    private JsonNode read(String resource) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            return new ObjectMapper(new YAMLFactory()).readTree(is);
        }
    }
}
