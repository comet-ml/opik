package com.comet.opik.infrastructure.db.healthchecks;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.Records;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.HashMap;
import java.util.Map;

import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.CLICKHOUSE_HEALTH_CHECK_TIMEOUT;

/**
 * Asserts that {@code databaseAnalyticsDataModel.tracesDistributedWrapEnabled} agrees with the actual {@code traces}
 * topology in ClickHouse, so a flag↔topology mismatch is caught at readiness instead of as errors on the first
 * mutation.
 *
 * <p>Post-cutover {@code traces} is a {@code Distributed} table, which supports {@code SELECT}/{@code INSERT} but not
 * mutations, so {@code TraceDAO} routes deletes at {@code traces_local} only while the flag is on (OPIK-7455). The two
 * mismatch directions both break trace deletion, each in its own way, and neither is visible until a delete runs:
 * flag off over a wrapped {@code traces} fails with {@code BAD_ARGUMENTS} (36) / {@code NOT_IMPLEMENTED} (48), flag on
 * over an unwrapped one with {@code UNKNOWN_TABLE} (60). Since the answer depends on each environment's config plus its
 * live database, this is inherently a runtime check and cannot be a CI guard.
 *
 * <p>One {@code system.tables} lookup covers both tables and both directions:
 * <ul>
 *     <li>flag on → {@code traces} must be {@code Distributed} <b>and</b> {@code traces_local} must exist;</li>
 *     <li>flag off → {@code traces} must be a {@code (Replicated)MergeTree}, i.e. not {@code Distributed}.</li>
 * </ul>
 *
 * <p>The flag stays the source of truth: a mismatch is reported, never repaired, and nothing here influences routing.
 * The probe is not toggle-gated — flag off over a {@code MergeTree} {@code traces} is the default everywhere
 * (OSS Docker, self-hosted, pre-cutover SaaS), so the assertion holds for every install as shipped and only a genuine
 * misconfiguration trips it. Being a readiness check, it is re-evaluated continuously and clears itself once the
 * operator brings the two sides back in step, which is what makes the lockstep cutover transition (apply the wrap, flip
 * the flag, restart) tolerable rather than a deadlock.
 */
@Singleton
public class ClickHouseTracesTopologyHealthCheck extends AbstractClickHouseHealthCheck {

    private static final String NAME = "clickhouse-traces-topology";

    private static final String TRACES_TABLE = "traces";
    private static final String TRACES_LOCAL_TABLE = "traces_local";

    private static final String DISTRIBUTED_ENGINE = "Distributed";
    private static final String MERGE_TREE_ENGINE_SUFFIX = "MergeTree";

    private static final String FLAG = "databaseAnalyticsDataModel.tracesDistributedWrapEnabled";

    private static final String NAME_COLUMN = "name";
    private static final String ENGINE_COLUMN = "engine";

    /**
     * Both tables in one lookup. {@code currentDatabase()} resolves to the database the v2 client was built with
     * ({@code Client.Builder#setDefaultDatabase}), so the probe follows {@code databaseAnalytics.databaseName} without
     * interpolating it into SQL.
     */
    private static final String TOPOLOGY_QUERY = """
            SELECT name, engine FROM system.tables \
            WHERE database = currentDatabase() AND name IN ('%s', '%s')\
            """.formatted(TRACES_TABLE, TRACES_LOCAL_TABLE);

    private static final String HEALTHY_WRAPPED = "'%s' is %s over '%s', matching %s=true"
            .formatted(TRACES_TABLE, DISTRIBUTED_ENGINE, TRACES_LOCAL_TABLE, FLAG);
    private static final String HEALTHY_UNWRAPPED_TEMPLATE = "'%s' is a %%s, matching %s=false"
            .formatted(TRACES_TABLE, FLAG);

