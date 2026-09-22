package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything Custom Charts is configured by, in one place.
 *
 * <p>The ClickHouse credentials it runs as are the exception, and deliberately: they live in
 * {@code databaseAnalyticsReadOnlyFreeFormExtendedSql} so they keep the same shape as the account Agent Insights
 * uses. They describe a database connection, not this feature.
 */
@Data
public class CustomChartsConfig {

    /**
     * Workspaces allowed to use Custom Charts, comma-separated. Empty (the default) disables the feature
     * everywhere. Membership also routes the workspace's free-form SQL to the wider ClickHouse account, so its
     * Agent Insights queries run under that account too — intended while the allowlist is internal-only, and the
     * reason this is an allowlist rather than a plain boolean.
     *
     * <p>Held as a String because Dropwizard substitutes env vars as scalars, so a comma-separated value cannot
     * bind to a collection; {@link #getEnabledWorkspaces()} splits, strips and drops blanks.
     */
    /**
     * Nullable rather than {@code @NotNull}: Dropwizard substitutes an env var that is set but empty as an empty
     * scalar, which binds to null and would fail validation before startup — so exporting
     * {@code TOGGLE_CUSTOM_CHARTS_WORKSPACES=""}, the obvious way to turn the feature off, would stop the backend
     * booting. Null and blank both mean "no workspace allowlisted".
     */
    @JsonProperty
    private String enabledWorkspaces = "";

    /**
     * Derived: the parsed, stripped, blank-free set of allowlisted workspace ids. Parsed on first use and kept,
     * since every chart request and every health probe reads it and the configuration does not change after
     * binding. {@code volatile} because those readers are request threads, not the one that binds.
     */
    public Set<String> getEnabledWorkspaces() {
        Set<String> parsed = parsedEnabledWorkspaces;
        if (parsed == null) {
            parsed = Arrays.stream(Objects.toString(enabledWorkspaces, "").split(","))
                    .map(String::strip)
                    .filter(workspaceId -> !workspaceId.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
            parsedEnabledWorkspaces = parsed;
        }
        return parsed;
    }

    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient volatile Set<String> parsedEnabledWorkspaces;

    /**
     * Beyond this many distinct ids in one result, name enrichment resolves none and every row keeps its raw id,
     * rather than labelling some rows and not others. Production p99 is 2 datasets and 11 projects per workspace
     * against a worst case of 4,120, so the default guards a pathological result set rather than limiting anyone.
     */
    @JsonProperty
    private @Min(1) @Max(50_000) int maxNameLookupIds = 5_000;
}
