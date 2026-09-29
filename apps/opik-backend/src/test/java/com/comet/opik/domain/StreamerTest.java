package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import com.comet.opik.infrastructure.RedactionConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.redaction.JsonNodeRedactor;
import com.comet.opik.infrastructure.redaction.RedactionRule;
import com.comet.opik.infrastructure.redaction.RedactionRules;
import com.comet.opik.infrastructure.redaction.RedactionService;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.OutOfScopeException;
import com.google.inject.ProvisionException;
import jakarta.inject.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Nested
    @DisplayName("resolving the rules for a stream")
    class ResolveRules {

        private static final String RULES_JSON = "[{\"regex\":\"[\\\\w.]+@[\\\\w.]+\",\"replace\":\"[EMAIL]\"}]";

        private static RedactionService enabledService() {
            var config = new RedactionConfig();
            config.setEnabled(true);
            config.setRules(RULES_JSON);
            return new RedactionService(config);
        }

        private static Streamer streamerWhoseContextThrows(RedactionService service, RuntimeException failure) {
            Provider<RequestContext> provider = () -> {
                throw failure;
            };
            return new Streamer(service, provider);
        }

        /** What Guice actually raises for a request-scoped binding read off the request thread. */
        private static ProvisionException outsideRequestScope() {
            return new ProvisionException("Unable to provision", new OutOfScopeException("Cannot access scoped"));
        }

        @Test
        @DisplayName("a missing request scope falls back to the unknown-caller policy")
        void aMissingRequestScopeFallsBackToTheUnknownCallerPolicy() {
            var rules = streamerWhoseContextThrows(enabledService(), outsideRequestScope()).resolveRules();

            assertThat(rules.isEmpty()).isFalse();
            assertThat(rules.apply("mail john.doe@example.com")).isEqualTo("mail [EMAIL]");
        }

        @Test
        @DisplayName("with the feature off the fallback is never reached, so a stream costs nothing")
        void withTheFeatureOffTheFallbackIsNeverReached() {
            // The provider would throw if it were consulted at all; that it is not is the guarantee.
            var streamer = streamerWhoseContextThrows(RedactionService.disabled(), outsideRequestScope());

            assertThat(streamer.resolveRules().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a ProvisionException that is not a missing scope propagates instead of masking a defect")
        void aProvisionExceptionThatIsNotAMissingScopePropagates() {
            // The reason the catch inspects the cause: a provider bug or a fault in isRedactResponse() answered
            // with the unknown-caller policy would come back as a successful redacted stream, hiding it.
            var failure = new ProvisionException("Unable to provision", new IllegalStateException("provider bug"));

            assertThatThrownBy(() -> streamerWhoseContextThrows(enabledService(), failure).resolveRules())
                    .isSameAs(failure);
        }
    }
}
