package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Arrays;
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
    @JsonProperty
    private @NotNull String enabledWorkspaces = "";

    /** Derived: the parsed, stripped, blank-free set of allowlisted workspace ids. */
    public Set<String> getEnabledWorkspaces() {
        return Arrays.stream(enabledWorkspaces.split(","))
                .map(String::strip)
                .filter(workspaceId -> !workspaceId.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Beyond this many distinct ids in one result, name enrichment resolves none and every row keeps its raw id,
     * rather than labelling some rows and not others. Production p99 is 2 datasets and 11 projects per workspace
     * against a worst case of 4,120, so the default guards a pathological result set rather than limiting anyone.
     */
    @JsonProperty
    private @Min(1) int maxNameLookupIds = 5_000;
}
