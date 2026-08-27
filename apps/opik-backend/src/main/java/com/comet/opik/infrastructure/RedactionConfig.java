package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redaction.FieldMasker;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Read-time masking of trace and span content.
 * <p>
 * Content is persisted exactly as the SDK sends it; what a given caller is allowed to read is decided separately,
 * on the way out, from the workspace permissions the platform resolved during authentication. With
 * {@code enabled} false nothing is masked and every response is byte-identical to before this feature existed.
 * <p>
 * Configuration is deployment-level rather than per workspace: the installations that need this run dedicated and
 * single-tenant. Per-workspace configuration can be layered on later without changing the mechanism.
 */
@Data
public class RedactionConfig {

    @JsonProperty
    private boolean enabled;

    /**
     * Leaf field names whose values are masked, matched at any depth in any content column.
     * <p>
     * Named fields rather than paths because the same field sits at different depths depending on the
     * integration that wrote the trace. See {@link FieldMasker} for why matching by name is both sufficient and
     * the safe direction to fail in.
     */
    @Valid @JsonProperty
    @NotNull private List<@NotBlank String> maskFields = List.of();

    /** Written in place of a masked value. One token for everything: the form is not part of the API contract. */
    @JsonProperty
    @NotBlank private String replacement = "[REDACTED]";

    /**
     * Enabled with nothing to mask would leave every response exactly as stored while the deployment believes its
     * content is protected — the failure mode a privacy control can least afford, because nothing about it is
     * visible from the outside. Startup fails instead.
     */
    @JsonIgnore
    @AssertTrue(message = "redaction.maskFields must not be empty when redaction.enabled=true") public boolean isConfiguredWhenEnabled() {
        return !enabled || !maskFields.isEmpty();
    }

    public FieldMasker compile() {
        return new FieldMasker(Set.copyOf(maskFields), replacement);
    }
}
