package com.comet.opik.infrastructure.redaction;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.Trace;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Json Node Redactor Test")
class JsonNodeRedactorTest {

    /** Matches the shape of a prompt commit, so the tree below is rewritten wherever it is not exempt. */
    private static final RedactionRules RULES = new RedactionRules(List.of(
            RedactionRule.of("\\b[0-9a-f]{8}\\b", "[HEX]")));

    private static String redact(String json, Class<?> itemType) {
        return JsonNodeRedactor.redact(JsonUtils.getJsonNodeFromString(json), RULES, itemType).toString();
    }

    @Test
    @DisplayName("an exempt property at the item's own level survives")
    void anExemptPropertyAtTheItemsOwnLevelSurvives() {
        var redacted = redact("{\"thread_id\":\"a1b2c3d4\",\"name\":\"a1b2c3d4\"}", Trace.class);

        assertThat(redacted).contains("\"thread_id\":\"a1b2c3d4\"");
        // Trace.name is free text the caller writes per call, so it is not exempt - asserted so this test says
        // the exemption is per property and not "the top level is skipped".
        assertThat(redacted).contains("\"name\":\"[HEX]\"");
    }

    @Test
    @DisplayName("a caller-chosen map key that happens to match an exempt name is still redacted")
    void aCallerChosenMapKeyMatchingAnExemptNameIsStillRedacted() {
        // The property this walk cannot distinguish from a declared one, and the reason exemptions stop at the
        // item's own level: metadata is caller content, so an entry named thread_id or id must not buy itself an
        // exemption. Under-exempting is recoverable; this would be a leak.
        var redacted = redact("{\"metadata\":{\"thread_id\":\"a1b2c3d4\",\"id\":\"a1b2c3d4\"}}", Trace.class);

        assertThat(redacted).doesNotContain("a1b2c3d4");
        assertThat(redacted).contains("[HEX]");
    }

    @Test
    @DisplayName("a nested DTO's exempt property is rewritten, which the paged path does not do")
    void aNestedDtosExemptPropertyIsRewritten() {
        // A known divergence between the two representations of one experiment, pinned rather than left
        // undescribed: BeanSerializerModifier.changeProperties runs for every bean in the graph, so the paged
        // read exempts PromptVersionLink.commit while the stream rewrites it.
        //
        // Closing it needs per-level type information the walk does not have, and approximating it by applying
        // the names at every depth is the leak asserted above. The follow-up that masks by field name instead
        // of exempting by it removes the exemption list altogether, and this assertion is what will flip when
        // it does.
        var redacted = redact("{\"id\":\"a1b2c3d4\",\"prompt_versions\":[{\"commit\":\"a1b2c3d4\"}]}",
                Experiment.class);

        assertThat(redacted).contains("\"id\":\"a1b2c3d4\"");
        assertThat(redacted).contains("\"commit\":\"[HEX]\"");
    }
}
