package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.NonNull;

import java.util.Comparator;
import java.util.UUID;

/**
 * Reference to a project with its ID and name.
 * Used in automation rules to represent project assignments.
 * Implements Comparable for natural ordering by project name, then by project ID.
 */
@Schema(description = "Project reference with ID and name")
public record ProjectReference(
        @NonNull @JsonProperty("project_id") @Schema(description = "Project ID") UUID projectId,

        @NonNull @JsonProperty("project_name") @Schema(description = "Project name") String projectName)
        implements
            Comparable<ProjectReference> {

    private static final Comparator<ProjectReference> COMPARATOR = Comparator
            .comparing(ProjectReference::projectName)
            .thenComparing(ProjectReference::projectId, Comparator.reverseOrder());

    @Override
    public int compareTo(ProjectReference other) {
        return COMPARATOR.compare(this, other);
    }
}