    private static final String MISSING_TRACES_TEMPLATE = ("%s=%%b, but table '%s' does not exist in the analytics "
            + "database. Trace reads and writes cannot work at all; check that the analytics migrations ran.")
            .formatted(FLAG, TRACES_TABLE);
    private static final String NOT_WRAPPED_TEMPLATE = ("%s=true routes trace mutations at '%s', but '%s' is a %%s, "
            + "not %s: the Distributed wrap has not been applied. Apply it (exchange_and_wrap.sh --wrap-only) or set "
            + "the flag back to false — otherwise trace deletes fail with UNKNOWN_TABLE (60).")
            .formatted(FLAG, TRACES_LOCAL_TABLE, TRACES_TABLE, DISTRIBUTED_ENGINE);
    private static final String MISSING_TRACES_LOCAL = ("%s=true routes trace mutations at '%s' and '%s' is %s as "
            + "expected, but table '%s' does not exist. Trace deletes fail with UNKNOWN_TABLE (60); the Distributed "
            + "wrap points at a shard table that is absent from this node.")
            .formatted(FLAG, TRACES_LOCAL_TABLE, TRACES_TABLE, DISTRIBUTED_ENGINE, TRACES_LOCAL_TABLE);
    private static final String WRAPPED_MESSAGE = ("%s=false routes trace mutations directly at '%s', but '%s' is a "
            + "%s table, which rejects mutations: the Distributed wrap has been applied. Set the flag to true and "
            + "restart — otherwise trace deletes fail with BAD_ARGUMENTS (36) / NOT_IMPLEMENTED (48).")
            .formatted(FLAG, TRACES_TABLE, TRACES_TABLE, DISTRIBUTED_ENGINE);
    private static final String NOT_MERGE_TREE_TEMPLATE = ("%s=false expects '%s' to be a (Replicated)%s that takes "
            + "mutations directly, but it is a %%s. Trace deletes are not guaranteed to work against this engine.")
            .formatted(FLAG, TRACES_TABLE, MERGE_TREE_ENGINE_SUFFIX);

    private final boolean wrapEnabled;

    @Inject
    public ClickHouseTracesTopologyHealthCheck(@NonNull Client clickHouseClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout,
            @NonNull @Config("databaseAnalyticsDataModel") DatabaseAnalyticsDataModelConfig dataModel) {
        super(clickHouseClient, healthCheckTimeout, NAME);
        this.wrapEnabled = dataModel.tracesDistributedWrapEnabled();
    }

    @Override
    protected Result check() {
        return executeProbe(clickHouseClient.queryRecords(TOPOLOGY_QUERY, newQuerySettings()), this::evaluate);
    }

    private Result evaluate(Records records) {
        var engines = readEngines(records);
        var tracesEngine = engines.get(TRACES_TABLE);

        if (tracesEngine == null) {
            return Result.unhealthy(MISSING_TRACES_TEMPLATE.formatted(wrapEnabled));
        }
        return wrapEnabled
                ? checkWrapExpected(tracesEngine, engines.containsKey(TRACES_LOCAL_TABLE))
                : checkWrapNotExpected(tracesEngine);
    }

    private static Result checkWrapExpected(String tracesEngine, boolean tracesLocalExists) {
        if (!DISTRIBUTED_ENGINE.equals(tracesEngine)) {
            return Result.unhealthy(NOT_WRAPPED_TEMPLATE.formatted(tracesEngine));
        }
        if (!tracesLocalExists) {
            return Result.unhealthy(MISSING_TRACES_LOCAL);
        }
        return Result.healthy(HEALTHY_WRAPPED);
    }

    private static Result checkWrapNotExpected(String tracesEngine) {
        if (DISTRIBUTED_ENGINE.equals(tracesEngine)) {
            return Result.unhealthy(WRAPPED_MESSAGE);
        }
        // Accepts every MergeTree family member (MergeTree, ReplicatedMergeTree, SharedMergeTree): what matters is
        // that the engine takes mutations directly, which is exactly what the family suffix marks.
        if (!tracesEngine.endsWith(MERGE_TREE_ENGINE_SUFFIX)) {
            return Result.unhealthy(NOT_MERGE_TREE_TEMPLATE.formatted(tracesEngine));
        }
        return Result.healthy(HEALTHY_UNWRAPPED_TEMPLATE.formatted(tracesEngine));
    }

    private static Map<String, String> readEngines(Records records) {
        var engines = new HashMap<String, String>();
        // Iterating (rather than Iterable.forEach) keeps the read on Records.iterator(), the one method the sibling
        // probes' tests stub — a mocked default method would silently swallow the rows.
        for (var row : records) {
            engines.put(row.getString(NAME_COLUMN), row.getString(ENGINE_COLUMN));
        }
        return engines;
    }
}
