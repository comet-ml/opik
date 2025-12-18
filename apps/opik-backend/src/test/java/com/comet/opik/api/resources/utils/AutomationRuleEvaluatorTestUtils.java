package com.comet.opik.api.resources.utils;

import com.comet.opik.api.evaluators.ProjectReference;
import lombok.experimental.UtilityClass;

import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Utility methods for automation rule evaluator tests.
 */
@UtilityClass
public class AutomationRuleEvaluatorTestUtils {

    /**
     * Helper method to create SortedSet of ProjectReference for tests.
     * Project names are generated as "project-{uuid}" for testing purposes.
     * Uses natural ordering (by project name) from ProjectReference.compareTo().
     *
     * @param projectIds Set of project IDs to convert
     * @return SortedSet of ProjectReference objects sorted by project name
     */
    public static SortedSet<ProjectReference> toProjects(Set<UUID> projectIds) {
        return projectIds.stream()
                .map(id -> new ProjectReference(id, "project-" + id))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Helper method to get the primary (alphabetically first) project ID from a set of projects.
     * This matches the backend behavior where the legacy projectId field is set to the first
     * project in the alphabetically sorted set.
     *
     * @param projects SortedSet of ProjectReference objects
     * @return The project ID of the alphabetically first project, or null if the set is empty or null
     */
    public static UUID getPrimaryProjectId(SortedSet<ProjectReference> projects) {
        if (projects == null || projects.isEmpty()) {
            return null;
        }
        return projects.stream()
                .map(ProjectReference::projectId)
                .findFirst()
                .orElse(null);
    }
}
