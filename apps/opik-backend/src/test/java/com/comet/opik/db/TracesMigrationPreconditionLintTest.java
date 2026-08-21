package com.comet.opik.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A static lint over the shipped ClickHouse migrations: a migration that mutates {@code traces} must be
 * topology-aware.
 *
 * <p><b>Why, when the parity gates already cover this.</b> The gates are correct but expensive and indirect — they spin
 * up ClickHouse and ZooKeeper, apply 100-plus migrations twice, and report the <i>consequence</i> (a column missing from
 * the shadow) rather than the cause. This runs in milliseconds with no container and names the file and the missing
 * guard, so the common mistake is caught at the point it was made. It is a fast path in front of the gates, not a
 * replacement: it checks that a guard is present, and only the gates can check the DDL is actually right.
 *
 * <p><b>Why it starts at {@link #CUTOVER_SPLICE_POINT}.</b> Shipped migrations are append-only and never edited, so the
 * migrations that mutate {@code traces} unguarded ({@code 000091_add_id_at_to_traces},
 * {@code 000113_add_id_bloom_filter_index_to_traces}, …) must stay exactly as they are: they predate the cutover and are
 * correct for the installs that ran them. Rather than carry a grandfather list that someone would eventually append to,
 * the lint applies from the shadow-table migration onward — the point after which an install can already be
 * post-cutover, and therefore the point from which two branches become mandatory. That boundary needs no maintenance
 * and cannot be widened by accident.
 *
 * <p>The playbook this enforces is {@code apps/opik-backend/docs/traces-schema-ddl.md}.
 */
@DisplayName("Traces Migration Precondition Lint")
class TracesMigrationPreconditionLintTest {

    /** Relative to the Maven module directory ({@code apps/opik-backend}), the working directory locally and in CI. */
    private static final Path MIGRATIONS = Path
            .of("src/main/resources/liquibase/db-app-analytics/migrations");

    /**
     * The migration after which an install may already have cut over. Everything from here on must tolerate both
     * topologies; everything before it ran only pre-cutover. Matches the splice point the post-cutover gate uses.
     */
    private static final String CUTOVER_SPLICE_POINT = "000114_recreate_traces_local_v2_id_at_datetime64.sql";

    /**
     * A statement that mutates one of the physical trace tables. Reads are excluded deliberately: {@code SELECT ... FROM
     * traces} is correct on both topologies. {@code traces_local_v2} on its own is not enough to require a guard — the
     * shadow only exists pre-cutover and a shadow-only migration is inherently single-topology — so the trigger is a
     * mutation of {@code traces} or {@code traces_local}.
     */
    private static final Pattern TRACES_MUTATION = Pattern.compile("(?im)^\\s*"
            + "(?:(?:ALTER|OPTIMIZE)\\s+TABLE|DELETE\\s+FROM)\\s+"
            + "(?:\\$\\{ANALYTICS_DB_DATABASE_NAME}\\.)?(traces|traces_local)\\b");

    /** The guard the pattern in the playbook uses: a sqlCheck precondition keyed on whether traces_local exists. */
    private static final Pattern TOPOLOGY_PRECONDITION = Pattern
            .compile("(?i)--\\s*precondition-sql-check\\b.*\\btraces_local\\b");

    private static final Pattern MARK_RAN = Pattern.compile("(?i)--\\s*preconditions\\b.*\\bonFail:MARK_RAN\\b");

    @Test
    @DisplayName("a traces-mutating migration added after the cutover splice point is topology-aware")
    void tracesMutatingMigrationsAfterTheSplicePointAreTopologyAware() throws IOException {
        var migrations = migrationsAfterTheSplicePoint();

        assertThat(migrations)
                .as("""
                        the migrations directory must be readable at %s (relative to apps/opik-backend) and hold the \
                        splice point %s; if either moved, update this lint rather than dropping it\
                        """, MIGRATIONS, CUTOVER_SPLICE_POINT)
                .isNotNull();

        var offenders = new ArrayList<String>();
        for (var migration : migrations) {
            var sql = stripLineComments(Files.readString(migration));
            if (!TRACES_MUTATION.matcher(sql).find()) {
                continue;
            }

            var raw = Files.readString(migration);
            if (!TOPOLOGY_PRECONDITION.matcher(raw).find() || !MARK_RAN.matcher(raw).find()) {
                offenders.add(migration.getFileName().toString());
            }
        }

        assertThat(offenders)
                .as("""
                        a migration that mutates `traces` or `traces_local` must ship as two complementary changesets \
                        guarded on whether traces_local exists, each with `--preconditions onFail:MARK_RAN` and a \
                        `--precondition-sql-check` on system.tables, so exactly one branch runs per topology. See \
                        docs/traces-schema-ddl.md and the reference migration it links. Offending migrations: %s\
                        """, offenders)
                .isEmpty();
    }

    /**
     * Migrations strictly after the splice point, in the lexicographic order the changelog's {@code includeAll} applies
     * them. Returns {@code null} if the splice point is absent, so the caller can fail with a useful message rather than
     * silently linting nothing.
     */
    private static List<Path> migrationsAfterTheSplicePoint() throws IOException {
        if (!Files.isDirectory(MIGRATIONS)) {
            return null;
        }
        try (var files = Files.list(MIGRATIONS)) {
            var ordered = files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            var splicePoint = ordered.stream()
                    .filter(path -> path.getFileName().toString().equals(CUTOVER_SPLICE_POINT))
                    .findFirst();
            if (splicePoint.isEmpty()) {
                return null;
            }

            return ordered.subList(ordered.indexOf(splicePoint.get()) + 1, ordered.size());
        }
    }

    /**
     * Strips {@code --} line comments before looking for mutations, so a migration's prose — which routinely discusses
     * {@code ALTER TABLE traces} — cannot trip the lint. The precondition patterns are matched against the raw text
     * instead, since preconditions <i>are</i> comments.
     */
    private static String stripLineComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(Collectors.joining("\n"));
    }
}
