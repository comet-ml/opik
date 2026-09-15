package com.comet.opik.utils;

import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Supported grammar for an automation rule's variable mappings — the {@code output.choices[0].message}
 * strings that {@code OnlineScoringEngine} turns into a JsonPath and reads out of a trace's
 * input/output/metadata.
 * <p>
 * Everything JsonPath accepts is allowed except the two constructs whose cost is unbounded in the
 * section's size:
 * <ul>
 *   <li><b>recursive descent</b> ({@code ..}) — walks the whole subtree, and chained descents multiply:
 *       measured at ~40x the cost of a single descent, ~2.4s on a 54 MB section.</li>
 *   <li><b>filter predicates</b> ({@code [?(...)]}) — evaluated against every node the descent reaches.</li>
 * </ul>
 * Indexed access ({@code [0]}) and single-level wildcards ({@code [*]}) stay supported: both are bounded
 * by one level's child count, and rules in the field use them.
 * <p>
 * Scoring runs on a scheduler shared by every workspace on the pod, so an expression whose cost scales
 * with the trace is not merely the rule author's own problem.
 */
@UtilityClass
public class VariablePathUtils {

    private static final String RECURSIVE_DESCENT = "..";
    private static final String FILTER_PREDICATE = "[?(";

    /**
     * Prefixes that make a mapping value a path into the entity rather than a literal replacement.
     * Mirrors {@code OnlineScoringEngine.TraceSection}; a value that matches none of these is a literal
     * and is never handed to JsonPath, so it is not constrained.
     */
    private static final List<String> SECTION_PREFIXES = List.of("input.", "output.", "metadata.");

    public static boolean isEntityPath(String mappingValue) {
        return mappingValue != null && SECTION_PREFIXES.stream().anyMatch(mappingValue::startsWith);
    }

    /**
     * @return the unsupported construct found in the mapping value, or empty when it is supported
     *         (including when it is a literal rather than a path).
     */
    public static Optional<String> findUnsupportedConstruct(String mappingValue) {
        if (!isEntityPath(mappingValue)) {
            return Optional.empty();
        }
        if (mappingValue.contains(RECURSIVE_DESCENT)) {
            return Optional.of(RECURSIVE_DESCENT);
        }
        if (mappingValue.contains(FILTER_PREDICATE)) {
            return Optional.of(FILTER_PREDICATE);
        }
        return Optional.empty();
    }

    /**
     * Same check against an already-built JsonPath (the {@code $.…} form), for the extraction path. Rules
     * stored before this validation existed still reach the engine, so the guard has to hold there too.
     */
    public static Optional<String> findUnsupportedConstructInJsonPath(String jsonPath) {
        if (jsonPath == null) {
            return Optional.empty();
        }
        if (jsonPath.contains(RECURSIVE_DESCENT)) {
            return Optional.of(RECURSIVE_DESCENT);
        }
        if (jsonPath.contains(FILTER_PREDICATE)) {
            return Optional.of(FILTER_PREDICATE);
        }
        return Optional.empty();
    }

    /**
     * @return a message naming every offending variable, or empty when all mappings are supported.
     */
    public static Optional<String> validate(Map<String, String> variables) {
        if (variables == null) {
            return Optional.empty();
        }
        var offenders = variables.entrySet().stream()
                .flatMap(entry -> findUnsupportedConstruct(entry.getValue())
                        .map(construct -> "'%s' (%s uses '%s')"
                                .formatted(entry.getKey(), entry.getValue(), construct))
                        .stream())
                .toList();
        return offenders.isEmpty()
                ? Optional.empty()
                : Optional.of(("unsupported path construct in variable mapping: %s. Recursive descent ('..') and "
                        + "filter predicates ('[?(') are not supported because their cost grows with the size of "
                        + "the trace; use an explicit path or an index instead")
                        .formatted(String.join(", ", offenders)));
    }
}
