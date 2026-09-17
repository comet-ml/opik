package com.comet.opik.infrastructure.db.healthchecks;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.Records;
import io.dropwizard.util.Duration;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Asserts that a {@code databaseAnalyticsDataModel.*DistributedWrapEnabled} flag agrees with the actual topology of
 * the table whose mutations it routes, so a mismatch is caught at readiness instead of on the first delete. Traces
 * (OPIK-7773) and spans (OPIK-8376) run the same assertion over their own tables, so subclasses carry only what
 * differs: probe name, the two table names, the noun its messages read in, the flag and its value.
 *
 * <p>Post-cutover the read-facing table is {@code Distributed}, which supports {@code SELECT} and {@code INSERT} but
 * not mutations, so the DAO routes deletes at the {@code _local} shard only while the flag is on. Both mismatch
 * directions break deletion, and neither is visible until a delete runs: flag off over a wrapped table fails with
 * {@code BAD_ARGUMENTS} (36) / {@code NOT_IMPLEMENTED} (48), flag on over an unwrapped one with
 * {@code UNKNOWN_TABLE} (60). The answer depends on an environment's configuration plus its live database, so this
 * cannot be a CI guard; it belongs at readiness.
 *
 * <p>One {@code system.tables} lookup covers both tables and both directions:
 * <ul>
 *     <li>flag on → the table must be {@code Distributed} <b>and</b> its {@code _local} shard must exist;</li>
 *     <li>flag off → the table must be an engine of the {@code MergeTree} family — the suffix match accepts
 *     {@code MergeTree}, {@code ReplicatedMergeTree} and ClickHouse Cloud's {@code SharedMergeTree} alike — that is,
 *     anything taking mutations directly, and not {@code Distributed}.</li>
 * </ul>
 *
 * <p>The flag stays the source of truth: a mismatch is reported, never repaired, and nothing here influences routing.
 * The probe is not toggle-gated — flag off over a {@code MergeTree} table is the default on every install as shipped,
 * so the assertion holds universally and only a genuine misconfiguration trips it. Being a readiness check, it is
 * re-evaluated continuously and clears itself once the operator brings the two sides back in step, which is what makes
 * the lockstep cutover transition (apply the wrap, flip the flag, restart) tolerable rather than a deadlock.
 *
 * <p><b>The scope is the node that answers, deliberately.</b> {@code system.tables} is node-local and the probe does
 * not fan out with {@code clusterAllReplicas}: the fan-out needs {@code REMOTE} and {@code CLUSTER} grants the
 * application user is not guaranteed to hold, and one unreachable replica would pull every pod out of rotation over a
 * condition that breaks no delete at all. The backend reaches ClickHouse through a load-balanced service, so the probe
 * reads the same population its own {@code DELETE}s are routed to.
 *
 * <p>The accepted limit: a divergence confined to some replicas — an {@code ON CLUSTER} DDL still propagating, or one
 * that failed on a host — is seen only by the probes that land there, so it degrades into sporadic unhealthy reports
 * instead of taking the fleet dark. "Has this DDL reached every replica?" is a different question, answered by the
 * fail-loud, operator-run {@code clusterAllReplicas} gates in each cutover's {@code exchange_and_wrap.sh} and
 * {@code finalize.sh}, and by the query the self-host troubleshooting page carries for exactly that.
 */
abstract class AbstractClickHouseTopologyHealthCheck extends AbstractClickHouseHealthCheck {

    private static final String DISTRIBUTED_ENGINE = "Distributed";
    private static final String MERGE_TREE_ENGINE_SUFFIX = "MergeTree";

    private static final String NAME_COLUMN = "name";
    private static final String ENGINE_COLUMN = "engine";

    private static final String TABLE_PARAM = "table";
    private static final String LOCAL_TABLE_PARAM = "localTable";

    /**
     * Both tables in one lookup, shared by every topology probe. The table names are bound as ClickHouse query
     * parameters rather than spliced in, so the query text stays a constant and no SQL is assembled with Java string
     * operations. {@code currentDatabase()} resolves to the database the v2 client was built with
     * ({@code Client.Builder#setDefaultDatabase}), so the probe follows {@code databaseAnalytics.databaseName} without
     * naming it either.
     */
    private static final String TOPOLOGY_QUERY = """
            SELECT name, engine FROM system.tables \
            WHERE database = currentDatabase() AND name IN ({table:String}, {localTable:String})\
            """;

    private final String table;
    private final String localTable;
    private final String entity;
    private final String capitalizedEntity;
    private final String flag;
    private final boolean wrapEnabled;
    private final Map<String, Object> queryParams;

    /**
     * @param table      the read-facing table the flag describes, e.g. {@code traces}
     * @param localTable the shard table mutations are routed at while the flag is on, e.g. {@code traces_local}
     * @param entity     singular, lower-case noun for a row of {@code table} ({@code trace}, {@code span}), read into
     *                   the messages an operator sees on {@code /health-check}
     * @param flag       fully qualified configuration key being asserted, named in every message so the operator knows
     *                   which of the two sides to change
     */
    protected AbstractClickHouseTopologyHealthCheck(@NonNull Client clickHouseClient,
            @NonNull Duration healthCheckTimeout, String name, @NonNull String table, @NonNull String localTable,
            @NonNull String entity, @NonNull String flag, boolean wrapEnabled) {
        super(clickHouseClient, healthCheckTimeout, name);
        this.table = table;
        this.localTable = localTable;
        this.entity = entity;
        this.capitalizedEntity = StringUtils.capitalize(entity);
        this.flag = flag;
        this.wrapEnabled = wrapEnabled;
        this.queryParams = Map.of(TABLE_PARAM, table, LOCAL_TABLE_PARAM, localTable);
    }

