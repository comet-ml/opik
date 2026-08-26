package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redaction.RedactionRule;
import com.comet.opik.infrastructure.redaction.RedactionRules;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Read-time redaction of trace and span content.
 * <p>
 * Rules are deployment-level rather than per workspace: the installations that need this run dedicated and
 * single-tenant, and keeping them here means they compile once at startup instead of being read and cached per
 * request. Per-workspace rules can be layered on later without changing the mechanism.
 * <p>
 * They are carried as a JSON array in a single scalar so the whole set can come from one environment variable,
 * which a YAML list cannot: {@code [{"regex":"...","replace":"[EMAIL]"}]}. With {@code enabled} false nothing
 * is registered and every response is written exactly as it is stored.
 */
@Data
public class RedactionConfig {

    private static final TypeReference<List<Rule>> RULES_TYPE = new TypeReference<>() {
    };

    @JsonProperty
    private boolean enabled;

    @JsonProperty
    @NotNull private String rules = "[]";

    @Data
    public static class Rule {
        @JsonProperty
        @NotBlank private String regex;

        /** Empty is meaningful: it removes the match instead of replacing it. */
        @JsonProperty
        private String replace = "";
    }

    /**
     * Parses and compiles the rule set. Called once at startup so a malformed regex or malformed JSON stops
     * the deployment rather than silently disabling redaction at request time.
     */
    /**
     * Enabled with nothing to apply would leave every response exactly as stored while the deployment believes
     * its content is protected. Nothing about that is visible from outside: no error, no failed request. It is
     * the same reasoning that makes a malformed regex fail startup rather than silently disabling redaction, and
     * an empty rule set is the case that reasoning missed.
     */
    @JsonIgnore
    @AssertTrue(message = "redaction.rules must not be empty when redaction.enabled=true") public boolean isConfiguredWhenEnabled() {
        return !enabled || (StringUtils.isNotBlank(rules) && !compile().isEmpty());
    }

    public RedactionRules compile() {
        if (StringUtils.isBlank(rules)) {
            return RedactionRules.empty();
        }

        return new RedactionRules(JsonUtils.readValue(rules, RULES_TYPE).stream()
                .map(rule -> RedactionRule.of(rule.getRegex(), rule.getReplace()))
                .toList());
    }
}
