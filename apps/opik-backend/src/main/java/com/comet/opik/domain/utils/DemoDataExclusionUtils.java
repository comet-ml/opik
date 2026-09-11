package com.comet.opik.domain.utils;

import com.comet.opik.api.BiInformationResponse.BiInformation;
import com.comet.opik.api.UsageByWorkspaceProjectUserResponse.WorkspaceProjectUserCount;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Excludes demo-project usage from the daily BI and billing counts.
 *
 * <p>The exclusion cannot be a {@code project_id NOT IN [...]} literal in the query text. One demo project is
 * created per signup, so the set is unbounded, and a {@code Distributed} table re-serialises and re-parses the
 * query text for its shard — a large literal comes to dominate the query cost and eventually exceeds
 * {@code max_execution_time}. {@code GLOBAL NOT IN} does not help, because the literal is still in the query text.
 *
 * <p>The usage queries therefore {@code GROUP BY project_id} and the exclusion is applied here, which keeps the
 * query text constant however many demo projects exist.
 *
 * <p>Aggregating after the exclusion preserves the totals a workspace- or user-grouped query returns, because an
 * id has exactly one project. {@code project_id} is part of the sorting key of both {@code traces} and
 * {@code spans}, so two rows for one id under different projects would both survive deduplication and be summed
 * twice — what stops them existing is the write path, which rejects an upsert presenting an existing id with a
 * different project rather than moving it. The folds are insertion-ordered, so the result keeps the order the
 * query returned its rows in.
 */
@UtilityClass
public class DemoDataExclusionUtils {

    /** A previous-day count for one project — the granularity the usage queries return. */
    @Builder(toBuilder = true)
    public record WorkspaceProjectCount(@NonNull String workspaceId, @NonNull UUID projectId, long count) {
    }

    /**
     * Calculates the demo data created at timestamp by finding the maximum creation time
     * from the excluded project IDs and adding 1 minute to ensure all demo data is excluded.
     *
     * <p>Used only by the span usage queries, which still carry the exclusion in SQL. The cutoff assumes a demo set
     * created once at install time; where demo projects are created continuously it is effectively "now", so the
     * {@code OR created_at > :demo_data_created_at} branch it feeds cannot match a row in the previous-day window.
     *
     * @param excludedProjectIds map of project ID to creation timestamp
     * @return Optional containing the calculated timestamp, or empty if no projects exist
     */
    public Optional<Instant> calculateDemoDataCreatedAt(@NonNull Map<UUID, Instant> excludedProjectIds) {
        return excludedProjectIds.values()
                .stream()
                .max(Comparator.naturalOrder())
                .map(createAt -> createAt.plus(1, ChronoUnit.MINUTES));
    }

    /**
     * Drops demo projects and re-aggregates the per-project counts into one count per workspace.
     *
     * @param rows           per-project counts, in the order the query returned them
     * @param demoProjectIds ids of the demo projects to exclude; ids absent from {@code rows} are simply unused
     * @return workspace id to count, in the order the workspaces first appear in {@code rows}
     */
    public Map<String, Long> foldByWorkspace(@NonNull List<WorkspaceProjectCount> rows,
            @NonNull Set<UUID> demoProjectIds) {
        return rows.stream()
                .filter(row -> !demoProjectIds.contains(row.projectId()))
                .collect(Collectors.groupingBy(WorkspaceProjectCount::workspaceId, LinkedHashMap::new,
                        Collectors.summingLong(WorkspaceProjectCount::count)));
    }

    /**
     * Drops demo projects and re-aggregates the per-project counts into one count per workspace and user, the shape
     * the BI events expect.
     *
     * @param rows           per-project, per-user counts, in the order the query returned them
     * @param demoProjectIds ids of the demo projects to exclude; ids absent from {@code rows} are simply unused
     * @return one entry per workspace and user, in the order the pairs first appear in {@code rows}
     */
    public List<BiInformation> foldByWorkspaceAndUser(@NonNull List<WorkspaceProjectUserCount> rows,
            @NonNull Set<UUID> demoProjectIds) {
        return rows.stream()
                .filter(row -> !demoProjectIds.contains(row.projectId()))
                .collect(Collectors.groupingBy(row -> Pair.of(row.workspaceId(), row.user()), LinkedHashMap::new,
                        Collectors.summingLong(WorkspaceProjectUserCount::count)))
                .entrySet()
                .stream()
                .map(entry -> BiInformation.builder()
                        .workspaceId(entry.getKey().getLeft())
                        .user(entry.getKey().getRight())
                        .count(entry.getValue())
                        .build())
                .toList();
    }
}