    @Override
    protected Result check() {
        return executeProbe(clickHouseClient.queryRecords(TOPOLOGY_QUERY, queryParams, newQuerySettings()),
                this::evaluate);
    }

    private Result evaluate(Records records) {
        var engines = readEngines(records);
        var tableEngine = engines.get(table);
        if (tableEngine == null) {
            return Result.unhealthy(missingTableMessage());
        }
        return wrapEnabled
                ? checkWrapExpected(tableEngine, engines.get(localTable))
                : checkWrapNotExpected(tableEngine);
    }

    private Result checkWrapExpected(String tableEngine, String localTableEngine) {
        if (!DISTRIBUTED_ENGINE.equals(tableEngine)) {
            return Result.unhealthy(notWrappedMessage(tableEngine));
        }
        if (localTableEngine == null) {
            return Result.unhealthy(missingLocalTableMessage());
        }
        // Presence alone is not enough: the wrap's target is where the DAO sends its DELETEs, so a same-named View,
        // Log or nested Distributed there fails mutations exactly like an absent table. The engine is already in the
        // row this probe reads, so holding it to the mutation-capable family costs nothing.
        return isMergeTreeFamily(localTableEngine)
                ? Result.healthy(healthyWrappedMessage())
                : Result.unhealthy(localTableNotMergeTreeMessage(localTableEngine));
    }

    private Result checkWrapNotExpected(String tableEngine) {
        if (DISTRIBUTED_ENGINE.equals(tableEngine)) {
            return Result.unhealthy(wrappedMessage());
        }
        return isMergeTreeFamily(tableEngine)
                ? Result.healthy(healthyUnwrappedMessage(tableEngine))
                : Result.unhealthy(notMergeTreeMessage(tableEngine));
    }

    /**
     * Accepts every MergeTree family member — {@code MergeTree}, {@code ReplicatedReplacingMergeTree},
     * {@code SharedMergeTree} and the rest. What matters is whether the engine takes mutations directly, and the family
     * suffix is exactly what marks that; enumerating the variants would only go stale.
     */
    private boolean isMergeTreeFamily(String engine) {
        return engine.endsWith(MERGE_TREE_ENGINE_SUFFIX);
    }

    /**
     * Collected eagerly because {@link Records} is a single-pass cursor over the response, while both table names are
     * looked up independently — and, on the healthy-unwrapped path, one of them not at all.
     */
    private Map<String, String> readEngines(Records records) {
        return StreamSupport.stream(records.spliterator(), false)
                .collect(Collectors.toMap(row -> row.getString(NAME_COLUMN), row -> row.getString(ENGINE_COLUMN)));
    }

    private String healthyWrappedMessage() {
        return "'%s' is %s over '%s', matching %s=true".formatted(table, DISTRIBUTED_ENGINE, localTable, flag);
    }

    private String healthyUnwrappedMessage(String tableEngine) {
        return "'%s' is a %s, matching %s=false".formatted(table, tableEngine, flag);
    }

    private String missingTableMessage() {
        return """
                %s=%b, but table '%s' does not exist in the analytics database. %s reads and writes cannot work at \
                all; check that the analytics migrations ran.\
                """.formatted(flag, wrapEnabled, table, capitalizedEntity);
    }

    /**
     * Deliberately names no error code. This branch fires whenever the flag is on and the table is not
     * {@code Distributed}, and the consequence depends on whether the shard table happens to exist: absent, deletes
     * fail loudly with {@code UNKNOWN_TABLE} (60); present but stale — the state a rollback leaves, having promoted the
     * original table back while the old shard lingers — the delete succeeds against the wrong table and the live rows
     * are never touched. Naming only the loud outcome would understate the quiet one, which is worse.
     */
    private String notWrappedMessage(String tableEngine) {
        return """
                %s=true routes %s mutations at '%s', but '%s' is a %s, not Distributed: the Distributed wrap has not \
                been applied (or has been rolled back). Apply it (exchange_and_wrap.sh --wrap-only) or set the flag \
                back to false — otherwise %s deletes either fail with UNKNOWN_TABLE (60) when '%s' is absent, or \
                silently delete from a stale '%s' while the live rows in '%s' are left untouched.\
                """.formatted(flag, entity, localTable, table, tableEngine, entity, localTable, localTable, table);
    }

    private String missingLocalTableMessage() {
        return """
                %s=true routes %s mutations at '%s' and '%s' is Distributed as expected, but table '%s' does not \
                exist. %s deletes fail with UNKNOWN_TABLE (60); the Distributed wrap points at a shard table that is \
                absent from this node.\
                """.formatted(flag, entity, localTable, table, localTable, capitalizedEntity);
    }

    private String localTableNotMergeTreeMessage(String localTableEngine) {
        return """
                %s=true routes %s mutations at '%s', which exists but is a %s rather than a (Replicated)MergeTree. %s \
                deletes cannot run against that engine, so the wrap is pointing at the wrong table.\
                """.formatted(flag, entity, localTable, localTableEngine, capitalizedEntity);
    }

    private String wrappedMessage() {
        return """
                %s=false routes %s mutations directly at '%s', but '%s' is a Distributed table, which rejects \
                mutations: the Distributed wrap has been applied. Set the flag to true and restart — otherwise %s \
                deletes fail with BAD_ARGUMENTS (36) / NOT_IMPLEMENTED (48).\
                """.formatted(flag, entity, table, table, entity);
    }

    private String notMergeTreeMessage(String tableEngine) {
        return """
                %s=false expects '%s' to be a (Replicated)MergeTree that takes mutations directly, but it is a %s. %s \
                deletes are not guaranteed to work against this engine.\
                """.formatted(flag, table, tableEngine, capitalizedEntity);
    }
}
