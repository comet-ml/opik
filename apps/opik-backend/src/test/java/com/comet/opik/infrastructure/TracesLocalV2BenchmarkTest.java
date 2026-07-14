package com.comet.opik.infrastructure;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-column compression benchmark and DateTime64 precision check for the {@code traces_local_v2} table (OPIK-6899).
 *
 * <p>Two complementary checks. {@link #everyTracesLocalV2ColumnUsesItsIntendedCodec()} reads {@code system.columns} for
 * the live {@code traces_local_v2} and pins each of its 32 columns to the codec its type warrants — a drift guard that
 * fails if a column's codec changes or a new column lands unclassified. The remaining tests store one identical
 * synthetic slice under the competing codecs as side-by-side columns of a scratch table and read the compressed sizes
 * back from {@code system.columns}, so a size difference is attributable to the codec alone. They also confirm the
 * {@code DateTime64(9) -> DateTime64(6)} conversion truncates rather than rounds, record long-text decompression cost,
 * and measure the whole-row storage of the old vs new table format.
 *
 * <p>The synthetic slice matches the production data shapes and the {@code (workspace_id, project_id, id)} sort order,
 * since both drive compression:
 * <ul>
 *   <li>{@code id} is a unique UUIDv7 per row, monotonic over ~a week — the sorted order a real part stores it in;</li>
 *   <li>{@code project_id} is a UUIDv7 clustered into ~200 runs (second sort key), {@code workspace_id} a UUIDv4
 *       clustered into ~20 runs (first sort key, with a rare short-text outlier as in production);</li>
 *   <li>{@code id_at} is the second-precision event time derived from the id, monotonic within each project run;</li>
 *   <li>of the five Delta-coded timestamps, only {@code start_time}, {@code created_at} and {@code id_at} are monotonic
 *       in storage order — verified against {@code TraceDAO}'s upsert coalesce, which preserves {@code start_time} /
 *       {@code created_at} to the creation moment; {@code end_time} (start + duration) and {@code last_updated_at} (the
 *       ReplacingMergeTree version column's last-write value) are not, and are modeled as scrambled.</li>
 * </ul>
 * Rows are inserted in {@code (workspace_id, project_id, id)} order, so the clustered keys form the same runs a real
 * part has; the monotonic three are idealized as globally monotonic rather than resetting at each project-run boundary,
 * which is marginally optimistic for Delta but does not change the codec ordering.
 * Data is deterministic (hash-derived, single-threaded insert), so runs are byte-identical and the test doubles as a
 * regression guard. Absolute byte counts hold only for this slice; the validated conclusions are the relative codec
 * orderings. Runs directly against ClickHouse via {@link TransactionTemplateAsync} (no Dropwizard app), mirroring
 * {@code TracesLocalV2TableTest}.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracesLocalV2BenchmarkTest {

    private static final String TABLE = "opik_6899_benchmark";
    private static final int ROW_COUNT = 20_000;

    /**
     * A 23-word vocabulary joined into 60-word sentences: repetitive natural-language-shaped text, the profile that
     * separates ZSTD(3) from ZSTD(1) and LZ4 the way real chat input/output does. Inline so the harness needs no
     * fixture files. Injected verbatim via {@code replace()}, so it carries a single {@code %} modulo operator like the
     * rest of the INSERT template.
     */
    private static final String SENTENCE_EXPR = """
            arrayStringConcat(
                arrayMap(i -> ['the','user','asked','the','assistant','about','weather','and','requested','a',
                               'concise','summary','of','the','document','with','several','key','points','listed',
                               'in','order','please'][(cityHash64(number, i) % 23) + 1], range(60)),
                ' ')
            """;

    /**
     * The intended codec for every {@code traces_local_v2} column, keyed by column name. This is the canonical record
     * of the per-field decisions the benchmarks below justify; {@link #everyTracesLocalV2ColumnUsesItsIntendedCodec()}
     * asserts the live DDL matches it, column for column. It tracks the codecs currently shipped in migration
     * {@code 000101}; the six benchmark-driven refinements land as a later {@code ALTER} migration, at which point the
     * affected entries here move in lockstep — the pin test fails loudly until they do.
     */
    private static final Map<String, ExpectedCodec> TRACES_LOCAL_V2_CODECS = Map.ofEntries(
            Map.entry("id", ExpectedCodec.ZSTD1),
            Map.entry("workspace_id", ExpectedCodec.ZSTD1),
            Map.entry("project_id", ExpectedCodec.ZSTD1),
            Map.entry("name", ExpectedCodec.ZSTD1),
            Map.entry("start_time", ExpectedCodec.DELTA_ZSTD1),
            Map.entry("end_time", ExpectedCodec.DELTA_ZSTD1),
            Map.entry("input", ExpectedCodec.ZSTD3),
            Map.entry("output", ExpectedCodec.ZSTD3),
            Map.entry("metadata", ExpectedCodec.ZSTD3),
            Map.entry("tags", ExpectedCodec.ZSTD1),
            Map.entry("created_at", ExpectedCodec.DELTA_ZSTD1),
            Map.entry("last_updated_at", ExpectedCodec.DELTA_ZSTD1),
            Map.entry("created_by", ExpectedCodec.ZSTD1),
            Map.entry("last_updated_by", ExpectedCodec.ZSTD1),
            Map.entry("error_info", ExpectedCodec.ZSTD1),
            Map.entry("thread_id", ExpectedCodec.ZSTD1),
            Map.entry("visibility_mode", ExpectedCodec.SERVER_DEFAULT),
            Map.entry("truncation_threshold", ExpectedCodec.ZSTD1),
            Map.entry("input_slim", ExpectedCodec.ZSTD3),
            Map.entry("output_slim", ExpectedCodec.ZSTD3),
            Map.entry("ttft", ExpectedCodec.ZSTD1),
            Map.entry("source", ExpectedCodec.SERVER_DEFAULT),
            Map.entry("environment", ExpectedCodec.SERVER_DEFAULT),
            Map.entry("is_deleted", ExpectedCodec.SERVER_DEFAULT),
            Map.entry("input_length", ExpectedCodec.T64_ZSTD1),
            Map.entry("output_length", ExpectedCodec.T64_ZSTD1),
            Map.entry("metadata_length", ExpectedCodec.T64_ZSTD1),
            Map.entry("truncated_input", ExpectedCodec.ZSTD3),
            Map.entry("truncated_output", ExpectedCodec.ZSTD3),
            Map.entry("output_keys", ExpectedCodec.ZSTD1),
            Map.entry("duration", ExpectedCodec.ZSTD1),
            Map.entry("id_at", ExpectedCodec.DELTA_ZSTD1));

    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);

    private final TransactionTemplateAsync transactionTemplateAsync;

    /**
     * Per-column compressed/uncompressed sizes of the scratch benchmark table, keyed by column name in DDL order.
     * Populated once in {@link #loadBenchmarkSlice()} — a {@code @BeforeAll} that completes before any test — and only
     * read afterward, so it needs no synchronization even if the suite were run in parallel (which it is not; there is
     * no parallel-execution config). LinkedHashMap so {@link #logReport()} prints columns in declaration order.
     */
    private final Map<String, ColumnStat> columnStats = new LinkedHashMap<>();

    {
        Startables.deepStart(clickHouseContainer, zookeeperContainer).join();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        transactionTemplateAsync = TransactionTemplateAsync.create(databaseAnalyticsFactory.build());
    }

    @BeforeAll
    void loadBenchmarkSlice() {
        execute("DROP TABLE IF EXISTS %s.%s".formatted(DATABASE_NAME, TABLE));

        // One scratch column per (column type, codec) candidate. Every family holds identical data across its variants,
        // so a size difference in system.columns is attributable to the codec alone.
        execute("""
                CREATE TABLE %s.%s
                (
                    id_lz4            FixedString(36)              CODEC(LZ4),
                    id_zstd1          FixedString(36)              CODEC(ZSTD(1)),
                    id_zstd3          FixedString(36)              CODEC(ZSTD(3)),
                    proj_lz4          FixedString(36)              CODEC(LZ4),
                    proj_zstd1        FixedString(36)              CODEC(ZSTD(1)),
                    proj_zstd3        FixedString(36)              CODEC(ZSTD(3)),
                    ws_lz4            String                       CODEC(LZ4),
                    ws_zstd1          String                       CODEC(ZSTD(1)),
                    name_lz4          String                       CODEC(LZ4),
                    name_zstd1        String                       CODEC(ZSTD(1)),
                    name_zstd3        String                       CODEC(ZSTD(3)),
                    text_lz4          String                       CODEC(LZ4),
                    text_zstd1        String                       CODEC(ZSTD(1)),
                    text_zstd3        String                       CODEC(ZSTD(3)),
                    ts6_lz4           DateTime64(6, 'UTC')         CODEC(LZ4),
                    ts6_zstd1         DateTime64(6, 'UTC')         CODEC(ZSTD(1)),
                    ts6_delta_zstd1   DateTime64(6, 'UTC')         CODEC(Delta, ZSTD(1)),
                    ts6_dd_zstd1      DateTime64(6, 'UTC')         CODEC(DoubleDelta, ZSTD(1)),
                    ts9_delta_zstd1   DateTime64(9, 'UTC')         CODEC(Delta, ZSTD(1)),
                    idat_lz4          DateTime('UTC')              CODEC(LZ4),
                    idat_zstd1        DateTime('UTC')              CODEC(ZSTD(1)),
                    idat_delta_zstd1  DateTime('UTC')              CODEC(Delta, ZSTD(1)),
                    idat_dd_zstd1     DateTime('UTC')              CODEC(DoubleDelta, ZSTD(1)),
                    cnt_zstd1         UInt64                       CODEC(ZSTD(1)),
                    cnt_t64_zstd1     UInt64                       CODEC(T64, ZSTD(1)),
                    cnt_dd_zstd1      UInt64                       CODEC(DoubleDelta, ZSTD(1)),
                    tt_lz4            UInt64                       CODEC(LZ4),
                    tt_zstd1          UInt64                       CODEC(ZSTD(1)),
                    dur_lz4           Float64                      CODEC(LZ4),
                    dur_zstd1         Float64                      CODEC(ZSTD(1)),
                    dur_gorilla       Float64                      CODEC(Gorilla),
                    ttft_lz4          Float64                      CODEC(LZ4),
                    ttft_zstd1        Float64                      CODEC(ZSTD(1)),
                    ttft_gorilla      Float64                      CODEC(Gorilla),
                    arr_lz4           Array(String)                CODEC(LZ4),
                    arr_zstd1         Array(String)                CODEC(ZSTD(1)),
                    arr_zstd3         Array(String)                CODEC(ZSTD(3)),
                    ok_lz4            Array(Tuple(String, String)) CODEC(LZ4),
                    ok_zstd1          Array(Tuple(String, String)) CODEC(ZSTD(1)),
                    ok_zstd3          Array(Tuple(String, String)) CODEC(ZSTD(3)),
                    lcstr_zstd1       String                       CODEC(ZSTD(1)),
                    lc_default        LowCardinality(String),
                    lc_zstd1          LowCardinality(String)       CODEC(ZSTD(1)),
                    enum_default      Enum8('unknown' = 0, 'default' = 1, 'hidden' = 2),
                    enum_zstd1        Enum8('unknown' = 0, 'default' = 1, 'hidden' = 2) CODEC(ZSTD(1)),
                    flag_default      UInt8,
                    flag_zstd1        UInt8                        CODEC(ZSTD(1)),
                    et_lz4            DateTime64(6, 'UTC')         CODEC(LZ4),
                    et_zstd1          DateTime64(6, 'UTC')         CODEC(ZSTD(1)),
                    et_delta_zstd1    DateTime64(6, 'UTC')         CODEC(Delta, ZSTD(1)),
                    meta_lz4          String                       CODEC(LZ4),
                    meta_zstd1        String                       CODEC(ZSTD(1)),
                    meta_zstd3        String                       CODEC(ZSTD(3)),
                    thread_lz4        String                       CODEC(LZ4),
                    thread_zstd1      String                       CODEC(ZSTD(1)),
                    thread_zstd3      String                       CODEC(ZSTD(3)),
                    slim_zstd1        String                       CODEC(ZSTD(1)),
                    slim_zstd3        String                       CODEC(ZSTD(3)),
                    lua_lz4           DateTime64(6, 'UTC')         CODEC(LZ4),
                    lua_zstd1         DateTime64(6, 'UTC')         CODEC(ZSTD(1)),
                    lua_delta_zstd1   DateTime64(6, 'UTC')         CODEC(Delta, ZSTD(1)),
                    err_lz4           String                       CODEC(LZ4),
                    err_zstd1         String                       CODEC(ZSTD(1)),
                    err_zstd3         String                       CODEC(ZSTD(3)),
                    -- Nullable counterparts of the sentinel columns (same data, same codec) to measure the Nullable ->
                    -- sentinel storage change: the sentinel form above (et_zstd1/ttft_zstd1/dur_zstd1) vs these Nullable
                    -- forms, which additionally store a null-mask stream.
                    et_nullable_zstd1   Nullable(DateTime64(6, 'UTC')) CODEC(ZSTD(1)),
                    ttft_nullable_zstd1 Nullable(Float64)              CODEC(ZSTD(1)),
                    dur_nullable_zstd1  Nullable(Float64)              CODEC(ZSTD(1))
                )
                ENGINE = MergeTree ORDER BY tuple()
                """
                .formatted(DATABASE_NAME, TABLE));

        // Templated with replace() rather than formatted() so the SQL can use the ClickHouse '%' modulo operator freely.
        // Rows are generated in (workspace_id, project_id, id) order — numbers() order is that order by construction —
        // so the clustered keys and the monotonic id/id_at land the way a real sorted part stores them.
        execute("""
                INSERT INTO {table}
                    (
                        id_lz4,
                        id_zstd1,
                        id_zstd3,
                        proj_lz4,
                        proj_zstd1,
                        proj_zstd3,
                        ws_lz4,
                        ws_zstd1,
                        name_lz4,
                        name_zstd1,
                        name_zstd3,
                        text_lz4,
                        text_zstd1,
                        text_zstd3,
                        ts6_lz4,
                        ts6_zstd1,
                        ts6_delta_zstd1,
                        ts6_dd_zstd1,
                        ts9_delta_zstd1,
                        idat_lz4,
                        idat_zstd1,
                        idat_delta_zstd1,
                        idat_dd_zstd1,
                        cnt_zstd1,
                        cnt_t64_zstd1,
                        cnt_dd_zstd1,
                        tt_lz4,
                        tt_zstd1,
                        dur_lz4,
                        dur_zstd1,
                        dur_gorilla,
                        ttft_lz4,
                        ttft_zstd1,
                        ttft_gorilla,
                        arr_lz4,
                        arr_zstd1,
                        arr_zstd3,
                        ok_lz4,
                        ok_zstd1,
                        ok_zstd3,
                        lcstr_zstd1,
                        lc_default,
                        lc_zstd1,
                        enum_default,
                        enum_zstd1,
                        flag_default,
                        flag_zstd1,
                        et_lz4,
                        et_zstd1,
                        et_delta_zstd1,
                        meta_lz4,
                        meta_zstd1,
                        meta_zstd3,
                        thread_lz4,
                        thread_zstd1,
                        thread_zstd3,
                        slim_zstd1,
                        slim_zstd3,
                        lua_lz4,
                        lua_zstd1,
                        lua_delta_zstd1,
                        err_lz4,
                        err_zstd1,
                        err_zstd3,
                        et_nullable_zstd1,
                        ttft_nullable_zstd1,
                        dur_nullable_zstd1
                    )
                WITH base AS (
                    SELECT
                        -- Deterministic storage order (matches the (workspace_id, project_id, id) sort a real part has),
                        -- so compression is reproducible run to run.
                        number AS n,
                        -- workspace_id: UUID v4 (no timestamp prefix), low-cardinality and clustered as the first sort
                        -- key (~20 workspaces of 1000 rows). A rare short-text value mirrors the ~0.04% of production
                        -- workspaces that use a name instead of a UUID; it does not move the codec choice.
                        intDiv(number, 1000) AS w_idx,
                        hex(cityHash64(w_idx, 'wa')) AS wh1,
                        hex(cityHash64(w_idx, 'wb')) AS wh2,
                        if(number % 2500 = 0,
                            'acme-analytics-team',
                            lower(concat(
                                substring(wh1, 1, 8), '-',
                                substring(wh1, 9, 4), '-4',
                                substring(wh1, 13, 3), '-',
                                ['8', '9', 'a', 'b'][(cityHash64(w_idx, 'wv') % 4) + 1], substring(wh2, 1, 3), '-',
                                substring(wh2, 4, 12)))) AS workspace_id,
                        -- project_id: UUID v7, clustered as the second sort key (~200 projects of 100 rows). The 48-bit
                        -- prefix is the project's creation time (constant per project); the tail is random.
                        intDiv(number, 100) AS p_idx,
                        hex(toUInt64(toUnixTimestamp64Milli(toDateTime64('2025-06-01 00:00:00', 3, 'UTC'))
                            + p_idx * 3600000)) AS ph_ts,
                        hex(cityHash64(p_idx, 'pa')) AS ph1,
                        hex(cityHash64(p_idx, 'pb')) AS ph2,
                        toFixedString(lower(concat(
                            substring(ph_ts, 5, 8), '-',
                            substring(ph_ts, 13, 4), '-7',
                            substring(ph1, 1, 3), '-',
                            ['8', '9', 'a', 'b'][(cityHash64(p_idx, 'pv') % 4) + 1], substring(ph1, 4, 3), '-',
                            substring(ph2, 1, 12))), 36) AS project_id,
                        -- id: UUID v7, unique per row and strictly increasing over ~a week (30 s ± jitter between rows;
                        -- the 30 s step exceeds the jitter so order is preserved), i.e. the sorted order a real part
                        -- stores ids in. The tail is random.
                        toUInt64(toUnixTimestamp64Milli(toDateTime64('2026-01-06 00:00:00', 3, 'UTC'))
                            + number * 30000 + (cityHash64(number, 'ij') % 20000)) AS id_ms,
                        hex(id_ms) AS ih_ts,
                        hex(cityHash64(number, 'ia')) AS ih1,
                        hex(cityHash64(number, 'ib')) AS ih2,
                        toFixedString(lower(concat(
                            substring(ih_ts, 5, 8), '-',
                            substring(ih_ts, 13, 4), '-7',
                            substring(ih1, 1, 3), '-',
                            ['8', '9', 'a', 'b'][(cityHash64(number, 'iv') % 4) + 1], substring(ih1, 4, 3), '-',
                            substring(ih2, 1, 12))), 36) AS uid,
                        -- id_at: the event time at second precision, i.e. what UUIDv7ToDateTime(id) yields, derived
                        -- directly from the id's embedded millisecond so it stays monotonic in id and robust.
                        toDateTime(intDiv(id_ms, 1000), 'UTC') AS idat,
                        -- name: short trace name, medium cardinality (~750 distinct).
                        concat(['llm-call', 'chain', 'agent-step', 'retrieval', 'embedding', 'rerank', 'tool-call',
                                'completion', 'chat', 'summarize', 'classify', 'extract', 'generate', 'evaluate',
                                'moderate'][(cityHash64(number, 'nm') % 15) + 1],
                            '-', toString(cityHash64(number, 'ns') % 50)) AS name,
                        -- Monotonic ingestion timestamps: a 3 ms step plus whole-microsecond macro jitter (shared by
                        -- both precisions) plus 0-999 ns micro noise that only the nanosecond column carries. This
                        -- isolates exactly what the microsecond precision drop removes.
                        toDateTime64('2026-01-06 00:00:00', 9, 'UTC')
                            + toIntervalNanosecond(number * 3000000
                                + (cityHash64(number, 'macro') % 2000) * 1000
                                + (cityHash64(number) % 1000)) AS t9,
                        toDateTime64(t9, 6, 'UTC') AS t6,
                        concat('{"role":"assistant","content":"', {sentence}, '"}') AS txt,
                        -- Narrow counter standing in for the *_length materialized columns / usage counts.
                        toUInt64(500 + (cityHash64(number, 'cnt') % 4000)) AS cnt,
                        -- truncation_threshold: the DDL default (10001) on 99.5% of rows, occasionally lowered.
                        if(cityHash64(number, 'tt') % 1000 < 995,
                            toUInt64(10001),
                            toUInt64(cityHash64(number, 'ttv') % 10001)) AS threshold,
                        -- Two float profiles, both independent across rows: duration is mostly populated (end_time is
                        -- normally set), ttft is often absent (only streaming LLM traces set it).
                        if(cityHash64(number, 'durnan') % 100 < 5,
                            toFloat64('nan'),
                            round((cityHash64(number, 'dur') % 10000000) / 1000.0, 3)) AS dur,
                        if(cityHash64(number, 'ttftnan') % 100 < 80,
                            toFloat64('nan'),
                            round(10 + (cityHash64(number, 'tt2') % 2000000) / 1000.0, 3)) AS ttft,
                        -- tags: 0-3 short values from a realistic mix of framework names and custom labels, as in
                        -- ['Langchain', 'some_value']; many traces have none.
                        arrayMap(i -> ['Langchain', 'production', 'v2', 'experiment', 'rag', 'openai', 'staging',
                            'agent', 'baseline', 'llm'][(cityHash64(number, i) % 10) + 1],
                            range(cityHash64(number, 'ntag') % 4)) AS tags,
                        -- output_keys: the materialized (json key, json type) tuples, 1-5 per row.
                        arrayMap(i -> (['answer', 'score', 'tokens', 'model', 'choices', 'usage'][(cityHash64(number, i)
                            % 6) + 1], ['String', 'Int64', 'Object', 'Array'][(cityHash64(number, i, 'ty') % 4) + 1]),
                            range(1 + cityHash64(number, 'nk') % 5)) AS output_keys,
                        -- Low-cardinality string standing in for environment / source.
                        ['production', 'staging', 'development', ''][(cityHash64(number, 'env') % 4) + 1] AS env,
                        -- Enum8 standing in for visibility_mode / source.
                        ['unknown', 'default', 'hidden'][(cityHash64(number, 'vis') % 3) + 1] AS enum_value,
                        -- UInt8 flag standing in for is_deleted: 98% zeros, as live rows dominate.
                        toUInt8(if(cityHash64(number, 'flag') % 100 < 98, 0, 1)) AS flag,
                        -- end_time: start_time + a variable duration for 97% of rows; the epoch sentinel for the ~3%
                        -- still running or missing the final upsert. Because a trace's duration (up to ~30 s here) far
                        -- exceeds the inter-row start gap, end_time is NOT monotonic in storage order (unlike
                        -- start_time), and the epoch rows add outliers - both defeat Delta.
                        if(cityHash64(number, 'etnan') % 100 < 3,
                            toDateTime64('1970-01-01 00:00:00', 6, 'UTC'),
                            t6 + toIntervalMillisecond(cityHash64(number, 'etdur') % 30000)) AS et,
                        -- metadata: a JSON key-value document (not the large LLM input/output; no slim/truncated
                        -- variants), with a mix of structured fields and a short free-text note so it is not
                        -- pathologically uniform.
                        concat('{"environment":"', env,
                            '","sdk_version":"1.', toString(cityHash64(number, 'mv') % 40),
                            '.0","user_tier":"', ['free', 'pro', 'enterprise'][(cityHash64(number, 'mt') % 3) + 1],
                            '","session":"', substring(lower(hex(cityHash64(number, 'ms'))), 1, 12),
                            '","note":"', arrayStringConcat(arrayMap(i -> ['user', 'api', 'batch', 'retry', 'cache',
                                'async', 'sync', 'job', 'queue', 'worker'][(cityHash64(number, i, 'mn') % 10) + 1],
                                range(6)), ' '),
                            '","retries":', toString(cityHash64(number, 'mr') % 4), '}') AS meta,
                        -- thread_id: a flexible free-form identifier — empty for ~60% (not in a thread), else a UUIDv4
                        -- or a short random string id. Not in the sort key, so it is scattered (high cardinality).
                        multiIf(
                            cityHash64(number, 'thn') % 100 < 60, '',
                            cityHash64(number, 'tht') % 2 = 0,
                                lower(concat(substring(hex(cityHash64(number, 't1')), 1, 8), '-',
                                    substring(hex(cityHash64(number, 't1')), 9, 4), '-4',
                                    substring(hex(cityHash64(number, 't2')), 1, 3), '-8',
                                    substring(hex(cityHash64(number, 't2')), 4, 3), '-',
                                    substring(hex(cityHash64(number, 't3')), 1, 12))),
                            concat('thread_', lower(hex(cityHash64(number, 't4'))))) AS thread_id,
                        -- input_slim / truncated_*: JSON derived from input, just size-capped (a leaf-capped or prefix
                        -- form). Same text profile as input, only smaller — represented by a bounded slice of it.
                        substring(txt, 1, 250) AS slim,
                        -- last_updated_at: the ReplacingMergeTree version column = the last write time. ~70% of traces
                        -- are created then finalized, so their last write is ~ start + duration (scrambled in storage
                        -- order like end_time); ~30% are single-write (= creation, monotonic). No epoch sentinel.
                        if(cityHash64(number, 'luaupd') % 100 < 70,
                            t6 + toIntervalMillisecond(cityHash64(number, 'luad') % 30000),
                            t6) AS lua,
                        -- error_info: empty for ~90% (successful traces); otherwise a JSON stack trace whose traceback
                        -- is repetitive multi-frame text (exception_type + message + frames).
                        if(cityHash64(number, 'errn') % 100 < 90,
                            '',
                            concat('{"exception_type":"',
                                ['ValueError', 'KeyError', 'TypeError', 'RuntimeError', 'TimeoutError'][(cityHash64(
                                    number, 'ety') % 5) + 1],
                                '","message":"request failed after retries","traceback":"',
                                arrayStringConcat(arrayMap(i -> concat('at com.app.module',
                                    toString(cityHash64(number, i) % 20), '.handler(Module',
                                    toString(cityHash64(number, i) % 20), '.java:',
                                    toString(cityHash64(number, i, 'ln') % 500), ') '), range(10)), ''),
                                '"}')) AS err,
                        -- Nullable projections of the sentinel columns: absent (epoch / NaN) maps back to NULL, so the
                        -- Nullable columns hold the same logical data, differing only by the null-mask stream.
                        if(et = toDateTime64('1970-01-01 00:00:00', 6, 'UTC'), NULL, et) AS et_n,
                        if(isNaN(ttft), NULL, ttft) AS ttft_n,
                        if(isNaN(dur), NULL, dur) AS dur_n
                    FROM numbers({rows})
                )
                SELECT
                    uid,          -- id_lz4
                    uid,          -- id_zstd1
                    uid,          -- id_zstd3
                    project_id,   -- proj_lz4
                    project_id,   -- proj_zstd1
                    project_id,   -- proj_zstd3
                    workspace_id, -- ws_lz4
                    workspace_id, -- ws_zstd1
                    name,         -- name_lz4
                    name,         -- name_zstd1
                    name,         -- name_zstd3
                    txt,          -- text_lz4
                    txt,          -- text_zstd1
                    txt,          -- text_zstd3
                    t6,           -- ts6_lz4
                    t6,           -- ts6_zstd1
                    t6,           -- ts6_delta_zstd1
                    t6,           -- ts6_dd_zstd1
                    t9,           -- ts9_delta_zstd1
                    idat,         -- idat_lz4
                    idat,         -- idat_zstd1
                    idat,         -- idat_delta_zstd1
                    idat,         -- idat_dd_zstd1
                    cnt,          -- cnt_zstd1
                    cnt,          -- cnt_t64_zstd1
                    cnt,          -- cnt_dd_zstd1
                    threshold,    -- tt_lz4
                    threshold,    -- tt_zstd1
                    dur,          -- dur_lz4
                    dur,          -- dur_zstd1
                    dur,          -- dur_gorilla
                    ttft,         -- ttft_lz4
                    ttft,         -- ttft_zstd1
                    ttft,         -- ttft_gorilla
                    tags,         -- arr_lz4
                    tags,         -- arr_zstd1
                    tags,         -- arr_zstd3
                    output_keys,  -- ok_lz4
                    output_keys,  -- ok_zstd1
                    output_keys,  -- ok_zstd3
                    env,          -- lcstr_zstd1
                    env,          -- lc_default
                    env,          -- lc_zstd1
                    enum_value,   -- enum_default
                    enum_value,   -- enum_zstd1
                    flag,         -- flag_default
                    flag,         -- flag_zstd1
                    et,           -- et_lz4
                    et,           -- et_zstd1
                    et,           -- et_delta_zstd1
                    meta,         -- meta_lz4
                    meta,         -- meta_zstd1
                    meta,         -- meta_zstd3
                    thread_id,    -- thread_lz4
                    thread_id,    -- thread_zstd1
                    thread_id,    -- thread_zstd3
                    slim,         -- slim_zstd1
                    slim,         -- slim_zstd3
                    lua,          -- lua_lz4
                    lua,          -- lua_zstd1
                    lua,          -- lua_delta_zstd1
                    err,          -- err_lz4
                    err,          -- err_zstd1
                    err,          -- err_zstd3
                    et_n,         -- et_nullable_zstd1
                    ttft_n,       -- ttft_nullable_zstd1
                    dur_n         -- dur_nullable_zstd1
                FROM base
                ORDER BY n
                SETTINGS max_insert_threads = 1, max_threads = 1
                """
                .replace("{table}", DATABASE_NAME + "." + TABLE)
                .replace("{sentence}", SENTENCE_EXPR)
                .replace("{rows}", String.valueOf(ROW_COUNT)));

        // Merge to a single part so system.columns reports one clean compressed size per column.
        execute("OPTIMIZE TABLE %s.%s FINAL".formatted(DATABASE_NAME, TABLE));

        long rows = queryLong("SELECT count() FROM %s.%s".formatted(DATABASE_NAME, TABLE));
        assertThat(rows).isEqualTo(ROW_COUNT);

        fetchColumnStats(TABLE).forEach(stat -> columnStats.put(stat.name(), stat));
        logReport();
    }

    @Test
    void everyTracesLocalV2ColumnUsesItsIntendedCodec() {
        var actualCodecs = fetchColumnCodecs("traces_local_v2");

        // The live DDL must expose exactly the columns we classified: an unclassified new column forces re-validation.
        assertThat(actualCodecs.keySet()).isEqualTo(TRACES_LOCAL_V2_CODECS.keySet());

        actualCodecs.forEach((column, codec) -> {
            var expected = TRACES_LOCAL_V2_CODECS.get(column);
            assertThat(expected.matches(codec))
                    .as("column %s expected %s codec but was '%s'", column, expected, codec)
                    .isTrue();
        });
    }

    @Test
    void uniqueUuidV7IdColumnCompressesBestWithZstd1() {
        long uncompressed = uncompressed("id_zstd1");
        long lz4 = compressed("id_lz4");
        long zstd1 = compressed("id_zstd1");
        long zstd3 = compressed("id_zstd3");

        // id is unique per row: the entropy is in the random tail. ZSTD(1) compresses far better than LZ4, and ZSTD(3)
        // does not improve on the tail — so ZSTD(1) is the right level. The LZ4 margin is data-dependent and only logged.
        assertThat(zstd1).isLessThan(uncompressed);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(zstd3 * 1.05));
        log.info("[OPIK-6899] unique id (UUIDv7) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    @Test
    void clusteredUuidV7ProjectIdColumnCompressesBestWithZstd1() {
        long uncompressed = uncompressed("proj_zstd1");
        long lz4 = compressed("proj_lz4");
        long zstd1 = compressed("proj_zstd1");
        long zstd3 = compressed("proj_zstd3");

        // project_id repeats in long runs (second sort key), so it compresses to a tiny fraction of its raw size
        // (~170x) and beats LZ4 under ZSTD(1). ZSTD(3) is marginally smaller still on the run structure, but the
        // absolute gap is a few hundred bytes, so the cheaper ZSTD(1) is the right choice. Logged, not asserted, since
        // that gap is immaterial.
        assertThat(zstd1).isLessThan(uncompressed);
        assertThat(zstd1).isLessThan(lz4);
        log.info("[OPIK-6899] clustered project_id (UUIDv7) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    @Test
    void clusteredUuidV4WorkspaceIdColumnCompressesBestWithZstd1() {
        long uncompressed = uncompressed("ws_zstd1");
        long lz4 = compressed("ws_lz4");
        long zstd1 = compressed("ws_zstd1");

        // workspace_id is a UUIDv4 (no timestamp prefix) but clustered in long runs (first sort key), so it also
        // compresses to a small fraction of its raw size; ZSTD(1) is at least as good as LZ4. It cannot be
        // LowCardinality (it is the ORDER BY prefix), so ZSTD(1) String is the right choice.
        assertThat(zstd1).isLessThan(uncompressed);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(lz4 * 1.05));
        log.info("[OPIK-6899] clustered workspace_id (UUIDv4) compressed bytes | LZ4: {} | ZSTD(1): {}", lz4, zstd1);
    }

    @Test
    void traceNameColumnCompressesBestWithZstd1() {
        long lz4 = compressed("name_lz4");
        long zstd1 = compressed("name_zstd1");
        long zstd3 = compressed("name_zstd3");

        // The trace name is short medium-cardinality text; ZSTD(1) beats LZ4 and ZSTD(3) adds little.
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(zstd3 * 1.15));
    }

    @Test
    void longTextColumnCompressesBestWithZstd3() {
        long lz4 = compressed("text_lz4");
        long zstd1 = compressed("text_zstd1");
        long zstd3 = compressed("text_zstd3");

        // ZSTD(3) is the smallest for input/output/metadata; decode cost equals ZSTD(1) (see the decompression test).
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd3).isLessThanOrEqualTo(zstd1);
    }

    @Test
    void timestampColumnCompressesBestWithDeltaZstd1() {
        long lz4 = compressed("ts6_lz4");
        long zstd1 = compressed("ts6_zstd1");
        long deltaZstd1 = compressed("ts6_delta_zstd1");
        long doubleDelta = compressed("ts6_dd_zstd1");

        // Delta + ZSTD(1) is the smallest microsecond variant, and beats DoubleDelta: with irregularly-spaced ingestion
        // timestamps DoubleDelta's constant-second-derivative bet fails.
        assertThat(deltaZstd1).isLessThan(lz4);
        assertThat(deltaZstd1).isLessThanOrEqualTo(Math.round(zstd1 * 1.02));
        assertThat(deltaZstd1).isLessThanOrEqualTo(doubleDelta);
    }

    @Test
    void idAtDateTimeColumnCompressesBestWithDeltaZstd1() {
        long lz4 = compressed("idat_lz4");
        long zstd1 = compressed("idat_zstd1");
        long deltaZstd1 = compressed("idat_delta_zstd1");
        long doubleDelta = compressed("idat_dd_zstd1");

        // id_at is a second-precision DateTime derived from the id, so it is monotonic within a part; Delta + ZSTD(1)
        // exploits that and beats plain ZSTD(1) and LZ4. Logged alongside DoubleDelta for the record.
        assertThat(deltaZstd1).isLessThan(lz4);
        assertThat(deltaZstd1).isLessThanOrEqualTo(Math.round(zstd1 * 1.02));
        log.info("[OPIK-6899] id_at (DateTime) compressed bytes | LZ4: {} | ZSTD(1): {} | Delta+ZSTD(1): {} | "
                + "DoubleDelta+ZSTD(1): {}", lz4, zstd1, deltaZstd1, doubleDelta);
    }

    @Test
    void microsecondTimestampCompressesBetterThanNanosecond() {
        long microseconds = compressed("ts6_delta_zstd1");
        long nanoseconds = compressed("ts9_delta_zstd1");

        // Dropping precision to microseconds strictly helps, because the nanosecond column carries sub-microsecond
        // noise (the now64(9) default's bottom three digits) that Delta cannot smooth away.
        assertThat(microseconds).isLessThan(nanoseconds);
    }

    @Test
    void narrowCounterColumnCompressesBestWithT64Zstd1() {
        long zstd1 = compressed("cnt_zstd1");
        long t64Zstd1 = compressed("cnt_t64_zstd1");
        long doubleDelta = compressed("cnt_dd_zstd1");

        // T64 + ZSTD(1) beats plain ZSTD(1) on the narrow *_length counters; DoubleDelta is worse (no monotonic trend).
        assertThat(t64Zstd1).isLessThanOrEqualTo(Math.round(zstd1 * 1.02));
        assertThat(t64Zstd1).isLessThan(doubleDelta);
    }

    @Test
    void truncationThresholdColumnCompressesTriviallyUnderZstd1() {
        long uncompressed = uncompressed("tt_zstd1");
        long lz4 = compressed("tt_lz4");
        long zstd1 = compressed("tt_zstd1");

        // truncation_threshold is the constant 10001 on ~all rows, so it crushes to almost nothing (< 0.1 byte/row)
        // under ZSTD(1) — comparable to LZ4. No dedicated codec is warranted; ZSTD(1) is fine.
        assertThat(zstd1).isLessThan(uncompressed);
        assertThat(zstd1).isLessThan(ROW_COUNT / 10);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(lz4 * 1.10));
    }

    @Test
    void floatColumnsUseZstd1RegardlessOfNaNFraction() {
        long durUncompressed = uncompressed("dur_zstd1");
        long durZstd1 = compressed("dur_zstd1");
        long durGorilla = compressed("dur_gorilla");
        long ttftZstd1 = compressed("ttft_zstd1");
        long ttftGorilla = compressed("ttft_gorilla");

        // duration is mostly populated; ttft is mostly the NaN sentinel. In BOTH regimes ZSTD(1) beats the
        // float-specialized Gorilla codec, because ttft/duration are independent across adjacent rows (each a different
        // trace), not the correlated time series Gorilla's XOR model targets. So the DDL's ZSTD(1) is right and the
        // choice does not hinge on any NaN-fraction assumption. Exact deltas are logged.
        assertThat(durZstd1).isLessThan(durUncompressed);
        assertThat(durZstd1).isLessThanOrEqualTo(durGorilla);
        assertThat(ttftZstd1).isLessThanOrEqualTo(ttftGorilla);
        log.info("[OPIK-6899] float compressed bytes | duration(mostly populated) ZSTD(1): {} vs Gorilla: {} | "
                + "ttft(mostly NaN) ZSTD(1): {} vs Gorilla: {}", durZstd1, durGorilla, ttftZstd1, ttftGorilla);
    }

    @Test
    void tagsArrayColumnCompressesBestWithZstd1() {
        long lz4 = compressed("arr_lz4");
        long zstd1 = compressed("arr_zstd1");
        long zstd3 = compressed("arr_zstd3");

        // tags is an Array(String) of low-cardinality values; ZSTD(1) beats LZ4 and ZSTD(3) adds little.
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(zstd3 * 1.15));
    }

    @Test
    void outputKeysTupleArrayColumnCompressesBetterWithZstd3() {
        long lz4 = compressed("ok_lz4");
        long zstd1 = compressed("ok_zstd1");
        long zstd3 = compressed("ok_zstd3");

        // output_keys is an Array(Tuple(String, String)) of (json key, json type). It has real structured-text content
        // (key + type names), so unlike the simple tags array ZSTD(3) compresses it materially better than ZSTD(1) here
        // (~16%). ZSTD(1) still clearly beats LZ4; the size of the ZSTD(3) win depends on real key cardinality (this
        // uses a small key dictionary).
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd3).isLessThanOrEqualTo(zstd1);
        log.info("[OPIK-6899] output_keys Array(Tuple) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    @Test
    void lowCardinalityTypeBeatsPlainString() {
        long plainString = compressed("lcstr_zstd1");
        long lowCardinality = compressed("lc_default");

        // The win on environment/source is the LowCardinality dictionary type itself: even under the default LZ4 codec
        // it beats a plain ZSTD(1) String holding the same values.
        assertThat(lowCardinality).isLessThan(plainString);
    }

    @Test
    void serverDefaultCodecColumnsAreNegligibleInSize() {
        long enumDefault = compressed("enum_default");
        long enumZstd1 = compressed("enum_zstd1");
        long flagDefault = compressed("flag_default");
        long lcDefault = compressed("lc_default");
        long lcZstd1 = compressed("lc_zstd1");

        // visibility_mode/source (Enum8), is_deleted (UInt8) and environment (LowCardinality) are left on the server
        // default. Each costs well under a byte per row whatever the codec. The ratio is NOT a wash — ZSTD(1) roughly
        // halves enum / LowCardinality here — but the columns are tiny, so the decode cost is what the choice turns on.
        // The decode measurement below (default vs ZSTD, single-thread) quantifies that size/decode trade-off.
        assertThat(enumDefault).isLessThan(ROW_COUNT);
        assertThat(flagDefault).isLessThan(ROW_COUNT);
        assertThat(lcDefault).isLessThan(ROW_COUNT);
        log.info("[OPIK-6899] server-default columns compressed bytes default vs ZSTD(1) | enum: {} vs {} | "
                + "lowCardinality: {} vs {}", enumDefault, enumZstd1, lcDefault, lcZstd1);
        log.info("[OPIK-6899] server-default columns decode cost default vs ZSTD(1) | enum: {} vs {} | "
                + "lowCardinality: {} vs {}",
                measureScan("enum_default"), measureScan("enum_zstd1"),
                measureScan("lc_default"), measureScan("lc_zstd1"));
    }

    @Test
    void isDeletedFlagGainsNothingFromZstd() {
        long defaultCodec = compressed("flag_default");
        long zstd1 = compressed("flag_zstd1");

        // is_deleted is 98% zeros and one byte wide, so it compresses to a few hundred bytes under either codec and the
        // two are within ~1% of each other — no meaningful benefit from ZSTD, so the default is kept.
        assertThat(defaultCodec).isLessThanOrEqualTo(Math.round(zstd1 * 1.05));
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(defaultCodec * 1.05));
    }

    @Test
    void endTimeIsNotMonotonicSoPlainZstd1BeatsDelta() {
        long lz4 = compressed("et_lz4");
        long zstd1 = compressed("et_zstd1");
        long deltaZstd1 = compressed("et_delta_zstd1");

        // Unlike start_time/created_at (monotonic in storage order), end_time is start_time + a variable duration and
        // carries epoch sentinels for unfinished traces, so it is NOT monotonic in storage order: Delta hurts rather
        // than helps and plain ZSTD(1) is the smallest. The size of the gap depends on the duration distribution and
        // the epoch fraction.
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThanOrEqualTo(deltaZstd1);
        log.info(
                "[OPIK-6899] end_time (start+duration, ~3% epoch) compressed bytes | LZ4: {} | ZSTD(1): {} | Delta+ZSTD(1): {}",
                lz4, zstd1, deltaZstd1);
    }

    @Test
    void lastUpdatedAtIsScrambledSoPlainZstd1BeatsDelta() {
        long lz4 = compressed("lua_lz4");
        long zstd1 = compressed("lua_zstd1");
        long deltaZstd1 = compressed("lua_delta_zstd1");

        // last_updated_at is the ReplacingMergeTree version column = last write time. For created-then-finalized traces
        // (~70%) it is ~ start + duration, so like end_time it is NOT monotonic in storage order and plain ZSTD(1) beats
        // Delta. The size of the gap depends on the updated-trace fraction.
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThanOrEqualTo(deltaZstd1);
        log.info(
                "[OPIK-6899] last_updated_at (version column, ~70% updated) compressed bytes | LZ4: {} | ZSTD(1): {} | "
                        + "Delta+ZSTD(1): {}",
                lz4, zstd1, deltaZstd1);
    }

    @Test
    void errorInfoStackTraceColumnStaysOnZstd1() {
        long lz4 = compressed("err_lz4");
        long zstd1 = compressed("err_zstd1");
        long zstd3 = compressed("err_zstd3");

        // error_info is empty for ~90% of traces; when present it is a JSON stack trace with repetitive traceback text.
        // ZSTD(1) beats LZ4, and ZSTD(3) does NOT improve on it (the column is mostly empty and ZSTD(1) already captures
        // the repetitive frames), so the shipped ZSTD(1) is right — no ZSTD(3) upgrade, unlike output_keys.
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(zstd3 * 1.05));
        log.info("[OPIK-6899] error_info (~10% stack traces) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    @Test
    void metadataJsonColumnCompressesWellWithZstd() {
        long lz4 = compressed("meta_lz4");
        long zstd1 = compressed("meta_zstd1");
        long zstd3 = compressed("meta_zstd3");

        // metadata is a JSON key-value document, distinct from the large input/output text (and with no slim/truncated
        // forms). ZSTD clearly beats LZ4; ZSTD(1) vs ZSTD(3) is close and depends on how varied the metadata is (on
        // structured metadata ZSTD(1) can edge out ZSTD(3)). The shipped ZSTD(3) is safe (free decode) and helps varied
        // metadata; ZSTD(1) is equally fine. Logged, not asserted, since the winner is data-dependent.
        assertThat(zstd1).isLessThan(lz4);
        log.info("[OPIK-6899] metadata JSON compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}", lz4, zstd1, zstd3);
    }

    @Test
    void flexibleThreadIdColumnCompressesBestWithZstd1() {
        long lz4 = compressed("thread_lz4");
        long zstd1 = compressed("thread_zstd1");
        long zstd3 = compressed("thread_zstd3");

        // thread_id is a free-form identifier — mostly empty, else a UUIDv4 or a short random string — and scattered
        // (not in the sort key). ZSTD(1) beats LZ4 whatever the exact format; ZSTD(3) adds little. Shipped ZSTD(1) holds.
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(zstd3 * 1.15));
    }

    @Test
    void slimAndTruncatedTextColumnsCompressBestWithZstd3() {
        long zstd1 = compressed("slim_zstd1");
        long zstd3 = compressed("slim_zstd3");

        // input_slim / output_slim / truncated_* are size-capped JSON derived from input/output — the same text profile,
        // so truncation changes only the size, not the codec class: ZSTD(3) stays at least as small as ZSTD(1). Shipped
        // ZSTD(3) holds.
        assertThat(zstd3).isLessThanOrEqualTo(zstd1);
    }

    @Test
    void sentinelColumnsAreNoLargerThanNullable() {
        // end_time/ttft/duration are non-Nullable with epoch/NaN sentinels rather than Nullable. This measures only the
        // storage effect (codec is ZSTD(1) either way): the sentinel form drops the separate Nullable null-mask stream.
        // At this row count the null-mask is fixed-overhead-dominated, so treat the delta as directional.
        long etSentinel = compressed("et_zstd1");
        long etNullable = compressed("et_nullable_zstd1");
        long ttftSentinel = compressed("ttft_zstd1");
        long ttftNullable = compressed("ttft_nullable_zstd1");
        long durSentinel = compressed("dur_zstd1");
        long durNullable = compressed("dur_nullable_zstd1");

        // The sentinel form is smaller than Nullable on every column (it drops the null-mask); assert that and log the
        // deltas. The margin is largest on the mostly-absent ttft (~9%) and small on the mostly-populated ones (~1-2%).
        assertThat(etSentinel).isLessThanOrEqualTo(etNullable);
        assertThat(ttftSentinel).isLessThanOrEqualTo(ttftNullable);
        assertThat(durSentinel).isLessThanOrEqualTo(durNullable);
        log.info("[OPIK-6899] sentinel vs Nullable compressed bytes | end_time: {} vs {} | ttft: {} vs {} | "
                + "duration: {} vs {}", etSentinel, etNullable, ttftSentinel, ttftNullable, durSentinel, durNullable);
    }

    @Test
    void compressionIsInvariantToIndexGranularityBytes() {
        // index_granularity_bytes (raised to 40 MiB in traces_local_v2) sets the granule byte-cap and hence the number
        // of marks, the primary-index size and skip-index pruning granularity — a query-side / read-amplification
        // concern. It does NOT drive per-column compression, which is block-based (max_compress_block_size), so it is out
        // of scope for a compression benchmark. Confirmed here so the benchmark's sizes (taken at the default
        // granularity) also hold for the 40 MiB table: the same wide-row data under the default vs the 40 MiB byte-cap
        // gives a different granule structure (fewer marks) but the same compressed size.
        var defaultTable = "igb_default";
        var wideGranuleTable = "igb_40mib";
        for (var table : List.of(defaultTable, wideGranuleTable)) {
            execute("DROP TABLE IF EXISTS %s.%s".formatted(DATABASE_NAME, table));
        }
        execute("CREATE TABLE %s.%s (body String CODEC(ZSTD(3))) ENGINE = MergeTree ORDER BY tuple()"
                .formatted(DATABASE_NAME, defaultTable));
        execute(("CREATE TABLE %s.%s (body String CODEC(ZSTD(3))) ENGINE = MergeTree ORDER BY tuple() "
                + "SETTINGS index_granularity_bytes = 41943040").formatted(DATABASE_NAME, wideGranuleTable));

        // ~8 KB wide rows so the 10 MiB default cap and the 40 MiB cap land on different granule sizes (different marks).
        var wideBody = """
                arrayStringConcat(arrayMap(i -> ['the','user','asked','the','assistant','about','weather','and',
                    'requested','a','concise','summary','of','the','document','with','several','key','points','listed',
                    'in','order','please'][(cityHash64(number, i) % 23) + 1], range(1300)), ' ')
                """;
        var insert = "INSERT INTO %s.%s SELECT %s AS body FROM numbers(6000) SETTINGS max_insert_threads = 1, max_threads = 1";
        execute(insert.formatted(DATABASE_NAME, defaultTable, wideBody));
        execute(insert.formatted(DATABASE_NAME, wideGranuleTable, wideBody));
        execute("OPTIMIZE TABLE %s.%s FINAL".formatted(DATABASE_NAME, defaultTable));
        execute("OPTIMIZE TABLE %s.%s FINAL".formatted(DATABASE_NAME, wideGranuleTable));

        long defaultBytes = compressedBytesOf(defaultTable, "body");
        long wideBytes = compressedBytesOf(wideGranuleTable, "body");
        long defaultMarks = marksOf(defaultTable);
        long wideMarks = marksOf(wideGranuleTable);

        // The 40 MiB cap yields bigger granules (fewer marks), proving the setting took effect...
        assertThat(wideMarks).isLessThan(defaultMarks);
        // ...yet the compressed size is unchanged (within 2%) — compression is block-based, not granule-based.
        assertThat(Math.abs(defaultBytes - wideBytes)).isLessThanOrEqualTo(Math.round(defaultBytes * 0.02));
        log.info("[OPIK-6899] index_granularity_bytes default vs 40 MiB | compressed: {} vs {} bytes | marks: {} vs {}",
                defaultBytes, wideBytes, defaultMarks, wideMarks);

        for (var table : List.of(defaultTable, wideGranuleTable)) {
            execute("DROP TABLE IF EXISTS %s.%s".formatted(DATABASE_NAME, table));
        }
    }

    @Test
    void wholeRowStorageBeforeVsAfter() {
        // Headline number: total compressed size of a full traces row in the OLD prod format (all columns on the LZ4
        // default, DateTime64(9), Nullable end_time/ttft/duration) vs the NEW traces_local_v2 format (tuned per-column
        // codecs, DateTime64(6), epoch/NaN sentinels, is_deleted). Same data staged in full_src, inserted into both, so
        // the delta is the format alone. Partition/skip-indexes are omitted (confirmed not to move column compression).
        // Realistic proportions: input/output dominate, with the truncated_*/slim derived copies as in prod.
        for (var table : List.of("full_src", "full_before", "full_after")) {
            execute("DROP TABLE IF EXISTS %s.%s".formatted(DATABASE_NAME, table));
        }

        // Raw staging table (no codecs, no materialized columns) — the values the app writes.
        execute(("""
                CREATE TABLE {db}.full_src
                (
                    id String, workspace_id String, project_id String, name String,
                    start_time DateTime64(9, 'UTC'), end_time DateTime64(9, 'UTC'),
                    input String, output String, metadata String, tags Array(String),
                    created_at DateTime64(9, 'UTC'), last_updated_at DateTime64(6, 'UTC'),
                    created_by String, last_updated_by String, error_info String, thread_id String,
                    visibility_mode Enum8('unknown' = 0, 'default' = 1, 'hidden' = 2),
                    truncation_threshold UInt64, input_slim String, output_slim String, ttft Float64,
                    source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5),
                    environment LowCardinality(String)
                )
                ENGINE = MergeTree ORDER BY tuple()
                """)
                .replace("{db}", DATABASE_NAME));

        // ~3 KB input / ~6 KB output / ~0.6 KB metadata JSON; keys clustered as in prod; end_time = start + duration.
        var vocab = "['the','user','asked','the','assistant','about','weather','and','requested','a','concise',"
                + "'summary','of','the','document','with','several','key','points','listed','in','order','please']";
        execute(("""
                INSERT INTO {db}.full_src
                WITH toUInt64(toUnixTimestamp64Milli(toDateTime64('2026-01-06 00:00:00', 3, 'UTC')) + number * 3000)
                    AS id_ms
                SELECT
                    -- UUIDv7 id (48-bit timestamp prefix) so the id_at MATERIALIZED column is meaningful, as in prod.
                    lower(concat(substring(hex(id_ms), 5, 8), '-',
                        substring(hex(id_ms), 13, 4), '-7', substring(hex(cityHash64(number, 'i1')), 1, 3),
                        '-8', substring(hex(cityHash64(number, 'i2')), 4, 3), '-',
                        substring(hex(cityHash64(number, 'i3')), 1, 12))) AS id,
                    lower(hex(cityHash64(intDiv(number, 150), 'ws'))) AS workspace_id,
                    lower(hex(cityHash64(intDiv(number, 30), 'pj'))) AS project_id,
                    concat('op-', toString(cityHash64(number, 'nm') % 200)) AS name,
                    toDateTime64('2026-01-06 00:00:00', 9, 'UTC') + toIntervalMillisecond(number * 3000) AS start_time,
                    toDateTime64('2026-01-06 00:00:00', 9, 'UTC') + toIntervalMillisecond(number * 3000
                        + (cityHash64(number, 'dur') % 30000)) AS end_time,
                    concat('{"messages":[{"role":"user","content":"', arrayStringConcat(arrayMap(i ->
                        VOCAB[(cityHash64(number, i, 'in') % 23) + 1], range(500)), ' '), '"}]}') AS input,
                    concat('{"choices":[{"message":{"content":"', arrayStringConcat(arrayMap(i ->
                        VOCAB[(cityHash64(number, i, 'ou') % 23) + 1], range(1000)), ' '), '"}}]}') AS output,
                    concat('{"env":"prod","model":"gpt-4o","note":"', arrayStringConcat(arrayMap(i ->
                        VOCAB[(cityHash64(number, i, 'md') % 23) + 1], range(80)), ' '), '"}') AS metadata,
                    arrayMap(i -> ['Langchain','production','rag','agent','llm'][(cityHash64(number, i) % 5) + 1],
                        range(cityHash64(number, 'ntag') % 4)) AS tags,
                    toDateTime64('2026-01-06 00:00:00', 9, 'UTC') + toIntervalMillisecond(number * 3000) AS created_at,
                    toDateTime64('2026-01-06 00:00:00', 6, 'UTC') + toIntervalMillisecond(number * 3000
                        + (cityHash64(number, 'lua') % 30000)) AS last_updated_at,
                    concat('user-', toString(cityHash64(number, 'cb') % 500)) AS created_by,
                    concat('user-', toString(cityHash64(number, 'ub') % 500)) AS last_updated_by,
                    if(cityHash64(number, 'err') % 100 < 90, '', concat('{"exception_type":"ValueError","traceback":"',
                        arrayStringConcat(arrayMap(i -> concat('at m', toString(cityHash64(number, i) % 20), ' '),
                        range(10)), ''), '"}')) AS error_info,
                    if(cityHash64(number, 'th') % 100 < 60, '',
                        lower(hex(cityHash64(number, 'thv')))) AS thread_id,
                    ['unknown','default','hidden'][(cityHash64(number, 'vis') % 3) + 1] AS visibility_mode,
                    toUInt64(10001) AS truncation_threshold,
                    substring(concat('{"messages":[{"role":"user","content":"', arrayStringConcat(arrayMap(i ->
                        VOCAB[(cityHash64(number, i, 'in') % 23) + 1], range(500)), ' '), '"}]}'), 1, 1000) AS input_slim,
                    substring(concat('{"choices":[{"message":{"content":"', arrayStringConcat(arrayMap(i ->
                        VOCAB[(cityHash64(number, i, 'ou') % 23) + 1], range(1000)), ' '), '"}}]}'), 1, 1000) AS output_slim,
                    if(cityHash64(number, 'ttft') % 100 < 80, toFloat64('nan'),
                        round(10 + (cityHash64(number, 'tv') % 2000000) / 1000.0, 3)) AS ttft,
                    ['sdk','experiment','playground'][(cityHash64(number, 'src') % 3) + 1] AS source,
                    ['production','staging','development',''][(cityHash64(number, 'env') % 4) + 1] AS environment
                FROM numbers(3000) SETTINGS max_insert_threads = 1, max_threads = 1
                """)
                .replace("{db}", DATABASE_NAME).replace("VOCAB", vocab));

        var beforeMaterialized = """
                    input_length UInt64 MATERIALIZED length(input),
                    output_length UInt64 MATERIALIZED length(output),
                    metadata_length UInt64 MATERIALIZED length(metadata),
                    truncated_input String MATERIALIZED if(length(input) >= truncation_threshold, substring(input, 1, truncation_threshold), input),
                    truncated_output String MATERIALIZED if(length(output) >= truncation_threshold, substring(output, 1, truncation_threshold), output),
                    output_keys Array(Tuple(key String, type String)) MATERIALIZED arrayMap(key -> (key, toString(JSONType(JSONExtractRaw(output, key)))), JSONExtractKeys(output)),
                    duration Nullable(Float64) MATERIALIZED if((end_time IS NOT NULL) AND (start_time IS NOT NULL) AND (start_time != toDateTime64('1970-01-01 00:00:00.000', 9)), dateDiff('microsecond', start_time, end_time) / 1000., NULL),
                    id_at DateTime('UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(id))
                """;
        // OLD prod format: LZ4 default everywhere, DateTime64(9), Nullable end_time/ttft/duration, no is_deleted.
        execute(("""
                CREATE TABLE {db}.full_before
                (
                    id FixedString(36), workspace_id String, project_id FixedString(36), name String,
                    start_time DateTime64(9, 'UTC'), end_time Nullable(DateTime64(9, 'UTC')),
                    input String, output String, metadata String, tags Array(String),
                    created_at DateTime64(9, 'UTC'), last_updated_at DateTime64(6, 'UTC'),
                    created_by String, last_updated_by String, error_info String, thread_id String,
                    visibility_mode Enum8('unknown' = 0, 'default' = 1, 'hidden' = 2),
                    truncation_threshold UInt64, input_slim String, output_slim String, ttft Nullable(Float64),
                    source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5),
                    environment LowCardinality(String),
                """
                + beforeMaterialized + """
                        )
                        ENGINE = MergeTree ORDER BY (workspace_id, project_id, id)
                        """).replace("{db}", DATABASE_NAME));

        var afterMaterialized = """
                    input_length UInt64 MATERIALIZED length(input) CODEC(T64, ZSTD(1)),
                    output_length UInt64 MATERIALIZED length(output) CODEC(T64, ZSTD(1)),
                    metadata_length UInt64 MATERIALIZED length(metadata) CODEC(T64, ZSTD(1)),
                    truncated_input String MATERIALIZED if(length(input) >= truncation_threshold, substring(input, 1, truncation_threshold), input) CODEC(ZSTD(3)),
                    truncated_output String MATERIALIZED if(length(output) >= truncation_threshold, substring(output, 1, truncation_threshold), output) CODEC(ZSTD(3)),
                    output_keys Array(Tuple(key String, type String)) MATERIALIZED arrayMap(key -> (key, toString(JSONType(JSONExtractRaw(output, key)))), JSONExtractKeys(output)) CODEC(ZSTD(3)),
                    duration Float64 MATERIALIZED if(end_time = toDateTime64('1970-01-01 00:00:00', 6) OR start_time = toDateTime64('1970-01-01 00:00:00', 6), toFloat64('nan'), dateDiff('microsecond', start_time, end_time) / 1000.0) CODEC(ZSTD(1)),
                    id_at DateTime('UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(id)) CODEC(Delta, ZSTD(1))
                """;
        // NEW traces_local_v2 format = the adopted end-state we intend to ship: the shipped 000101 codecs PLUS all six
        // benchmark-driven best-guess refinements (end_time / last_updated_at -> ZSTD(1); visibility_mode / source /
        // environment -> ZSTD(1); output_keys -> ZSTD(3)). This deliberately differs from 000101 and from the pin map,
        // which the drift guard keeps matching the live table until the refinements' ALTER migration lands. So this
        // headline is the format we're steering to (best guess, pending staging re-validation), not the interim deploy.
        execute(("""
                CREATE TABLE {db}.full_after
                (
                    id FixedString(36) CODEC(ZSTD(1)), workspace_id String CODEC(ZSTD(1)),
                    project_id FixedString(36) CODEC(ZSTD(1)), name String CODEC(ZSTD(1)),
                    start_time DateTime64(6, 'UTC') CODEC(Delta, ZSTD(1)), end_time DateTime64(6, 'UTC') CODEC(ZSTD(1)),
                    input String CODEC(ZSTD(3)), output String CODEC(ZSTD(3)), metadata String CODEC(ZSTD(3)),
                    tags Array(String) CODEC(ZSTD(1)),
                    created_at DateTime64(6, 'UTC') CODEC(Delta, ZSTD(1)), last_updated_at DateTime64(6, 'UTC') CODEC(ZSTD(1)),
                    created_by String CODEC(ZSTD(1)), last_updated_by String CODEC(ZSTD(1)),
                    error_info String CODEC(ZSTD(1)), thread_id String CODEC(ZSTD(1)),
                    visibility_mode Enum8('unknown' = 0, 'default' = 1, 'hidden' = 2) CODEC(ZSTD(1)),
                    truncation_threshold UInt64 CODEC(ZSTD(1)), input_slim String CODEC(ZSTD(3)), output_slim String CODEC(ZSTD(3)),
                    ttft Float64 CODEC(ZSTD(1)),
                    source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) CODEC(ZSTD(1)),
                    environment LowCardinality(String) CODEC(ZSTD(1)), is_deleted UInt8,
                """
                + afterMaterialized + """
                        )
                        ENGINE = MergeTree ORDER BY (workspace_id, project_id, id)
                        """).replace("{db}", DATABASE_NAME));

        var storedColumns = "id, workspace_id, project_id, name, start_time, end_time, input, output, metadata, tags, "
                + "created_at, last_updated_at, created_by, last_updated_by, error_info, thread_id, visibility_mode, "
                + "truncation_threshold, input_slim, output_slim, ttft, source, environment";
        execute(("INSERT INTO {db}.full_before (" + storedColumns + ") SELECT " + storedColumns
                + " FROM {db}.full_src SETTINGS max_insert_threads = 1, max_threads = 1")
                .replace("{db}", DATABASE_NAME));
        execute(("INSERT INTO {db}.full_after (" + storedColumns + ", is_deleted) SELECT "
                + "id, workspace_id, project_id, name, toDateTime64(start_time, 6, 'UTC'), toDateTime64(end_time, 6, 'UTC'), "
                + "input, output, metadata, tags, toDateTime64(created_at, 6, 'UTC'), last_updated_at, created_by, "
                + "last_updated_by, error_info, thread_id, visibility_mode, truncation_threshold, input_slim, output_slim, "
                + "ttft, source, environment, 0 FROM {db}.full_src SETTINGS max_insert_threads = 1, max_threads = 1")
                .replace("{db}", DATABASE_NAME));
        execute("OPTIMIZE TABLE %s.full_before FINAL".formatted(DATABASE_NAME));
        execute("OPTIMIZE TABLE %s.full_after FINAL".formatted(DATABASE_NAME));

        long before = totalCompressedOf("full_before");
        long after = totalCompressedOf("full_after");
        long rows = queryLong("SELECT count() FROM %s.full_before".formatted(DATABASE_NAME));

        // The new format must be materially smaller on the same data.
        assertThat(after).isLessThan(before);
        log.info("[OPIK-6899] whole-row storage before (prod format) vs after (traces_local_v2) | {} vs {} bytes "
                + "over {} rows | {} vs {} bytes/row | after is {}% of before",
                before, after, rows, before / rows, after / rows, Math.round(100.0 * after / before));

        for (var table : List.of("full_src", "full_before", "full_after")) {
            execute("DROP TABLE IF EXISTS %s.%s".formatted(DATABASE_NAME, table));
        }
    }

    @Test
    void compressionIsInvariantToWeeklyPartitioning() {
        // PARTITION BY toMonday(id_at) is a data-lifecycle mechanism (retention + tiering) validated by
        // TracesLocalV2PartitioningTest, not a codec — and it is required regardless of size. It splits data into weekly
        // parts that each compress independently, so it can only add minor per-part overhead (e.g. one LowCardinality
        // dictionary per part), not change per-column compression. Confirmed here so the benchmark's sizes (measured
        // unpartitioned) transfer to the partitioned traces_local_v2: the same data spanning several weeks, weekly-
        // partitioned vs not, yields multiple partitions but ~the same total compressed size.
        var flatTable = "part_flat";
        var weeklyTable = "part_weekly";
        for (var table : List.of(flatTable, weeklyTable)) {
            execute("DROP TABLE IF EXISTS %s.%s".formatted(DATABASE_NAME, table));
        }
        var columns = "(id_at DateTime('UTC') CODEC(Delta, ZSTD(1)), body String CODEC(ZSTD(3)), "
                + "env LowCardinality(String))";
        execute("CREATE TABLE %s.%s %s ENGINE = MergeTree ORDER BY tuple()"
                .formatted(DATABASE_NAME, flatTable, columns));
        execute("CREATE TABLE %s.%s %s ENGINE = MergeTree PARTITION BY toMonday(id_at) ORDER BY tuple()"
                .formatted(DATABASE_NAME, weeklyTable, columns));

        // id_at spread over ~8 weeks so weekly partitioning yields several parts; env is LowCardinality (per-part
        // dictionary), the column most likely to show partitioning overhead.
        var insert = ("INSERT INTO %s.%s SELECT "
                + "toDateTime('2026-01-05 00:00:00', 'UTC') + toIntervalSecond(number * 250) AS id_at, "
                + "%s AS body, "
                + "['production', 'staging', 'development', ''][(cityHash64(number, 'env') %% 4) + 1] AS env "
                + "FROM numbers(%d) SETTINGS max_insert_threads = 1, max_threads = 1");
        execute(insert.formatted(DATABASE_NAME, flatTable, SENTENCE_EXPR, ROW_COUNT));
        execute(insert.formatted(DATABASE_NAME, weeklyTable, SENTENCE_EXPR, ROW_COUNT));
        execute("OPTIMIZE TABLE %s.%s FINAL".formatted(DATABASE_NAME, flatTable));
        execute("OPTIMIZE TABLE %s.%s FINAL".formatted(DATABASE_NAME, weeklyTable));

        long flatBytes = totalCompressedOf(flatTable);
        long weeklyBytes = totalCompressedOf(weeklyTable);
        long partitions = queryLong(("SELECT uniqExact(partition) FROM system.parts "
                + "WHERE database = '%s' AND table = '%s' AND active").formatted(DATABASE_NAME, weeklyTable));

        // Partitioning took effect (several weekly parts)...
        assertThat(partitions).isGreaterThan(1);
        // ...yet the total compressed size is within a few percent — partitioning is not a compression lever.
        assertThat(Math.abs(flatBytes - weeklyBytes)).isLessThanOrEqualTo(Math.round(flatBytes * 0.05));
        log.info("[OPIK-6899] weekly partitioning vs flat | compressed: {} vs {} bytes | partitions: {}",
                flatBytes, weeklyBytes, partitions);

        for (var table : List.of(flatTable, weeklyTable)) {
            execute("DROP TABLE IF EXISTS %s.%s".formatted(DATABASE_NAME, table));
        }
    }

    @Test
    void microsecondConversionTruncatesInsteadOfRounding() {
        execute("DROP TABLE IF EXISTS %s.dt64_src".formatted(DATABASE_NAME));
        execute("DROP TABLE IF EXISTS %s.dt64_dst".formatted(DATABASE_NAME));
        execute("CREATE TABLE %s.dt64_src (dt9 DateTime64(9, 'UTC')) ENGINE = MergeTree ORDER BY tuple()"
                .formatted(DATABASE_NAME));
        execute("CREATE TABLE %s.dt64_dst (dt6 DateTime64(6, 'UTC')) ENGINE = MergeTree ORDER BY tuple()"
                .formatted(DATABASE_NAME));
        // .123456789 -> the 7th digit (7) would flip the 6th (6 -> 7) under rounding.
        execute("INSERT INTO %s.dt64_src VALUES (toDateTime64('2026-01-01 00:00:00.123456789', 9, 'UTC'))"
                .formatted(DATABASE_NAME));
        // The exact operation the backfill runs: INSERT ... SELECT from DateTime64(9) into DateTime64(6).
        execute("INSERT INTO %s.dt64_dst SELECT dt9 FROM %s.dt64_src".formatted(DATABASE_NAME, DATABASE_NAME));

        String stored = queryString("SELECT toString(dt6) FROM %s.dt64_dst LIMIT 1".formatted(DATABASE_NAME));

        assertThat(stored).isEqualTo("2026-01-01 00:00:00.123456");
        log.info("[OPIK-6899] DateTime64(9)->(6) conversion of .123456789 stored as {} (truncation confirmed)", stored);
    }

    @Test
    void longTextDecompressionCostIsRecorded() {
        var lz4 = measureScan("text_lz4");
        var zstd1 = measureScan("text_zstd1");
        var zstd3 = measureScan("text_zstd3");

        assertThat(lz4).isNotNull();
        assertThat(zstd1).isNotNull();
        assertThat(zstd3).isNotNull();

        // ZSTD decode cost is independent of the compression level, so ZSTD(3) on long text costs no more to read than
        // ZSTD(1) while compressing better — the trade-off that makes ZSTD(3) worth it there. This is a single-shot scan
        // after one warmup (not a sustained concurrent-read load), so the robust takeaway is the ZSTD(3) ~= ZSTD(1)
        // parity; the absolute times are order-sensitive and only logged, never asserted.
        log.info("[OPIK-6899] long-text single-thread decompression scan cost | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    private void logReport() {
        var report = new StringBuilder("\n[OPIK-6899] Per-column codec benchmark on %,d synthetic traces rows\n"
                .formatted(ROW_COUNT));
        report.append("%-18s %16s %16s %8s\n".formatted("column", "uncompressed", "compressed", "ratio"));
        report.repeat("-", 62).append('\n');
        columnStats.values().forEach(stat -> report.append("%-18s %,16d %,16d %8.2fx\n".formatted(
                stat.name(), stat.uncompressedBytes(), stat.compressedBytes(), stat.ratio())));
        log.info(report.toString());
    }

    private long compressed(String column) {
        return stat(column).compressedBytes();
    }

    private long uncompressed(String column) {
        return stat(column).uncompressedBytes();
    }

    private ColumnStat stat(String column) {
        var stat = columnStats.get(column);
        assertThat(stat).as("column %s must be present in system.columns", column).isNotNull();
        return stat;
    }

    private List<ColumnStat> fetchColumnStats(String table) {
        return transactionTemplateAsync.stream(connection -> {
            var statement = connection.createStatement("""
                    SELECT
                        name,
                        data_compressed_bytes AS compressed,
                        data_uncompressed_bytes AS uncompressed
                    FROM system.columns
                    WHERE database = :database
                    AND table = :table
                    ORDER BY position
                    """)
                    .bind("database", DATABASE_NAME)
                    .bind("table", table);
            return Flux.from(statement.execute()).flatMap(result -> result.map((row, _) -> new ColumnStat(
                    row.get("name", String.class),
                    row.get("compressed", Long.class),
                    row.get("uncompressed", Long.class))));
        }).collectList().block();
    }

    private Map<String, String> fetchColumnCodecs(String table) {
        var codecs = new LinkedHashMap<String, String>();
        transactionTemplateAsync.stream(connection -> {
            var statement = connection.createStatement("""
                    SELECT
                        name,
                        compression_codec AS codec
                    FROM system.columns
                    WHERE database = :database
                    AND table = :table
                    ORDER BY position
                    """)
                    .bind("database", DATABASE_NAME)
                    .bind("table", table);
            return Flux.from(statement.execute()).flatMap(result -> result.map(
                    (row, _) -> Map.entry(row.get("name", String.class), row.get("codec", String.class))));
        }).toIterable().forEach(entry -> codecs.put(entry.getKey(), entry.getValue()));
        return codecs;
    }

    private ScanCost measureScan(String column) {
        var marker = "codecbench-%s-%s".formatted(column, UUID.randomUUID());
        // sum(cityHash64(...)) forces the column to be read and decompressed for any type; single-threaded so the cost
        // is comparable across codecs. Warm up once first so the measured pass reflects steady-state decode rather than
        // first-touch overhead (which otherwise dominates on these tiny columns).
        execute("SELECT sum(cityHash64(%s)) FROM %s.%s SETTINGS max_threads = 1"
                .formatted(column, DATABASE_NAME, TABLE));
        execute("SELECT sum(cityHash64(%s)) FROM %s.%s SETTINGS log_comment = '%s', max_threads = 1"
                .formatted(column, DATABASE_NAME, TABLE, marker));
        execute("SYSTEM FLUSH LOGS");
        return transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT
                        query_duration_ms AS duration_ms,
                        ProfileEvents['OSCPUVirtualTimeMicroseconds'] AS cpu_us,
                        read_bytes AS read_bytes
                    FROM system.query_log
                    WHERE log_comment = :marker
                    AND type = 'QueryFinish'
                    ORDER BY event_time_microseconds DESC
                    LIMIT 1
                    """).bind("marker", marker);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, _) -> new ScanCost(
                            row.get("duration_ms", Long.class),
                            row.get("cpu_us", Long.class),
                            row.get("read_bytes", Long.class)))));
        }).block();
    }

    private void execute(String sql) {
        transactionTemplateAsync.nonTransaction(connection -> Mono.from(connection.createStatement(sql).execute()))
                .block();
    }

    private long compressedBytesOf(String table, String column) {
        return queryLong(("SELECT data_compressed_bytes FROM system.columns "
                + "WHERE database = '%s' AND table = '%s' AND name = '%s'").formatted(DATABASE_NAME, table, column));
    }

    private long totalCompressedOf(String table) {
        return queryLong(("SELECT sum(data_compressed_bytes) FROM system.parts "
                + "WHERE database = '%s' AND table = '%s' AND active").formatted(DATABASE_NAME, table));
    }

    private long marksOf(String table) {
        return queryLong(("SELECT sum(marks) FROM system.parts WHERE database = '%s' AND table = '%s' AND active")
                .formatted(DATABASE_NAME, table));
    }

    private long queryLong(String sql) {
        return transactionTemplateAsync
                .nonTransaction(connection -> Mono.from(connection.createStatement(sql).execute())
                        .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, Long.class)))))
                .block();
    }

    private String queryString(String sql) {
        return transactionTemplateAsync
                .nonTransaction(connection -> Mono.from(connection.createStatement(sql).execute())
                        .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class)))))
                .block();
    }

    /**
     * The codec class a column is expected to carry, matched against {@code system.columns.compression_codec}. Delta and
     * T64 are matched by token (the reported form includes an auto-detected byte width, e.g. {@code Delta(8)}), with
     * {@code DoubleDelta} explicitly excluded from the Delta match since it also contains the substring; the plain levels
     * match exactly; the server default reports an empty string.
     */
    private enum ExpectedCodec {
        ZSTD1,
        ZSTD3,
        DELTA_ZSTD1,
        T64_ZSTD1,
        SERVER_DEFAULT;

        boolean matches(String codec) {
            return switch (this) {
                case ZSTD1 -> codec.equals("CODEC(ZSTD(1))");
                case ZSTD3 -> codec.equals("CODEC(ZSTD(3))");
                case DELTA_ZSTD1 -> codec.contains("Delta") && !codec.contains("DoubleDelta")
                        && codec.contains("ZSTD(1)");
                case T64_ZSTD1 -> codec.contains("T64") && codec.contains("ZSTD(1)");
                case SERVER_DEFAULT -> codec.isEmpty();
            };
        }
    }

    private record ColumnStat(String name, long compressedBytes, long uncompressedBytes) {
        double ratio() {
            return compressedBytes == 0 ? 0 : (double) uncompressedBytes / compressedBytes;
        }
    }

    private record ScanCost(long durationMs, long cpuMicros, long readBytes) {
        @Override
        public String toString() {
            return "%d ms / %,d cpu-us / %,d read-bytes".formatted(durationMs, cpuMicros, readBytes);
        }
    }
}
