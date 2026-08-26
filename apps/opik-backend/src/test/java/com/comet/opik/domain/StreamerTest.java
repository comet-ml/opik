package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.infrastructure.redaction.JsonNodeRedactor;
import com.comet.opik.infrastructure.redaction.RedactionRule;
import com.comet.opik.infrastructure.redaction.RedactionRules;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Streamer")
class StreamerTest {

    private static final String STORED = "{\"prompt\":\"refund for john.doe@example.com\"}";

    private static final RedactionRules RULES = new RedactionRules(List.of(
            RedactionRule.of("(?<![\\w.+-])[\\w.+-]+@[\\w-]+\\.[\\w.]+", "[EMAIL]")));

    private static Trace traceWith(com.fasterxml.jackson.databind.JsonNode input) {
        return Trace.builder()
                .id(UUID.randomUUID())
                .projectName("project")
                .name("trace")
                .input(input)
                .build();
    }

    @Test
    @DisplayName("a streamed DTO is not copied, because conversion already built a tree of its own")
    void aStreamedDtoIsNotCopied() {
        var trace = traceWith(JsonUtils.getJsonNodeFromString(STORED));

        var tree = JsonUtils.readTree(trace);

        assertThat(Streamer.copyIfShared(tree, trace)).isSameAs(tree);
    }

    @Test
    @DisplayName("an item that is itself a tree is copied, since rewriting it in place would rewrite the item")
    void anItemThatIsItselfATreeIsCopied() {
        var node = JsonUtils.getJsonNodeFromString(STORED);

        var copy = Streamer.copyIfShared(node, node);

        assertThat(copy).isNotSameAs(node).isEqualTo(node);
    }

    @Test
    @DisplayName("rewriting the streamed tree in place leaves the item it was built from untouched")
    void rewritingTheStreamedTreeLeavesTheItemUntouched() {
        // What the dropped deepCopy was guarding against. convertValue serializes the item into a TokenBuffer
        // and reads it back, so no node is shared - including the JsonNode the DTO carries, which is the one
        // that would have been rewritten under the item's feet.
        var stored = JsonUtils.getJsonNodeFromString(STORED);
        var trace = traceWith(stored);

        var tree = JsonUtils.readTree(trace);
        assertThat(tree.get("input")).isNotSameAs(stored);

        JsonNodeRedactor.redact(Streamer.copyIfShared(tree, trace), RULES, Trace.class);

        assertThat(tree.get("input").toString()).contains("[EMAIL]");
        assertThat(trace.input().toString()).isEqualTo(stored.toString()).contains("john.doe@example.com");
    }
}
