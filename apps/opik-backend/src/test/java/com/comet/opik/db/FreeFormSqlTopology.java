package com.comet.opik.db;

import com.clickhouse.client.api.Client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;

/**
 * The table topologies the free-form SQL suites run on, as a parameter rather than a subclass: the migrated schema
 * as is, and traces and spans wrapped as Distributed over their local tables, the production topology, reached the
 * way the cutover reaches it: the runbook's backfill into the local table, then its exchange and wrap.
 */
public enum FreeFormSqlTopology {

    MIGRATED {
        @Override
        public void apply(Client admin) {
        }
    },
    DISTRIBUTED {
        @Override
        public void apply(Client admin) {
            for (String table : List.of("traces", "spans")) {
                // The runbook's own backfill first: the exchange swaps in the copy, so live data has to be in it.
                admin.queryAll(backfill(table));
                run(admin, """
                        EXCHANGE TABLES %1$s.%2$s AND %1$s.%2$s_local_v2 ON CLUSTER '{cluster}'
                        """, table);
                run(admin, """
                        RENAME TABLE %1$s.%2$s_local_v2 TO %1$s.%2$s_pre_cutover_backup ON CLUSTER '{cluster}'
                        """, table);
                run(admin, """
                        CREATE TABLE %1$s.%2$s_dist ON CLUSTER '{cluster}' AS %1$s.%2$s
                        ENGINE = Distributed('{cluster}', '%1$s', '%2$s_local', sipHash64(project_id))
                        """, table);
                run(admin, """
                        RENAME TABLE %1$s.%2$s TO %1$s.%2$s_local, %1$s.%2$s_dist TO %1$s.%2$s ON CLUSTER '{cluster}'
                        """, table);
            }
        }
    };

    /** A backfill script placeholder, {@code ${NAME}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z_]+)}");

    /** Brings the migrated schema, data included, to this topology. */
    public abstract void apply(Client admin);

    /** The cutover's backfill of {@code table}, filled the way backfill.sh fills it, over every row in one window. */
    private static String backfill(String table) {
        var path = Path
                .of("data-migrations/%1$s-local-v2-cutover/scripts/db-app-analytics/000001_backfill_%1$s_local_v2.sql"
                        .formatted(table));
        var values = Map.of("ANALYTICS_DB_DATABASE_NAME", DATABASE_NAME, "WINDOW_LO", "1970-01-01 00:00:00",
                "WINDOW_HI", "2262-01-01 00:00:00", "MAX_INSERT_BLOCK_SIZE", "1048576",
                "MAX_PARTITIONS_PER_INSERT_BLOCK", "10000", "MAX_INSERT_THREADS", "1",
                "MIN_INSERT_BLOCK_SIZE_BYTES", "268435456");
        try {
            // Full-line comments name placeholders the driver never fills, so they go before substituting.
            String script = Files.readAllLines(path).stream().filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            String sql = PLACEHOLDER.matcher(script).replaceAll(match -> Matcher.quoteReplacement(
                    Objects.requireNonNull(values.get(match.group(1)), "unfilled placeholder " + match.group())));
            return sql.strip().replaceAll(";$", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void run(Client admin, String sql, String table) {
        admin.queryAll(sql.formatted(DATABASE_NAME, table));
    }
}
