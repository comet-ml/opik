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
}
