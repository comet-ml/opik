package com.comet.opik.infrastructure.redaction;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.SpanType;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RedactionModuleTest {

    private static final String EMAIL = "john.doe@example.com";
    private static final String PHONE = "555-123-4567";

    // Built from the configured mapper so the test exercises the same serialization the app uses.
    private final ObjectMapper mapper = JsonUtils.getMapper().copy().registerModule(new RedactionModule());

    private static RedactionRules rules() {
        return new RedactionRules(List.of(
                RedactionRule.of("(?<![\\w.+-])[\\w.+-]+@[\\w-]+\\.[\\w.]+", "[EMAIL]"),
                RedactionRule.of("\\b\\d{3}-\\d{3}-\\d{4}\\b", "[PHONE]")));
    }

    @AfterEach
    void tearDown() {
        RedactionContext.clear();
    }

    @Test
    @DisplayName("redacts strings inside a JsonNode payload, which is where trace input actually lives")
    void redactsStringsInsideJsonNodePayload() throws Exception {
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .name("refund")
                .startTime(Instant.now())
                .input(JsonUtils.getJsonNodeFromString(
                        """
                                {"prompt":"Refund for %s, call %s","attempts":3,"nested":{"cc":"%s"}}"""
                                .formatted(EMAIL, PHONE, EMAIL)))
                .build();

        RedactionContext.set(rules());
        String json = mapper.writeValueAsString(trace);

        assertThat(json).doesNotContain(EMAIL).doesNotContain(PHONE);
        assertThat(json).contains("[EMAIL]").contains("[PHONE]");
        // A number is not a string, so it is never visited — the same blind spot the SDK has.
        assertThat(json).contains("\"attempts\":3");
    }

    @Test
    @DisplayName("redacts an ordinary String property too")
    void redactsOrdinaryStringProperty() throws Exception {
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .name("contact %s".formatted(EMAIL))
                .startTime(Instant.now())
                .build();

        RedactionContext.set(rules());

        assertThat(mapper.writeValueAsString(trace)).doesNotContain(EMAIL).contains("[EMAIL]");
    }

    @Test
    @DisplayName("leaves property names alone")
    void leavesPropertyNamesAlone() throws Exception {
        RedactionContext.set(new RedactionRules(List.of(RedactionRule.of("name", "[REDACTED]"))));

        var json = mapper.writeValueAsString(Trace.builder()
                .id(UUID.randomUUID())
                .name("kept")
                .startTime(Instant.now())
                .build());

        assertThat(json).contains("\"name\"");
    }

    @Test
    @DisplayName("writes values as stored when no rules are in force")
    void writesValuesAsStoredWhenNoRules() throws Exception {
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .name("contact %s".formatted(EMAIL))
                .startTime(Instant.now())
                .input(JsonUtils.getJsonNodeFromString("""
                        {"prompt":"%s"}""".formatted(PHONE)))
                .build();

        // No RedactionContext.set(...) — this is what every request looks like with the feature off.
        var json = mapper.writeValueAsString(trace);

        assertThat(json).contains(EMAIL).contains(PHONE).doesNotContain("[EMAIL]");
    }

    @Test
    @DisplayName("a payload containing $1 or a backslash is replaced literally, not as a group reference")
    void payloadContainingReplacementSyntaxIsTreatedLiterally() throws Exception {
        RedactionContext.set(rules());

        var payload = JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(
                java.util.Map.of("template", "$1 ${name} \\1 \\\\ literal, mail " + EMAIL)));

        var written = mapper.writeValueAsString(payload);

        // The surrounding syntax has to survive verbatim; only the address is rewritten.
        assertThat(written).contains("$1").contains("${name}").contains("[EMAIL]").doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("a long unbroken token stays linear, so an unanchored rule cannot stall a response")
    void longUnbrokenTokenStaysLinear() {
        // A base64-style run is the realistic trigger: an unanchored local part can start at every offset,
        // which turns a 250 KB attachment into a quadratic scan and a multi-second response.
        var token = "QWxpY2VIb3BwZXJCYXJjbGF5c1JlY29uY2lsaWF0aW9u".repeat(6000);
        // rules() carries the anchored local part. The unanchored form takes over 100 seconds on this input,
        // because the match can start at every offset in the token; the anchor is what keeps it linear.
        RedactionContext.set(rules());

        assertThatCode(() -> {
            var start = Instant.now();
            var written = mapper.writeValueAsString(
                    JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(
                            java.util.Map.of("attachment", token))));
            var elapsed = Duration.between(start, Instant.now());

            assertThat(written).contains(token);
            assertThat(elapsed).describedAs("redacting a %d char token took %s", token.length(), elapsed)
                    .isLessThan(Duration.ofSeconds(5));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("identifiers are written by their own serializers, so no rule can rewrite them")
    void identifiersAreNotRewritten() throws Exception {
        var id = UUID.randomUUID();
        // A rule broad enough to match the hex and hyphens of a UUID, to prove ids never reach the
        // String serializer at all rather than merely happening not to match.
        RedactionContext.set(new RedactionRules(List.of(RedactionRule.of("[0-9a-f-]{8,}", "[REDACTED]"))));

        var written = mapper.writeValueAsString(Trace.builder()
                .id(id)
                .projectId(UUID.randomUUID())
                .name("refund")
                .startTime(Instant.now())
                .build());

        assertThat(written).contains(id.toString()).doesNotContain("[REDACTED]");
    }

    @Test
    @DisplayName("structural properties are exempt, but the same name inside content is not")
    void structuralPropertiesAreExemptButContentIsNot() throws Exception {
        // A rule broad enough to hit any word, so an exemption is the only thing that can spare a value.
        RedactionContext.set(new RedactionRules(List.of(RedactionRule.of("[a-z-]{4,}", "[X]"))));

        var written = mapper.writeValueAsString(Trace.builder()
                .id(UUID.randomUUID())
                .projectName("refund-project")
                .threadId("thread-alpha")
                .name("refund-trace")
                .startTime(Instant.now())
                .input(JsonUtils.getJsonNodeFromString("{\"projectName\":\"secret-content\"}"))
                .build());

        // Lookup keys survive so navigation keeps working...
        assertThat(written).contains("refund-project").contains("thread-alpha");
        // ...while an identically named key inside caller content gets no such protection.
        assertThat(written).doesNotContain("secret-content");
        // A non-exempt property is still redacted.
        assertThat(written).doesNotContain("refund-trace");
    }

    @Test
    @DisplayName("a span's model and provider are returned as stored, even when a rule would match them")
    void vendorIdentifiersAreExemptEvenWhenARuleMatches() throws Exception {
        // Exempting these is a decision, not an oversight: they name a vendor and a model, the UI filters and
        // groups by them, and redacting them would rewrite legitimate identifiers such as gpt-4o-2024-08-06
        // under a date rule. The cost is that content placed in them is returned as stored — asserted here so
        // that nobody later reads it as a leak and 'fixes' it.
        RedactionContext.set(new RedactionRules(
                List.of(RedactionRule.of("(?<![\\w.+-])[\\w.+-]+@[\\w-]+\\.[\\w.]+", "[EMAIL]"))));

        var written = mapper.writeValueAsString(Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectName("refund-project")
                .name("call for alice.brown@example.com")
                .type(SpanType.llm)
                .startTime(Instant.now())
                .model("alice.brown@example.com")
                .provider("bob.jones@example.com")
                .build());

        assertThat(written).contains("\"model\":\"alice.brown@example.com\"");
        assertThat(written).contains("\"provider\":\"bob.jones@example.com\"");

        // providers is a collection, which the modifier reaches through a separate condition to the scalar one.
        var trace = mapper.writeValueAsString(Trace.builder()
                .id(UUID.randomUUID())
                .projectName("refund-project")
                .name("trace for dave.green@example.com")
                .startTime(Instant.now())
                .providers(List.of("carol.white@example.com", "openai"))
                .build());
        assertThat(trace).contains("\"providers\":[\"carol.white@example.com\",\"openai\"]");
        assertThat(trace).contains("\"name\":\"trace for [EMAIL]\"");
        // A caller-supplied field that is not exempt is still redacted, so the exemption is scoped and not
        // a hole that swallowed the whole span.
        assertThat(written).contains("\"name\":\"call for [EMAIL]\"");
    }

    @Test
    @DisplayName("a map entry is redacted even when the caller names its key after an exempt property")
    void mapEntryIsRedactedEvenWhenKeyMatchesAnExemptProperty() throws Exception {
        RedactionContext.set(rules());

        // Several DTOs expose Map<String, String> metadata whose keys the caller chooses. Exempting by field
        // name alone would hand those callers an opt-out simply by naming a key "id" or "model".
        var written = mapper.writeValueAsString(java.util.Map.of("id", EMAIL, "model", PHONE));

        assertThat(written).doesNotContain(EMAIL).doesNotContain(PHONE)
                .contains("[EMAIL]").contains("[PHONE]");
    }

    @Test
    @DisplayName("a collection-typed structural property is exempt as a whole")
    void collectionTypedStructuralPropertyIsExempt() throws Exception {
        RedactionContext.set(new RedactionRules(List.of(RedactionRule.of("[a-z_]{4,}", "[X]"))));

        var written = mapper.writeValueAsString(Trace.TracePage.builder()
                .page(1)
                .size(1)
                .total(1)
                .content(List.of())
                .sortableBy(List.of("start_time", "last_updated_at"))
                .build());

        // Sort keys are what the UI sends straight back as query parameters; rewriting them breaks sorting.
        assertThat(written).contains("start_time").contains("last_updated_at").doesNotContain("[X]");
    }

    @Test
    @DisplayName("a name that addresses an entity is exempt, while a trace name is not")
    void nameIsExemptOnlyWhereItAddressesAnEntity() throws Exception {
        RedactionContext.set(new RedactionRules(List.of(RedactionRule.of("[a-z-]{4,}", "[X]"))));

        // Dataset names are replayed by the SDK against a get-or-create endpoint, so a rewritten one silently
        // creates a second dataset instead of failing.
        var dataset = mapper.writeValueAsString(Dataset.builder().name("customer-records").build());
        assertThat(dataset).contains("customer-records").doesNotContain("[X]");

        // A trace name is free text the caller writes per call, so it stays redacted.
        var trace = mapper.writeValueAsString(Trace.builder()
                .id(UUID.randomUUID())
                .name("refund-for-someone")
                .startTime(Instant.now())
                .build());
        assertThat(trace).doesNotContain("refund-for-someone").contains("[X]");
    }
}
