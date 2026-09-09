package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Argument binding for the Python code-metric path. Pure static function, so no containers.
 * <p>
 * The regression: {@code toReplacements} drops a mapping it cannot resolve and the Python path
 * spreads the result as {@code metric.score(**data)}, so a trace logged without metadata called
 * a metric declaring {@code metadata} without that argument — a TypeError that stored no score
 * while the rule kept reading Enabled.
 */
@DisplayName("OnlineScoringEngine declared-argument binding")
class OnlineScoringEngineBindDeclaredArgumentsTest {

    private static final Map<String, String> DIALOG_DEFAULT_ARGUMENTS = Map.of(
            "input", "input", "output", "output", "metadata", "metadata");

    private static Trace trace(String inputJson, String outputJson, String metadataJson) {
        var builder = Trace.builder()
                .id(UUID.randomUUID())
                .projectName("project")
                .projectId(UUID.randomUUID())
                .createdBy("user")
                .input(JsonUtils.getJsonNodeFromString(inputJson))
                .output(JsonUtils.getJsonNodeFromString(outputJson));
        if (metadataJson != null) {
            builder.metadata(JsonUtils.getJsonNodeFromString(metadataJson));
        }
        return builder.build();
    }

    @Test
    @DisplayName("a trace without metadata still binds the metadata argument, as null")
    void bindsUnresolvedArgumentAsNull() {
        var trace = trace("{\"question\":\"q\"}", "{\"answer\":\"a\"}", null);

        var replacements = OnlineScoringEngine.toReplacements(DIALOG_DEFAULT_ARGUMENTS, trace);
        var bound = OnlineScoringEngine.bindDeclaredArguments(DIALOG_DEFAULT_ARGUMENTS, replacements);

        // toReplacements' own contract is unchanged — the drop is what template rendering wants.
        assertThat(replacements).doesNotContainKey("metadata");
        // Binding is what score(**data) needs: present as a key, so the parameter is satisfied.
        assertThat(bound).containsKey("metadata");
        assertThat(bound).containsEntry("metadata", null);
        assertThat(bound.keySet()).containsExactlyInAnyOrder("input", "output", "metadata");
    }

    @Test
    @DisplayName("a resolvable argument keeps its extracted value rather than being nulled")
    void doesNotOverwriteResolvedArguments() {
        var trace = trace("{\"question\":\"q\"}", "{\"answer\":\"a\"}", "{\"env\":\"test\"}");

        var bound = OnlineScoringEngine.bindDeclaredArguments(DIALOG_DEFAULT_ARGUMENTS,
                OnlineScoringEngine.toReplacements(DIALOG_DEFAULT_ARGUMENTS, trace));

        assertThat(bound).containsEntry("metadata", "{\"env\":\"test\"}");
        assertThat(bound.values()).doesNotContainNull();
    }

    @Test
    @DisplayName("the reserved spans built-in is never bound from the arguments map")
    void leavesSpansToItsInjector() {
        // `spans` is injected as a typed list by the spans overload of toReplacements, not
        // resolved from a path. Binding it here would overwrite that with null on any rule
        // that declared it.
        var arguments = Map.of("output", "output", "spans", "spans");
        Map<String, Object> replacements = new LinkedHashMap<>();
        replacements.put("output", "{\"answer\":\"a\"}");
        replacements.put("spans", java.util.List.of());

        var bound = OnlineScoringEngine.bindDeclaredArguments(arguments, replacements);

        assertThat(bound).containsEntry("spans", java.util.List.of());
    }

    @Test
    @DisplayName("an argument the entity cannot resolve at all is still bound")
    void bindsArgumentWithNoMatchingSection() {
        var trace = trace("{\"question\":\"q\"}", "{\"answer\":\"a\"}", "{\"env\":\"test\"}");
        var arguments = Map.of("output", "output", "missing", "output.nope");

        var bound = OnlineScoringEngine.bindDeclaredArguments(arguments,
                OnlineScoringEngine.toReplacements(arguments, trace));

        assertThat(bound).containsEntry("missing", null);
    }
}
