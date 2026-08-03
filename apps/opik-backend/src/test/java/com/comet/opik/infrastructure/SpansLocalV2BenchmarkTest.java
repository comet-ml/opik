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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-column compression benchmark and DateTime64 precision check for the {@code spans_local_v2} table (OPIK-7400).
 *
 * <p>The spans counterpart of {@code TracesLocalV2BenchmarkTest} (OPIK-6899). Because migration {@code 000114} already
 * ships the codec set that benchmark settled on for traces, this is confirm-and-settle rather than discover: the shared
 * columns are re-confirmed to inherit correctly, and the work concentrates on the columns that have no traces evidence —
 * {@code usage} (Map), {@code total_estimated_cost} (+{@code _version}), {@code model}, {@code provider}, {@code type},
 * {@code trace_id} and {@code parent_span_id}.
 *
 * <p>Two complementary checks. {@link #everySpansLocalV2ColumnUsesItsIntendedCodec()} reads {@code system.columns} for
 * the live {@code spans_local_v2} and pins each of its 37 columns to the codec its type warrants — a drift guard that
 * fails if a column's codec changes or a new column lands unclassified. The remaining tests store one identical
 * synthetic slice under the competing codecs as side-by-side columns of a scratch table and read the compressed sizes
 * back from {@code system.columns}, so a size difference is attributable to the codec alone. They also confirm the
 * {@code DateTime64(9) -> DateTime64(6)} conversion truncates rather than rounds, record long-text decompression cost,
 * and measure the whole-row storage of the old vs new table format.
 *
 * <p>The synthetic slice matches the production {@code spans} shapes measured read-only against prod (1.26 B rows,
 * 19.25 TiB, ClickHouse 26.3.16) and the spans {@code (workspace_id, project_id, trace_id, id)} sort order, since both
 * drive compression:
 * <ul>
 *   <li>{@code id} is a unique UUIDv7 per row, monotonic over ~a week — the sorted order a real part stores it in;</li>
 *   <li>{@code trace_id} is a UUIDv7 clustered into short runs of ~5 spans (the third sort key; prod averages ~4.5
 *       spans per trace), {@code project_id} a UUIDv7 in ~100-row runs, {@code workspace_id} a UUIDv4 in ~1000-row
 *       runs — the trace-level run structure is what separates spans from traces here;</li>
 *   <li>{@code parent_span_id} is out of the sort key on this table: the first span of each trace carries the empty
 *       sentinel (stored as 36 NUL bytes) and the rest point at one of two ancestors, matching the ~1.7 distinct
 *       parents per trace measured for the 000114 DDL;</li>
 *   <li>{@code type} drives most of the spans-only columns: prod is 62% {@code general} / 28% {@code llm} / 9%
 *       {@code tool}, and {@code usage}, {@code model}, {@code provider} and {@code total_estimated_cost} are populated
 *       only on {@code llm} spans — hence their ~75% empty / ~79% zero prod fractions, reproduced here through that
 *       coupling rather than by drawing each independently;</li>
 *   <li>the text columns follow the prod size skew (median {@code input} 807 B, mean 30 KB), scaled down so the slice
 *       stays a unit test: most spans are short, a tenth are mid-sized, ~1% are large;</li>
 *   <li>of the timestamps only {@code start_time}, {@code created_at} and {@code id_at} are monotonic in storage order;
 *       {@code end_time} (start + duration) and {@code last_updated_at} (the ReplacingMergeTree version column's
 *       last-write value) are not, and are modeled as scrambled — the same split
 *       {@code TracesLocalV2BenchmarkTest} documents against {@code TraceDAO}'s upsert coalesce.</li>
 * </ul>
 *
 * <p>Where a conclusion turns on the payload's real distribution rather than on its shape, this synthetic slice cannot
 * settle it, and the tests below say so explicitly. Two such conclusions were <em>reversed</em> by the real-data pass
 * (OPIK-7400 stage b, 115,925 real production spans): {@code T64} on the {@code *_length} counters, and the storage
 * effect of the {@code Nullable} to sentinel change. Both are documented in place, so that nobody re-decides a shipped
 * codec on synthetic evidence alone. The refinements the real-data pass did adopt are migration {@code 000115}.
 * Rows are inserted in {@code (workspace_id, project_id, trace_id, id)} order, so the clustered keys form the same runs
 * a real part has. Data is deterministic (hash-derived, single-threaded insert), so runs are byte-identical and the test
 * doubles as a regression guard. Absolute byte counts hold only for this slice; the validated conclusions are the
 * relative codec orderings. Runs directly against ClickHouse via {@link TransactionTemplateAsync} (no Dropwizard app),
 * mirroring {@code SpansLocalV2TableTest}.
 *
 * <p>Two invariance results are not re-derived here: compression is independent of {@code index_granularity_bytes} and
 * of weekly partitioning. Both were measured generically (on a plain wide-row table, not on anything traces-specific)
 * by {@code TracesLocalV2BenchmarkTest#compressionIsInvariantToIndexGranularityBytes} and
 * {@code #compressionIsInvariantToWeeklyPartitioning}, and {@code spans_local_v2} uses the same 40 MiB byte-cap and the
 * same {@code toMonday(id_at)} partitioning, so the sizes below transfer unchanged.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpansLocalV2BenchmarkTest {

    private static final String TABLE = "opik_7400_benchmark";
    private static final int ROW_COUNT = 20_000;
    /** Spans per trace: prod averages ~4.5, so each synthetic trace holds a fixed 5 spans. */
    private static final int SPANS_PER_TRACE = 5;

    /**
     * Upper bound for the codec-competitiveness assertions: the shipped codec's compressed size may exceed the codec it
     * is compared against by at most this factor before the gap counts as material. The magnitude is a per-column
     * judgment call; the levels are named so repeated thresholds move together and the intent is explicit. See each
     * test's comment for which codecs it compares and why.
     */
    private static final double WITHIN_2_PCT = 1.02;
    /** As {@link #WITHIN_2_PCT}, at 5%. */
    private static final double WITHIN_5_PCT = 1.05;
    /** As {@link #WITHIN_2_PCT}, at 10%. */
    private static final double WITHIN_10_PCT = 1.10;

    /**
     * Long-text body for {@code input} / {@code output}: a 23-word vocabulary joined into sentences of a caller-supplied
     * length, the repetitive natural-language shape that separates ZSTD(3) from ZSTD(1) and LZ4 the way real chat
     * payloads do. Injected verbatim via {@code replace()}, so it carries a single {@code %} modulo operator like the
     * rest of the INSERT template.
     */
    private static final String SENTENCE_EXPR = """
            arrayStringConcat(
                arrayMap(i -> ['the','user','asked','the','assistant','about','weather','and','requested','a',
                               'concise','summary','of','the','document','with','several','key','points','listed',
                               'in','order','please'][(cityHash64(number, i) % 23) + 1], range({words})),
                ' ')
            """;

    /**
     * Builds a UUIDv7 string from a millisecond expression and a seed expression: the 48-bit timestamp prefix followed
     * by a hash-derived random tail, i.e. the layout the ids in prod carry. Injected with different {@code {ms}} /
     * {@code {seed}} pairs to mint the row's own {@code id}, its {@code trace_id}, and the ids of the ancestors
     * {@code parent_span_id} points at.
     */
    private static final String UUID_V7_EXPR = """
            lower(concat(
                substring(hex({ms}), 5, 8), '-',
                substring(hex({ms}), 13, 4), '-7',
                substring(hex(cityHash64({seed}, 'ua')), 1, 3), '-',
                ['8', '9', 'a', 'b'][(cityHash64({seed}, 'uv') % 4) + 1],
                substring(hex(cityHash64({seed}, 'ua')), 4, 3), '-',
                substring(hex(cityHash64({seed}, 'ub')), 1, 12)))
            """;

    /**
     * The {@code usage} keys of an OpenAI-shaped LLM span, in the order prod reports them. The first three are the
     * normalized counts every {@code llm} span carries; the {@code original_usage.*} entries are the provider's raw
     * payload flattened, which duplicates the same numbers under longer dotted names — that duplication is real, and it
     * is a large part of why the column compresses 24x in prod.
     */
    private static final String USAGE_KEYS_FULL = """
            ['prompt_tokens','completion_tokens','total_tokens',
             'original_usage.prompt_tokens','original_usage.total_tokens','original_usage.completion_tokens',
             'original_usage.completion_tokens_details.reasoning_tokens',
             'original_usage.prompt_tokens_details.text_tokens',
             'original_usage.prompt_tokens_details.cached_tokens',
             'original_usage.prompt_tokens_details.audio_tokens',
             'original_usage.completion_tokens_details.accepted_prediction_tokens',
             'original_usage.completion_tokens_details.rejected_prediction_tokens']
            """;

    /** The three normalized {@code usage} keys, the set the ~40% of LLM spans without a raw provider payload carry. */
    private static final String USAGE_KEYS_BASIC = "['prompt_tokens','completion_tokens','total_tokens']";

    /**
     * The intended codec for every {@code spans_local_v2} column, keyed by column name. This is the canonical record of
     * the per-field decisions the benchmarks below justify; {@link #everySpansLocalV2ColumnUsesItsIntendedCodec()}
     * asserts the live DDL matches it, column for column. It reflects the codecs the live table ships after migrations
     * {@code 000114} (create) and {@code 000115} (which applies the refinements the real-data pass identified), so it
     * moves in lockstep with those migrations — the pin test fails loudly if the DDL and this map ever drift apart.
     */
    private static final Map<String, ExpectedCodec> SPANS_LOCAL_V2_CODECS = Map.ofEntries(
            Map.entry("id", ExpectedCodec.ZSTD1),
            Map.entry("workspace_id", ExpectedCodec.ZSTD3),
            Map.entry("project_id", ExpectedCodec.ZSTD1),
            Map.entry("trace_id", ExpectedCodec.ZSTD1),
            Map.entry("parent_span_id", ExpectedCodec.ZSTD1),
            Map.entry("name", ExpectedCodec.ZSTD3),
            Map.entry("type", ExpectedCodec.ZSTD1),
            Map.entry("start_time", ExpectedCodec.ZSTD1),
            Map.entry("end_time", ExpectedCodec.DELTA_ZSTD1),
            Map.entry("input", ExpectedCodec.ZSTD3),
            Map.entry("output", ExpectedCodec.ZSTD3),
            Map.entry("metadata", ExpectedCodec.ZSTD3),
            Map.entry("tags", ExpectedCodec.ZSTD3),
            Map.entry("usage", ExpectedCodec.ZSTD3),
            Map.entry("created_at", ExpectedCodec.ZSTD1),
            Map.entry("last_updated_at", ExpectedCodec.DELTA_ZSTD1),
            Map.entry("created_by", ExpectedCodec.ZSTD3),
            Map.entry("last_updated_by", ExpectedCodec.ZSTD3),
            Map.entry("model", ExpectedCodec.ZSTD1),
            Map.entry("provider", ExpectedCodec.ZSTD1),
            Map.entry("total_estimated_cost", ExpectedCodec.ZSTD1),
            Map.entry("total_estimated_cost_version", ExpectedCodec.ZSTD1),
            Map.entry("error_info", ExpectedCodec.ZSTD3),
            Map.entry("truncation_threshold", ExpectedCodec.ZSTD1),
            Map.entry("input_slim", ExpectedCodec.ZSTD3),
            Map.entry("output_slim", ExpectedCodec.ZSTD3),
            Map.entry("ttft", ExpectedCodec.ZSTD1),
            Map.entry("source", ExpectedCodec.ZSTD1),
            Map.entry("environment", ExpectedCodec.ZSTD1),
            Map.entry("is_deleted", ExpectedCodec.SERVER_DEFAULT),
            Map.entry("input_length", ExpectedCodec.T64_ZSTD1),
            Map.entry("output_length", ExpectedCodec.T64_ZSTD1),
            Map.entry("metadata_length", ExpectedCodec.T64_ZSTD1),
            Map.entry("truncated_input", ExpectedCodec.ZSTD3),
            Map.entry("truncated_output", ExpectedCodec.ZSTD3),
            Map.entry("duration", ExpectedCodec.ZSTD1),
            Map.entry("id_at", ExpectedCodec.ZSTD1));

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
        // so a size difference in system.columns is attributable to the codec alone. The *_str_* / *_i32_* / *_lc_*
        // variants additionally hold the same data under the type the live `spans` table uses, which is how the
        // FixedString-vs-String, Int64-vs-Int32 and LowCardinality-vs-String questions are answered.
        execute("""
                CREATE TABLE %s.%s
                (
                    id_lz4            FixedString(36)          CODEC(LZ4),
                    id_zstd1          FixedString(36)          CODEC(ZSTD(1)),
                    id_zstd3          FixedString(36)          CODEC(ZSTD(3)),
                    proj_lz4          FixedString(36)          CODEC(LZ4),
                    proj_zstd1        FixedString(36)          CODEC(ZSTD(1)),
                    proj_zstd3        FixedString(36)          CODEC(ZSTD(3)),
                    ws_lz4            String                   CODEC(LZ4),
                    ws_zstd1          String                   CODEC(ZSTD(1)),
                    ws_zstd3          String                   CODEC(ZSTD(3)),
                    tid_lz4           FixedString(36)          CODEC(LZ4),
                    tid_zstd1         FixedString(36)          CODEC(ZSTD(1)),
                    tid_zstd3         FixedString(36)          CODEC(ZSTD(3)),
                    psid_lz4          FixedString(36)          CODEC(LZ4),
                    psid_zstd1        FixedString(36)          CODEC(ZSTD(1)),
                    psid_zstd3        FixedString(36)          CODEC(ZSTD(3)),
                    psid_str_lz4      String                   CODEC(LZ4),
                    psid_str_zstd1    String                   CODEC(ZSTD(1)),
                    psid_str_zstd3    String                   CODEC(ZSTD(3)),
                    name_lz4          String                   CODEC(LZ4),
                    name_zstd1        String                   CODEC(ZSTD(1)),
                    name_zstd3        String                   CODEC(ZSTD(3)),
                    text_lz4          String                   CODEC(LZ4),
                    text_zstd1        String                   CODEC(ZSTD(1)),
                    text_zstd3        String                   CODEC(ZSTD(3)),
                    meta_lz4          String                   CODEC(LZ4),
                    meta_zstd1        String                   CODEC(ZSTD(1)),
                    meta_zstd3        String                   CODEC(ZSTD(3)),
                    slim_zstd1        String                   CODEC(ZSTD(1)),
                    slim_zstd3        String                   CODEC(ZSTD(3)),
                    usage_lz4         Map(String, Int64)       CODEC(LZ4),
                    usage_zstd1       Map(String, Int64)       CODEC(ZSTD(1)),
                    usage_zstd3       Map(String, Int64)       CODEC(ZSTD(3)),
                    usage_i32_lz4     Map(String, Int32)       CODEC(LZ4),
                    usage_i32_zstd1   Map(String, Int32)       CODEC(ZSTD(1)),
                    usage_i32_zstd3   Map(String, Int32)       CODEC(ZSTD(3)),
                    cost_lz4          Decimal(38, 12)          CODEC(LZ4),
                    cost_zstd1        Decimal(38, 12)          CODEC(ZSTD(1)),
                    cost_zstd3        Decimal(38, 12)          CODEC(ZSTD(3)),
                    model_str_lz4     String                   CODEC(LZ4),
                    model_str_zstd1   String                   CODEC(ZSTD(1)),
                    model_str_zstd3   String                   CODEC(ZSTD(3)),
                    model_lc_default  LowCardinality(String),
                    model_lc_zstd1    LowCardinality(String)   CODEC(ZSTD(1)),
                    model_lc_zstd3    LowCardinality(String)   CODEC(ZSTD(3)),
                    prov_str_zstd1    String                   CODEC(ZSTD(1)),
                    prov_str_zstd3    String                   CODEC(ZSTD(3)),
                    prov_lc_default   LowCardinality(String),
                    prov_lc_zstd1     LowCardinality(String)   CODEC(ZSTD(1)),
                    prov_lc_zstd3     LowCardinality(String)   CODEC(ZSTD(3)),
                    costver_str_zstd1 String                   CODEC(ZSTD(1)),
                    costver_lc_default LowCardinality(String),
                    costver_lc_zstd1  LowCardinality(String)   CODEC(ZSTD(1)),
                    costver_lc_zstd3  LowCardinality(String)   CODEC(ZSTD(3)),
                    type_default      Enum8('unknown' = 0, 'general' = 1, 'tool' = 2, 'llm' = 3, 'guardrail' = 4),
                    type_zstd1        Enum8('unknown' = 0, 'general' = 1, 'tool' = 2, 'llm' = 3, 'guardrail' = 4) CODEC(ZSTD(1)),
                    type_zstd3        Enum8('unknown' = 0, 'general' = 1, 'tool' = 2, 'llm' = 3, 'guardrail' = 4) CODEC(ZSTD(3)),
                    src_default       Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5),
                    src_zstd1         Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) CODEC(ZSTD(1)),
                    env_str_zstd1     String                   CODEC(ZSTD(1)),
                    env_lc_default    LowCardinality(String),
                    env_lc_zstd1      LowCardinality(String)   CODEC(ZSTD(1)),
                    ts6_lz4           DateTime64(6, 'UTC')     CODEC(LZ4),
                    ts6_zstd1         DateTime64(6, 'UTC')     CODEC(ZSTD(1)),
                    ts6_delta_zstd1   DateTime64(6, 'UTC')     CODEC(Delta, ZSTD(1)),
                    ts6_dd_zstd1      DateTime64(6, 'UTC')     CODEC(DoubleDelta, ZSTD(1)),
                    ts9_delta_zstd1   DateTime64(9, 'UTC')     CODEC(Delta, ZSTD(1)),
                    et_lz4            DateTime64(6, 'UTC')     CODEC(LZ4),
                    et_zstd1          DateTime64(6, 'UTC')     CODEC(ZSTD(1)),
                    et_delta_zstd1    DateTime64(6, 'UTC')     CODEC(Delta, ZSTD(1)),
                    lua_lz4           DateTime64(6, 'UTC')     CODEC(LZ4),
                    lua_zstd1         DateTime64(6, 'UTC')     CODEC(ZSTD(1)),
                    lua_delta_zstd1   DateTime64(6, 'UTC')     CODEC(Delta, ZSTD(1)),
                    idat_lz4          DateTime64(0, 'UTC')     CODEC(LZ4),
                    idat_zstd1        DateTime64(0, 'UTC')     CODEC(ZSTD(1)),
                    idat_delta_zstd1  DateTime64(0, 'UTC')     CODEC(Delta, ZSTD(1)),
                    idat_dd_zstd1     DateTime64(0, 'UTC')     CODEC(DoubleDelta, ZSTD(1)),
                    cnt_zstd1         UInt64                   CODEC(ZSTD(1)),
                    cnt_t64_zstd1     UInt64                   CODEC(T64, ZSTD(1)),
                    cnt_dd_zstd1      UInt64                   CODEC(DoubleDelta, ZSTD(1)),
                    cntn_zstd1        UInt64                   CODEC(ZSTD(1)),
                    cntn_t64_zstd1    UInt64                   CODEC(T64, ZSTD(1)),
                    tt_lz4            UInt64                   CODEC(LZ4),
                    tt_zstd1          UInt64                   CODEC(ZSTD(1)),
                    dur_lz4           Float64                  CODEC(LZ4),
                    dur_zstd1         Float64                  CODEC(ZSTD(1)),
                    dur_gorilla       Float64                  CODEC(Gorilla),
                    ttft_lz4          Float64                  CODEC(LZ4),
                    ttft_zstd1        Float64                  CODEC(ZSTD(1)),
                    ttft_gorilla      Float64                  CODEC(Gorilla),
                    arr_lz4           Array(String)            CODEC(LZ4),
                    arr_zstd1         Array(String)            CODEC(ZSTD(1)),
                    arr_zstd3         Array(String)            CODEC(ZSTD(3)),
                    cb_lz4            String                   CODEC(LZ4),
                    cb_zstd1          String                   CODEC(ZSTD(1)),
                    cb_zstd3          String                   CODEC(ZSTD(3)),
                    err_lz4           String                   CODEC(LZ4),
                    err_zstd1         String                   CODEC(ZSTD(1)),
                    err_zstd3         String                   CODEC(ZSTD(3)),
                    flag_default      UInt8,
                    flag_zstd1        UInt8                    CODEC(ZSTD(1)),
                    -- Nullable counterparts of the sentinel columns (same data, same codec) to measure the Nullable ->
                    -- sentinel storage change: the sentinel form above (et_zstd1/ttft_zstd1/dur_zstd1) vs these
                    -- Nullable forms, which additionally store a null-mask stream.
                    et_nullable_zstd1   Nullable(DateTime64(6, 'UTC')) CODEC(ZSTD(1)),
                    ttft_nullable_zstd1 Nullable(Float64)              CODEC(ZSTD(1)),
                    dur_nullable_zstd1  Nullable(Float64)              CODEC(ZSTD(1))
                )
                ENGINE = MergeTree ORDER BY tuple()
                """
                .formatted(DATABASE_NAME, TABLE));

        // Templated with replace() rather than formatted() so the SQL can use the ClickHouse '%' modulo operator freely.
        // Rows are generated in (workspace_id, project_id, trace_id, id) order — numbers() order is that order by
        // construction — so the clustered keys and the monotonic id/id_at land the way a real sorted part stores them.
        execute("""
                INSERT INTO {table}
                    (
                        id_lz4, id_zstd1, id_zstd3,
                        proj_lz4, proj_zstd1, proj_zstd3,
                        ws_lz4, ws_zstd1, ws_zstd3,
                        tid_lz4, tid_zstd1, tid_zstd3,
                        psid_lz4, psid_zstd1, psid_zstd3,
                        psid_str_lz4, psid_str_zstd1, psid_str_zstd3,
                        name_lz4, name_zstd1, name_zstd3,
                        text_lz4, text_zstd1, text_zstd3,
                        meta_lz4, meta_zstd1, meta_zstd3,
                        slim_zstd1, slim_zstd3,
                        usage_lz4, usage_zstd1, usage_zstd3,
                        usage_i32_lz4, usage_i32_zstd1, usage_i32_zstd3,
                        cost_lz4, cost_zstd1, cost_zstd3,
                        model_str_lz4, model_str_zstd1, model_str_zstd3,
                        model_lc_default, model_lc_zstd1, model_lc_zstd3,
                        prov_str_zstd1, prov_str_zstd3,
                        prov_lc_default, prov_lc_zstd1, prov_lc_zstd3,
                        costver_str_zstd1, costver_lc_default, costver_lc_zstd1, costver_lc_zstd3,
                        type_default, type_zstd1, type_zstd3,
                        src_default, src_zstd1,
                        env_str_zstd1, env_lc_default, env_lc_zstd1,
                        ts6_lz4, ts6_zstd1, ts6_delta_zstd1, ts6_dd_zstd1, ts9_delta_zstd1,
                        et_lz4, et_zstd1, et_delta_zstd1,
                        lua_lz4, lua_zstd1, lua_delta_zstd1,
                        idat_lz4, idat_zstd1, idat_delta_zstd1, idat_dd_zstd1,
                        cnt_zstd1, cnt_t64_zstd1, cnt_dd_zstd1,
                        cntn_zstd1, cntn_t64_zstd1,
                        tt_lz4, tt_zstd1,
                        dur_lz4, dur_zstd1, dur_gorilla,
                        ttft_lz4, ttft_zstd1, ttft_gorilla,
                        arr_lz4, arr_zstd1, arr_zstd3,
                        cb_lz4, cb_zstd1, cb_zstd3,
                        err_lz4, err_zstd1, err_zstd3,
                        flag_default, flag_zstd1,
                        et_nullable_zstd1, ttft_nullable_zstd1, dur_nullable_zstd1
                    )
                WITH base AS (
                    SELECT
                        -- Deterministic storage order (matches the (workspace_id, project_id, trace_id, id) sort a real
                        -- part has), so compression is reproducible run to run.
                        number AS n,
                        -- workspace_id: UUID v4 (no timestamp prefix), low-cardinality and clustered as the first sort
                        -- key (~20 workspaces of 1000 rows). A rare short-text value mirrors the small share of
                        -- production workspaces that use a name instead of a UUID; it does not move the codec choice.
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
                        toUInt64(toUnixTimestamp64Milli(toDateTime64('2025-06-01 00:00:00', 3, 'UTC'))
                            + p_idx * 3600000) AS p_ms,
                        toFixedString({project_uuid}, 36) AS project_id,
                        -- trace_id: UUID v7, the third sort key, clustered into runs of SPANS_PER_TRACE — the run
                        -- structure that distinguishes spans from traces. Traces advance ~150 s apart here (the span
                        -- step times the spans per trace).
                        intDiv(number, {spans_per_trace}) AS t_idx,
                        toUInt64(toUnixTimestamp64Milli(toDateTime64('2026-01-06 00:00:00', 3, 'UTC'))
                            + t_idx * {spans_per_trace} * 30000) AS t_ms,
                        toFixedString({trace_uuid}, 36) AS trace_id,
                        -- id: UUID v7, unique per row and strictly increasing over ~a week (30 s +- jitter between
                        -- rows; the 30 s step exceeds the jitter so order is preserved), i.e. the sorted order a real
                        -- part stores ids in. The tail is random.
                        toUInt64(toUnixTimestamp64Milli(toDateTime64('2026-01-06 00:00:00', 3, 'UTC'))
                            + number * 30000 + (cityHash64(number, 'ij') % 20000)) AS id_ms,
                        toFixedString({id_uuid}, 36) AS uid,
                        -- parent_span_id: out of the sort key on spans_local_v2. The first span of a trace is the root
                        -- and carries the empty sentinel (FixedString(36) stores it as 36 NUL bytes); the rest point at
                        -- the root or at the trace's second span, giving the ~1.7 distinct parents per trace the 000114
                        -- DDL measured. The ancestor ids are minted with the same UUIDv7 expression at that span's
                        -- row number, so they are the ids those rows actually carry.
                        number % {spans_per_trace} AS span_in_trace,
                        t_idx * {spans_per_trace} AS root_n,
                        toUInt64(toUnixTimestamp64Milli(toDateTime64('2026-01-06 00:00:00', 3, 'UTC'))
                            + root_n * 30000 + (cityHash64(root_n, 'ij') % 20000)) AS root_ms,
                        toUInt64(toUnixTimestamp64Milli(toDateTime64('2026-01-06 00:00:00', 3, 'UTC'))
                            + (root_n + 1) * 30000 + (cityHash64(root_n + 1, 'ij') % 20000)) AS second_ms,
                        multiIf(
                            span_in_trace = 0, '',
                            span_in_trace <= 2, {root_uuid},
                            {second_uuid}) AS parent_span_id,
                        -- type: the prod mix (62% general, 28% llm, 9% tool, 0.6% guardrail, 0.3% unknown). It gates
                        -- the LLM-only columns below, which is where their empty fractions come from.
                        multiIf(
                            cityHash64(number, 'ty') % 10000 < 6210, 'general',
                            cityHash64(number, 'ty') % 10000 < 9005, 'llm',
                            cityHash64(number, 'ty') % 10000 < 9905, 'tool',
                            cityHash64(number, 'ty') % 10000 < 9969, 'guardrail',
                            'unknown') AS span_type,
                        span_type = 'llm' AS is_llm,
                        -- name: short span name, medium cardinality (~750 distinct).
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
                        -- id_at: the event time at second precision, i.e. what UUIDv7ToDateTime(id) yields, derived
                        -- directly from the id's embedded millisecond so it stays monotonic in id.
                        toDateTime64(intDiv(id_ms, 1000), 0, 'UTC') AS idat,
                        -- input / output: prod spans are far more skewed than traces (median input 807 B, mean 30 KB).
                        -- Three tiers reproduce that shape at unit-test scale: ~90% short, ~9% mid, ~1% large.
                        multiIf(
                            cityHash64(number, 'sz') % 1000 < 900, {short_text},
                            cityHash64(number, 'sz') % 1000 < 990, {medium_text},
                            {large_text}) AS body,
                        concat('{"messages":[{"role":"user","content":"', body, '"}]}') AS txt,
                        -- input_slim / truncated_*: JSON derived from input, just size-capped (a leaf-capped or prefix
                        -- form). Same text profile as input, only smaller — represented by a bounded slice of it.
                        substring(txt, 1, 250) AS slim,
                        -- usage: populated only on llm spans, so ~72% of rows hold the empty map, matching the 75-94%
                        -- empty measured per workspace in prod. 60% of llm spans carry the provider's flattened raw
                        -- payload as well as the normalized counts; the rest carry only the three normalized keys.
                        -- Values are token counts of realistic magnitude, and the original_usage.* entries repeat the
                        -- same numbers under longer names exactly as prod does.
                        toInt64(200 + cityHash64(number, 'pt') % 20000) AS prompt_tokens,
                        toInt64(cityHash64(number, 'ct') % 1200) AS completion_tokens,
                        if(cityHash64(number, 'uk') % 100 < 60, {usage_keys_full}, {usage_keys_basic}) AS usage_keys,
                        if(NOT is_llm,
                            CAST(map(), 'Map(String, Int64)'),
                            mapFromArrays(
                                usage_keys,
                                arrayMap(k -> multiIf(
                                    k = 'prompt_tokens', prompt_tokens,
                                    k = 'completion_tokens', completion_tokens,
                                    k = 'total_tokens', prompt_tokens + completion_tokens,
                                    k = 'original_usage.prompt_tokens', prompt_tokens,
                                    k = 'original_usage.completion_tokens', completion_tokens,
                                    k = 'original_usage.total_tokens', prompt_tokens + completion_tokens,
                                    k = 'original_usage.completion_tokens_details.reasoning_tokens',
                                        toInt64(cityHash64(number, 'rt') % 600),
                                    k = 'original_usage.prompt_tokens_details.text_tokens',
                                        toInt64(prompt_tokens - cityHash64(number, 'tk') % 200),
                                    k = 'original_usage.prompt_tokens_details.cached_tokens',
                                        toInt64(cityHash64(number, 'cc') % 8000),
                                    toInt64(0)), usage_keys))) AS usage_map,
                        -- total_estimated_cost: zero on ~79% of prod rows (everything but priced llm spans); when set
                        -- it is a small value that uses the full 12-digit scale, so the non-zero values are
                        -- high-entropy. Built from a hash as an exact decimal string to stay deterministic.
                        if(NOT is_llm OR cityHash64(number, 'cz') % 100 < 25,
                            toDecimal128('0', 12),
                            toDecimal128(concat('0.',
                                leftPad(toString(cityHash64(number, 'cost') % 1000000000000), 12, '0')), 12)) AS cost,
                        -- model / provider: set only on llm spans (~75% empty in prod). Head-heavy with a long tail —
                        -- the top names cover most rows and a hash suffix mints the tail. The slice cannot reproduce
                        -- prod's 6,711 distinct models in 5,600 llm rows; what it reproduces is the head-plus-tail
                        -- shape, and the LowCardinality question is re-checked against real data.
                        if(NOT is_llm, '',
                            if(cityHash64(number, 'md') % 100 < 72,
                                ['gpt-4o-mini-2024-07-18', 'gemini-2.5-flash', 'gpt-5-mini-2025-08-07',
                                 'gpt-4.1-mini-2025-04-14', 'gpt-4o-2024-08-06', 'google/gemini-2.5-flash',
                                 'gemini-3-flash-preview', 'gpt-4.1-nano-2025-04-14', 'gpt-4o-mini',
                                 'gemini-3.1-flash-lite', 'gpt-4o', 'gpt-4.1-2025-04-14', 'claude-sonnet-4-6',
                                 'openai/gpt-5.4-nano-20260317', 'gpt-5.1-2025-11-13', 'gpt-5-2025-08-07',
                                 'gpt-5.4-2026-03-05', 'llama-3.3-70b', 'gpt-5.5-2026-04-23',
                                 'openai/gpt-5.4-nano'][(cityHash64(number, 'mh') % 20) + 1],
                                concat('ft:custom-model-', toString(cityHash64(number, 'mt') % 700)))) AS model,
                        if(NOT is_llm, '',
                            ['openai', 'google_ai', 'openrouter', 'google_vertexai', 'anthropic', 'gemini', 'bedrock',
                             'api.cerebras.ai', 'azure', 'tongyi', 'vertex_ai',
                             'litellm_proxy'][(cityHash64(number, 'pv') % 12) + 1]) AS provider,
                        -- total_estimated_cost_version: three distinct values in prod ('' on 82%, then 1.1 and 1.0).
                        multiIf(cost = 0, '', cityHash64(number, 'cv') % 100 < 96, '1.1', '1.0') AS cost_version,
                        -- source: the prod mix (74% unknown, 23% sdk, 2.5% experiment, remainder split).
                        multiIf(
                            cityHash64(number, 'sr') % 1000 < 743, 'unknown',
                            cityHash64(number, 'sr') % 1000 < 971, 'sdk',
                            cityHash64(number, 'sr') % 1000 < 996, 'experiment',
                            cityHash64(number, 'sr') % 1000 < 998, 'evaluator',
                            'optimization') AS source,
                        -- environment: empty on 99.7% of prod rows; the populated tail is a handful of names.
                        if(cityHash64(number, 'ev') % 1000 < 997, '',
                            ['production', 'dev', 'local', 'qa', 'development',
                             'prod'][(cityHash64(number, 'eh') % 6) + 1]) AS environment,
                        -- The *_length materialized columns, i.e. the real text lengths: they inherit the payload's
                        -- skew and so span orders of magnitude (prod input_length runs from 0 to 678 MB with a median
                        -- of 807 B). Kept next to a deliberately narrow counter below, since the two behave differently
                        -- under T64.
                        toUInt64(length(txt)) AS cnt,
                        -- A narrow counter (a ~4000-wide band), the profile TracesLocalV2BenchmarkTest used for its
                        -- counter family. Included so the two ranges can be compared side by side and the T64 result
                        -- is attributable to the value range rather than to anything spans-specific.
                        toUInt64(500 + (cityHash64(number, 'cntn') % 4000)) AS cnt_narrow,
                        -- truncation_threshold: the DDL default (10001) on 99.5% of rows, occasionally lowered.
                        if(cityHash64(number, 'tt') % 1000 < 995,
                            toUInt64(10001),
                            toUInt64(cityHash64(number, 'ttv') % 10001)) AS threshold,
                        -- Two float profiles, both independent across rows: duration is mostly populated (end_time is
                        -- normally set — prod's duration column compresses 2.07x), ttft is nearly always absent (only
                        -- streaming LLM spans set it; prod's ttft compresses 225x).
                        if(cityHash64(number, 'durnan') % 100 < 5,
                            toFloat64('nan'),
                            round((cityHash64(number, 'dur') % 10000000) / 1000.0, 3)) AS dur,
                        if(NOT is_llm OR cityHash64(number, 'ttftnan') % 100 < 70,
                            toFloat64('nan'),
                            round(10 + (cityHash64(number, 'tt2') % 2000000) / 1000.0, 3)) AS ttft,
                        -- tags: 0-3 short values from a realistic mix of framework names and custom labels; most spans
                        -- have none (prod's tags column compresses 24x).
                        arrayMap(i -> ['Langchain', 'production', 'v2', 'experiment', 'rag', 'openai', 'staging',
                            'agent', 'baseline', 'llm'][(cityHash64(number, i) % 10) + 1],
                            range(if(cityHash64(number, 'ntag') % 100 < 80, 0,
                                cityHash64(number, 'ntag2') % 4))) AS tags,
                        -- created_by / last_updated_by: the acting user, repeated in long runs within a workspace
                        -- (prod compresses these ~198x).
                        concat('user-', toString(cityHash64(w_idx, 'cb') % 5)) AS created_by,
                        -- end_time: start_time + a variable duration for 97% of rows; the epoch sentinel for the ~3%
                        -- still running or missing the final upsert. Because a span's duration can far exceed the
                        -- inter-row start gap, end_time is NOT monotonic in storage order (unlike start_time), and the
                        -- epoch rows add outliers - both defeat Delta.
                        if(cityHash64(number, 'etnan') % 100 < 3,
                            toDateTime64('1970-01-01 00:00:00', 6, 'UTC'),
                            t6 + toIntervalMillisecond(cityHash64(number, 'etdur') % 30000)) AS et,
                        -- last_updated_at: the ReplacingMergeTree version column = the last write time. ~70% of spans
                        -- are created then finalized, so their last write is ~ start + duration (scrambled in storage
                        -- order like end_time); ~30% are single-write (= creation, monotonic). No epoch sentinel.
                        if(cityHash64(number, 'luaupd') % 100 < 70,
                            t6 + toIntervalMillisecond(cityHash64(number, 'luad') % 30000),
                            t6) AS lua,
                        -- metadata: a JSON key-value document (not the large LLM input/output; no slim/truncated
                        -- variants), with a mix of structured fields and a short free-text note so it is not
                        -- pathologically uniform.
                        concat('{"environment":"', environment,
                            '","sdk_version":"1.', toString(cityHash64(number, 'mv') % 40),
                            '.0","user_tier":"', ['free', 'pro', 'enterprise'][(cityHash64(number, 'mt') % 3) + 1],
                            '","session":"', substring(lower(hex(cityHash64(number, 'ms'))), 1, 12),
                            '","note":"', arrayStringConcat(arrayMap(i -> ['user', 'api', 'batch', 'retry', 'cache',
                                'async', 'sync', 'job', 'queue', 'worker'][(cityHash64(number, i, 'mn') % 10) + 1],
                                range(6)), ' '),
                            '","retries":', toString(cityHash64(number, 'mr') % 4), '}') AS meta,
                        -- error_info: empty for ~90% (successful spans); otherwise a JSON stack trace whose traceback
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
                        -- UInt8 flag standing in for is_deleted: 98% zeros, as live rows dominate.
                        toUInt8(if(cityHash64(number, 'flag') % 100 < 98, 0, 1)) AS flag,
                        -- Nullable projections of the sentinel columns: absent (epoch / NaN) maps back to NULL, so the
                        -- Nullable columns hold the same logical data, differing only by the null-mask stream.
                        if(et = toDateTime64('1970-01-01 00:00:00', 6, 'UTC'), NULL, et) AS et_n,
                        if(isNaN(ttft), NULL, ttft) AS ttft_n,
                        if(isNaN(dur), NULL, dur) AS dur_n
                    FROM numbers({rows})
                )
                SELECT
                    uid, uid, uid,
                    project_id, project_id, project_id,
                    workspace_id, workspace_id, workspace_id,
                    trace_id, trace_id, trace_id,
                    toFixedString(parent_span_id, 36), toFixedString(parent_span_id, 36),
                    toFixedString(parent_span_id, 36),
                    parent_span_id, parent_span_id, parent_span_id,
                    name, name, name,
                    txt, txt, txt,
                    meta, meta, meta,
                    slim, slim,
                    usage_map, usage_map, usage_map,
                    CAST(usage_map, 'Map(String, Int32)'), CAST(usage_map, 'Map(String, Int32)'),
                    CAST(usage_map, 'Map(String, Int32)'),
                    cost, cost, cost,
                    model, model, model,
                    model, model, model,
                    provider, provider,
                    provider, provider, provider,
                    cost_version, cost_version, cost_version, cost_version,
                    span_type, span_type, span_type,
                    source, source,
                    environment, environment, environment,
                    t6, t6, t6, t6, t9,
                    et, et, et,
                    lua, lua, lua,
                    idat, idat, idat, idat,
                    cnt, cnt, cnt,
                    cnt_narrow, cnt_narrow,
                    threshold, threshold,
                    dur, dur, dur,
                    ttft, ttft, ttft,
                    tags, tags, tags,
                    created_by, created_by, created_by,
                    err, err, err,
                    flag, flag,
                    et_n, ttft_n, dur_n
                FROM base
                ORDER BY n
                SETTINGS max_insert_threads = 1, max_threads = 1
                """
                .replace("{table}", DATABASE_NAME + "." + TABLE)
                .replace("{project_uuid}", uuidV7("p_ms", "p_idx"))
                .replace("{trace_uuid}", uuidV7("t_ms", "t_idx"))
                .replace("{id_uuid}", uuidV7("id_ms", "number"))
                .replace("{root_uuid}", uuidV7("root_ms", "root_n"))
                .replace("{second_uuid}", uuidV7("second_ms", "root_n + 1"))
                .replace("{short_text}", sentence(60))
                .replace("{medium_text}", sentence(600))
                .replace("{large_text}", sentence(3000))
                .replace("{usage_keys_full}", USAGE_KEYS_FULL)
                .replace("{usage_keys_basic}", USAGE_KEYS_BASIC)
                .replace("{spans_per_trace}", String.valueOf(SPANS_PER_TRACE))
                .replace("{rows}", String.valueOf(ROW_COUNT)));

        // Merge to a single part so system.columns reports one clean compressed size per column.
        execute("OPTIMIZE TABLE %s.%s FINAL".formatted(DATABASE_NAME, TABLE));

        long rows = queryLong("SELECT count() FROM %s.%s".formatted(DATABASE_NAME, TABLE));
        assertThat(rows).isEqualTo(ROW_COUNT);

        fetchColumnStats(TABLE).forEach(stat -> columnStats.put(stat.name(), stat));
        logReport();
    }

    @Test
    void everySpansLocalV2ColumnUsesItsIntendedCodec() {
        var actualCodecs = fetchColumnCodecs("spans_local_v2");

        // The live DDL must expose exactly the columns we classified: an unclassified new column forces re-validation.
        assertThat(actualCodecs.keySet()).isEqualTo(SPANS_LOCAL_V2_CODECS.keySet());

        actualCodecs.forEach((column, codec) -> {
            var expected = SPANS_LOCAL_V2_CODECS.get(column);
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
        // does not improve on the tail — so ZSTD(1) is the right level, inheriting the traces result. The LZ4 margin is
        // data-dependent and only logged.
        assertThat(zstd1).isLessThan(uncompressed);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(zstd3 * WITHIN_5_PCT));
        log.info("[OPIK-7400] unique id (UUIDv7) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    @Test
    void clusteredUuidV7ProjectIdColumnCompressesBestWithZstd1() {
        long uncompressed = uncompressed("proj_zstd1");
        long lz4 = compressed("proj_lz4");
        long zstd1 = compressed("proj_zstd1");
        long zstd3 = compressed("proj_zstd3");

        // project_id repeats in long runs (second sort key), so it compresses to a tiny fraction of its raw size and
        // beats LZ4 under ZSTD(1). ZSTD(3) is marginally smaller still on the run structure, but the absolute gap is a
        // few hundred bytes, so the cheaper ZSTD(1) is the right choice. Logged, not asserted, since that gap is
        // immaterial.
        assertThat(zstd1).isLessThan(uncompressed);
        assertThat(zstd1).isLessThan(lz4);
        log.info("[OPIK-7400] clustered project_id (UUIDv7) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    @Test
    void clusteredUuidV4WorkspaceIdColumnShipsZstd3() {
        long uncompressed = uncompressed("ws_zstd3");
        long lz4 = compressed("ws_lz4");
        long zstd1 = compressed("ws_zstd1");
        long zstd3 = compressed("ws_zstd3");

        // workspace_id is a clustered UUIDv4 String (first sort key, long runs), so it compresses to a tiny fraction of
        // its raw size; it cannot be LowCardinality (ORDER BY prefix). Ships ZSTD(3): on ClickHouse 26.3 ZSTD(1)
        // regressed on this clustered String (larger than LZ4), while ZSTD(3) is unaffected and smallest, at
        // level-independent decode. The shipped codec is pinned by everySpansLocalV2ColumnUsesItsIntendedCodec.
        assertThat(zstd3).isLessThan(uncompressed);
        assertThat(zstd3).isLessThanOrEqualTo(lz4);
        assertThat(zstd3).isLessThanOrEqualTo(zstd1);
        log.info("[OPIK-7400] clustered workspace_id (UUIDv4) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    @Test
    void shortRunTraceIdColumnCompressesBestWithZstd1() {
        long uncompressed = uncompressed("tid_zstd1");
        long lz4 = compressed("tid_lz4");
        long zstd1 = compressed("tid_zstd1");
        long zstd3 = compressed("tid_zstd3");

        // trace_id is the spans-only third sort key: a UUIDv7 repeated for the ~4.5 spans of one trace, so its runs are
        // three orders of magnitude shorter than project_id's. That is still enough structure for ZSTD(1) to beat LZ4
        // comfortably, and ZSTD(3) adds nothing on the random tail — the same conclusion as the other id columns, which
        // is what makes inheriting the id codec correct here. Prod confirms the shape: trace_id compresses 5.66x
        // against id's 1.34x under LZ4, purely from the repeat.
        assertThat(zstd1).isLessThan(uncompressed);
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(zstd3 * WITHIN_5_PCT));
        log.info("[OPIK-7400] trace_id (UUIDv7, ~{}-row runs) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                SPANS_PER_TRACE, lz4, zstd1, zstd3);
    }

    @Test
    void parentSpanIdColumnCompressesBestWithZstd1() {
        long uncompressed = uncompressed("psid_zstd1");
        long lz4 = compressed("psid_lz4");
        long zstd1 = compressed("psid_zstd1");
        long zstd3 = compressed("psid_zstd3");

        // parent_span_id is a UUIDv7 or the empty sentinel, repeated within a trace (~1.7 distinct parents per trace)
        // and, unlike the other id columns, NOT in the sort key — so its runs come only from the trace grouping. ZSTD(1)
        // still beats LZ4 and ZSTD(3) does not improve on it, so it takes the id codec.
        assertThat(zstd1).isLessThan(uncompressed);
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(zstd3 * WITHIN_5_PCT));
        log.info("[OPIK-7400] parent_span_id (FixedString(36)) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    @Test
    void parentSpanIdFixedStringIsNoLargerThanVariableLengthString() {
        long fixedUncompressed = uncompressed("psid_zstd1");
        long stringUncompressed = uncompressed("psid_str_zstd1");
        long fixedZstd1 = compressed("psid_zstd1");
        long stringZstd1 = compressed("psid_str_zstd1");

        // 000114 narrows parent_span_id from the live table's String to FixedString(36), on the reasoning that a column
        // holding a UUID or nothing does not need a per-value length prefix. This measures that claim on the storage
        // axis: the fixed form pads the empty sentinel out to 36 NUL bytes, so it can hold MORE raw bytes than the
        // String form, yet the padding is perfectly compressible while the String form's offsets are not, so the fixed
        // form is no larger compressed. (The read-path consequence of the padding is covered by SpansLocalV2TableTest
        // and SentinelTranslation, not here.)
        assertThat(fixedZstd1).isLessThanOrEqualTo(Math.round(stringZstd1 * WITHIN_5_PCT));
        log.info("[OPIK-7400] parent_span_id FixedString(36) vs String | uncompressed: {} vs {} | "
                + "ZSTD(1) compressed: {} vs {}", fixedUncompressed, stringUncompressed, fixedZstd1, stringZstd1);
    }

    @Test
    void usageMapCompressesBestWithZstd3() {
        long uncompressed = uncompressed("usage_zstd1");
        long lz4 = compressed("usage_lz4");
        long zstd1 = compressed("usage_zstd1");
        long zstd3 = compressed("usage_zstd3");

        // usage was the notable spans-only unknown: a Map(String, Int64) stored as an Array(String) keys subcolumn plus
        // an Array(Int64) values subcolumn, both under the one column codec. The keys are long, dotted and extremely
        // repetitive ('original_usage.completion_tokens_details.reasoning_tokens'), drawn from a handful of key sets —
        // exactly the small, repetitive, variable-length String shape ClickHouse 26.3 regressed ZSTD(1) on, which made
        // the ZSTD(1) that 000114 assigned as a best guess the level at risk. ZSTD(3) wins here by ~5%, and on real
        // production spans by 19.3% (580,731 -> 468,620 bytes) — real keys are longer and repeat more than this slice's.
        // Migration 000115 adopts ZSTD(3), and it is no more expensive to read: ZSTD decode is level-independent, so
        // less stored data is strictly less work (see longTextDecompressionCostIsRecorded).
        assertThat(zstd3).isLessThan(uncompressed);
        assertThat(zstd3).isLessThan(lz4);
        assertThat(zstd3).isLessThanOrEqualTo(zstd1);
        log.info("[OPIK-7400] usage Map(String, Int64) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {} | "
                + "uncompressed: {}", lz4, zstd1, zstd3, uncompressed);
    }

    @Test
    void usageInt64WideningIsAlmostFreeAfterCompression() {
        long int64Uncompressed = uncompressed("usage_zstd3");
        long int32Uncompressed = uncompressed("usage_i32_zstd3");
        long int64Zstd3 = compressed("usage_zstd3");
        long int32Zstd3 = compressed("usage_i32_zstd3");

        // 000114 widens usage from the live Map(String, Int32) to Map(String, Int64), a lossless widening that removes
        // the narrowing the live column forces on every write. It doubles the raw width of the values subcolumn, so the
        // cutover expectation needs the compressed cost of that, not the raw cost: token counts are small, so the extra
        // four bytes per value are leading zeros and compress away almost entirely.
        assertThat(int64Uncompressed).isGreaterThan(int32Uncompressed);
        assertThat(int64Zstd3).isLessThanOrEqualTo(Math.round(int32Zstd3 * WITHIN_10_PCT));
        log.info("[OPIK-7400] usage Int64 vs Int32 | uncompressed: {} vs {} | ZSTD(3) compressed: {} vs {}",
                int64Uncompressed, int32Uncompressed, int64Zstd3, int32Zstd3);
    }

    @Test
    void totalEstimatedCostDecimalStaysOnZstd1() {
        long uncompressed = uncompressed("cost_zstd1");
        long lz4 = compressed("cost_lz4");
        long zstd1 = compressed("cost_zstd1");
        long zstd3 = compressed("cost_zstd3");

        // total_estimated_cost is a Decimal(38, 12) — a 128-bit integer under the hood — that is zero on ~79% of prod
        // rows and, when set, a high-entropy value using the full 12-digit scale. The mass of zeros is what compresses;
        // the non-zero values are incompressible either way. The two specialized integer codecs are not even available
        // at this width (see decimal128RejectsTheSpecializedIntegerCodecs), so the choice is between the general-purpose
        // codecs, and ZSTD(1) wins: the shipped codec holds.
        assertThat(zstd1).isLessThan(uncompressed);
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(zstd3 * WITHIN_5_PCT));
        log.info("[OPIK-7400] total_estimated_cost Decimal(38,12) compressed bytes | LZ4: {} | ZSTD(1): {} | "
                + "ZSTD(3): {}", lz4, zstd1, zstd3);
    }

    @Test
    void decimal128RejectsTheSpecializedIntegerCodecs() {
        // total_estimated_cost is an integer type underneath, so T64 (which the narrow *_length counters use) and Delta
        // are the obvious candidates to try on it. ClickHouse rejects both: they only apply to 1/2/4/8-byte types, and
        // Decimal(38, 12) is 16 bytes wide. Recorded as a test so the report's "ZSTD(1), because the specialized
        // alternatives do not apply" is evidence rather than an assumption.
        var table = "opik_7400_decimal_codec";

        for (var codec : List.of("T64, ZSTD(1)", "Delta, ZSTD(1)")) {
            execute("DROP TABLE IF EXISTS %s.%s".formatted(DATABASE_NAME, table));
            assertThatThrownBy(() -> execute(("CREATE TABLE %s.%s (cost Decimal(38, 12) CODEC(%s)) "
                    + "ENGINE = MergeTree ORDER BY tuple()").formatted(DATABASE_NAME, table, codec)))
                    .as("codec %s must be rejected on Decimal(38, 12)", codec)
                    .hasMessageContaining("Decimal(38, 12)");
        }
    }

    @Test
    void lowCardinalityTypeBeatsPlainStringForModelAndProvider() {
        long modelPlain = compressed("model_str_zstd1");
        long modelLowCardinality = compressed("model_lc_default");
        long providerPlain = compressed("prov_str_zstd1");
        long providerLowCardinality = compressed("prov_lc_default");

        // 000114 makes model / provider / total_estimated_cost_version LowCardinality(String), which the live table
        // stores as plain String. The win is the dictionary type itself: even under the default LZ4 codec it beats a
        // plain ZSTD(1) String holding the same values, as it does for environment on traces.
        assertThat(modelLowCardinality).isLessThan(modelPlain);
        assertThat(providerLowCardinality).isLessThan(providerPlain);
        log.info("[OPIK-7400] LowCardinality vs plain String ZSTD(1) compressed bytes | model: {} vs {} | "
                + "provider: {} vs {}", modelLowCardinality, modelPlain, providerLowCardinality, providerPlain);
    }

    @Test
    void lowCardinalityColumnsCompressBestWithZstd1() {
        long modelDefault = compressed("model_lc_default");
        long modelZstd1 = compressed("model_lc_zstd1");
        long modelZstd3 = compressed("model_lc_zstd3");
        long providerDefault = compressed("prov_lc_default");
        long providerZstd1 = compressed("prov_lc_zstd1");
        long providerZstd3 = compressed("prov_lc_zstd3");
        long versionDefault = compressed("costver_lc_default");
        long versionZstd1 = compressed("costver_lc_zstd1");
        long versionZstd3 = compressed("costver_lc_zstd3");

        // On a LowCardinality column the codec applies to the dictionary and the index stream, not to repeated values,
        // so the 26.3 ZSTD(1) regression on repetitive Strings does not reach it — ZSTD(1) is smallest and is what
        // 000114 ships for all three. ZSTD(3) is logged for the record; the margin between the two is immaterial.
        assertThat(modelZstd1).isLessThan(modelDefault);
        assertThat(providerZstd1).isLessThan(providerDefault);
        assertThat(versionZstd1).isLessThan(versionDefault);
        assertThat(modelZstd1).isLessThanOrEqualTo(Math.round(modelZstd3 * WITHIN_5_PCT));
        log.info("[OPIK-7400] LowCardinality default vs ZSTD(1) vs ZSTD(3) compressed bytes | model: {} / {} / {} | "
                + "provider: {} / {} / {} | cost_version: {} / {} / {}",
                modelDefault, modelZstd1, modelZstd3, providerDefault, providerZstd1, providerZstd3,
                versionDefault, versionZstd1, versionZstd3);
    }

    @Test
    void spanTypeEnumCompressesBestWithZstd1() {
        long defaultCodec = compressed("type_default");
        long zstd1 = compressed("type_zstd1");
        long zstd3 = compressed("type_zstd3");

        // type is the spans-only Enum8 (five values, 62% 'general'). Like traces' source / visibility_mode it is tiny
        // whatever the codec, but ZSTD(1) compresses it materially better than the LZ4 default at equal decode cost,
        // which is why 000114 sets it rather than leaving the default. The decode measurement is logged alongside.
        assertThat(zstd1).isLessThan(defaultCodec);
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(zstd3 * WITHIN_5_PCT));
        log.info("[OPIK-7400] type (Enum8) compressed bytes default vs ZSTD(1) vs ZSTD(3): {} / {} / {}",
                defaultCodec, zstd1, zstd3);
        log.info("[OPIK-7400] type (Enum8) decode cost default vs ZSTD(1) | {} vs {}",
                measureScan("type_default"), measureScan("type_zstd1"));
    }

    @Test
    void longTextColumnCompressesBestWithZstd3() {
        long lz4 = compressed("text_lz4");
        long zstd1 = compressed("text_zstd1");
        long zstd3 = compressed("text_zstd3");

        // ZSTD(3) is the smallest for input/output; decode cost equals ZSTD(1) (see the decompression test). This is
        // the column that decides the headline: input alone is 64.6% of the compressed prod spans table.
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd3).isLessThanOrEqualTo(zstd1);
        log.info("[OPIK-7400] input-shaped long text compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    @Test
    void metadataJsonColumnCompressesWellWithZstd() {
        long lz4 = compressed("meta_lz4");
        long zstd1 = compressed("meta_zstd1");
        long zstd3 = compressed("meta_zstd3");

        // metadata is a JSON key-value document, distinct from the large LLM input/output. ZSTD clearly beats LZ4;
        // ZSTD(1) vs ZSTD(3) is close and depends on how varied the metadata is. The shipped ZSTD(3) is safe (free
        // decode) and helps varied metadata. Logged, not asserted, since the winner is data-dependent.
        assertThat(zstd1).isLessThan(lz4);
        log.info("[OPIK-7400] metadata JSON compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}", lz4, zstd1, zstd3);
    }

    @Test
    void slimAndTruncatedTextColumnsCompressBestWithZstd3() {
        long zstd1 = compressed("slim_zstd1");
        long zstd3 = compressed("slim_zstd3");

        // input_slim / output_slim / truncated_* are size-capped JSON derived from input/output — the same text profile,
        // so truncation changes only the size, not the codec class: ZSTD(3) stays at least as small as ZSTD(1). Shipped
        // ZSTD(3) holds. These are not a rounding error on spans: input_slim and output_slim together are 16.9% of the
        // compressed prod table, more than output itself.
        assertThat(zstd3).isLessThanOrEqualTo(zstd1);
    }

    @Test
    void spanNameColumnShipsZstd3() {
        long lz4 = compressed("name_lz4");
        long zstd1 = compressed("name_zstd1");
        long zstd3 = compressed("name_zstd3");

        // The span name is short medium-cardinality text. Ships ZSTD(3): on ClickHouse 26.3 ZSTD(1) regressed on these
        // repetitive variable-length String columns, while ZSTD(3) is unaffected and smallest, at level-independent
        // decode. The shipped codec is pinned by everySpansLocalV2ColumnUsesItsIntendedCodec.
        assertThat(zstd3).isLessThan(lz4);
        assertThat(zstd3).isLessThanOrEqualTo(Math.round(zstd1 * WITHIN_5_PCT));
        log.info("[OPIK-7400] name compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}", lz4, zstd1, zstd3);
    }

    @Test
    void createdByColumnShipsZstd3() {
        long lz4 = compressed("cb_lz4");
        long zstd1 = compressed("cb_zstd1");
        long zstd3 = compressed("cb_zstd3");

        // created_by / last_updated_by are short, highly repetitive Strings (the acting user, in long runs within a
        // workspace — 198x in prod). Same 26.3-regression class as name and tags, so they ship ZSTD(3).
        assertThat(zstd3).isLessThan(lz4);
        assertThat(zstd3).isLessThanOrEqualTo(Math.round(zstd1 * WITHIN_5_PCT));
        log.info("[OPIK-7400] created_by compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}", lz4, zstd1, zstd3);
    }

    @Test
    void tagsArrayColumnShipsZstd3() {
        long lz4 = compressed("arr_lz4");
        long zstd1 = compressed("arr_zstd1");
        long zstd3 = compressed("arr_zstd3");

        // tags is an Array(String) of low-cardinality values, empty on most spans. Ships ZSTD(3): it is smallest and,
        // unlike ZSTD(1), is unaffected by the ClickHouse 26.3 level-1 regression on repetitive columns.
        assertThat(zstd3).isLessThan(lz4);
        assertThat(zstd3).isLessThanOrEqualTo(Math.round(zstd1 * WITHIN_5_PCT));
        log.info("[OPIK-7400] tags Array(String) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
    }

    @Test
    void errorInfoStackTraceColumnShipsZstd3() {
        long lz4 = compressed("err_lz4");
        long zstd1 = compressed("err_zstd1");
        long zstd3 = compressed("err_zstd3");

        // error_info is empty for ~90% of spans; when present it is a JSON stack trace with repetitive traceback text.
        // Both ZSTD levels beat LZ4 by a wide margin, which is the robust fact and all this slice can assert: its
        // synthetic tracebacks are uniform enough that ZSTD(1) already captures the repetition, leaving the two levels
        // within ~1% of each other (ZSTD(1) marginally ahead). On real production stack traces, which are far more
        // varied, ZSTD(3) is 8.0% smaller (121,346 -> 111,596 bytes) — structured text behaving like input/output rather
        // than like the low-cardinality columns. Migration 000115 adopts ZSTD(3); it is pinned by
        // everySpansLocalV2ColumnUsesItsIntendedCodec.
        assertThat(zstd1).isLessThan(lz4);
        assertThat(zstd3).isLessThan(lz4);
        log.info("[OPIK-7400] error_info (~10% stack traces) compressed bytes | LZ4: {} | ZSTD(1): {} | ZSTD(3): {} "
                + "(ships ZSTD(3) on real-data evidence)", lz4, zstd1, zstd3);
    }

    @Test
    void monotonicTimestampFavoursDeltaOnlyOnTheIdealizedSyntheticSlice() {
        long lz4 = compressed("ts6_lz4");
        long zstd1 = compressed("ts6_zstd1");
        long deltaZstd1 = compressed("ts6_delta_zstd1");
        long doubleDelta = compressed("ts6_dd_zstd1");

        // This family models start_time / created_at as globally monotonic with a regular step, and under that idealized
        // shape Delta + ZSTD(1) is the smallest microsecond variant — it also beats DoubleDelta, whose
        // constant-second-derivative bet fails on irregularly-spaced ingestion timestamps.
        //
        // Real production spans do not have that shape, and there plain ZSTD(1) is SMALLER than Delta. Measured per ISO
        // week, since that is what one partition of this table holds: across the 10 densest weeks of the real sample
        // plain ZSTD(1) wins 10/10 weeks on created_at (median 19%), 9/10 on start_time (median 3.8%) and 8/10 on id_at
        // (median 13%). Two reasons, neither reproducible in a synthetic slice. At microsecond resolution the raw values
        // within one weekly partition share their high-order bytes, which ZSTD's literal matching exploits directly
        // while Delta discards it; and created_at is flat across 46.7% of adjacent row pairs, because batch ingest
        // stamps many spans with the identical microsecond. Delta additionally emits a large high-entropy 8-byte jump at
        // every workspace/project boundary, and a real weekly partition interleaves ~155k workspaces. Migration 000115
        // therefore drops Delta from start_time, created_at and id_at, and the pin map moves with it. Only the robust
        // orderings are asserted here.
        assertThat(deltaZstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThan(lz4);
        assertThat(deltaZstd1).isLessThanOrEqualTo(doubleDelta);
        log.info("[OPIK-7400] monotonic timestamp compressed bytes | LZ4: {} | ZSTD(1): {} | Delta+ZSTD(1): {} | "
                + "DoubleDelta+ZSTD(1): {} (ships ZSTD(1) on real-data evidence)", lz4, zstd1, deltaZstd1, doubleDelta);
    }

    @Test
    void microsecondTimestampCompressesBetterThanNanosecond() {
        long microseconds = compressed("ts6_delta_zstd1");
        long nanoseconds = compressed("ts9_delta_zstd1");

        // Dropping precision to microseconds strictly helps, because the nanosecond column carries sub-microsecond
        // noise (the now64(9) default's bottom three digits) that Delta cannot smooth away. Prod spans store
        // start_time / end_time / created_at as DateTime64(9); spans_local_v2 narrows them to (6).
        assertThat(microseconds).isLessThan(nanoseconds);
    }

    @Test
    void idAtColumnShipsZstd1FromRealData() {
        long lz4 = compressed("idat_lz4");
        long zstd1 = compressed("idat_zstd1");
        long deltaZstd1 = compressed("idat_delta_zstd1");
        long doubleDelta = compressed("idat_dd_zstd1");

        // id_at is a second-precision DateTime64(0) derived from the id, so it is monotonic within a part, and on this
        // slice — where the ids advance by a regular step — Delta exploits that and wins. On real production spans,
        // measured per ISO week (what one partition holds), plain ZSTD(1) is smaller in 8 of the 10 densest weeks, by a
        // median of 13%: whole-second values inside a single week are highly repetitive, so raw ZSTD matching beats
        // differencing. Migration 000115 ships ZSTD(1). Only the robust orderings are asserted.
        assertThat(deltaZstd1).isLessThan(lz4);
        assertThat(zstd1).isLessThan(lz4);
        log.info("[OPIK-7400] id_at (DateTime64(0)) compressed bytes | LZ4: {} | ZSTD(1): {} | Delta+ZSTD(1): {} | "
                + "DoubleDelta+ZSTD(1): {} (ships ZSTD(1) on real-data evidence)",
                lz4, zstd1, deltaZstd1, doubleDelta);
    }

    @Test
    void endTimeShipsDeltaZstd1FromRealData() {
        long lz4 = compressed("et_lz4");
        long zstd1 = compressed("et_zstd1");
        long deltaZstd1 = compressed("et_delta_zstd1");

        // Ships Delta + ZSTD(1), inherited from the traces real-data pass: on real production data end_time is
        // monotonic enough in storage order that Delta is smaller than plain ZSTD(1). This synthetic slice deliberately
        // scrambles end_time (start + a wide random duration + epoch sentinels), so plain ZSTD(1) can edge Delta here;
        // hence only the robust facts are asserted (both ZSTD variants beat LZ4). The spans-specific re-check belongs to
        // the real-data pass, since spans have a different duration profile from traces.
        assertThat(zstd1).isLessThan(lz4);
        assertThat(deltaZstd1).isLessThan(lz4);
        log.info("[OPIK-7400] end_time compressed bytes | LZ4: {} | ZSTD(1): {} | Delta+ZSTD(1): {} (ships Delta)",
                lz4, zstd1, deltaZstd1);
    }

    @Test
    void lastUpdatedAtShipsDeltaZstd1FromRealData() {
        long lz4 = compressed("lua_lz4");
        long zstd1 = compressed("lua_zstd1");
        long deltaZstd1 = compressed("lua_delta_zstd1");

        // last_updated_at is the ReplacingMergeTree version column. Ships Delta + ZSTD(1) for the same reason as
        // end_time; the synthetic slice scrambles it, so only the robust facts are asserted.
        assertThat(zstd1).isLessThan(lz4);
        assertThat(deltaZstd1).isLessThan(lz4);
        log.info(
                "[OPIK-7400] last_updated_at compressed bytes | LZ4: {} | ZSTD(1): {} | Delta+ZSTD(1): {} (ships Delta)",
                lz4, zstd1, deltaZstd1);
    }

    @Test
    void narrowCounterColumnCompressesBestWithT64Zstd1() {
        long zstd1 = compressed("cntn_zstd1");
        long t64Zstd1 = compressed("cntn_t64_zstd1");

        // T64's bet is that the values occupy a narrow bit range, so transposing the bit planes leaves long runs of
        // identical high-order bits for ZSTD to collapse. On a narrow counter (a ~4000-wide band) that holds and T64 +
        // ZSTD(1) is smaller than plain ZSTD(1) — the traces benchmark's counter result, reproduced here so the
        // divergence below is attributable to the value range alone.
        assertThat(t64Zstd1).isLessThanOrEqualTo(Math.round(zstd1 * WITHIN_2_PCT));
        log.info("[OPIK-7400] narrow counter compressed bytes | ZSTD(1): {} | T64+ZSTD(1): {}", zstd1, t64Zstd1);
    }

    @Test
    void tieredLengthCounterUnderstatesT64AndMustNotDecideItsCodec() {
        long zstd1 = compressed("cnt_zstd1");
        long t64Zstd1 = compressed("cnt_t64_zstd1");
        long doubleDelta = compressed("cnt_dd_zstd1");

        // A deliberate negative result, kept as a guard rail. This column holds the synthetic text lengths, and because
        // the slice draws payload sizes from three discrete tiers, those lengths are far more repetitive than real ones:
        // plain ZSTD matches the repeats directly and comes out ~5% AHEAD of T64, which would suggest dropping the
        // T64 + ZSTD(1) that 000114 ships on input_length / output_length / metadata_length.
        //
        // That suggestion is wrong, and the real-data pass settled it: on 115,925 real production spans T64 is 14.5% /
        // 13.9% / 26.0% SMALLER than plain ZSTD(1) on the three columns. Real lengths are continuously distributed over
        // six orders of magnitude (prod input_length: median 807 B, p99 537 KB, max 678 MB), which is exactly the regime
        // T64's bit transposition is built for, and there are no artificial repeats for plain ZSTD to exploit.
        //
        // So the shipped codec stands, and this test exists to record why the synthetic number disagrees — a tiered size
        // model cannot decide a codec that keys on the value distribution. DoubleDelta loses under either model.
        assertThat(t64Zstd1).isGreaterThan(zstd1);
        assertThat(t64Zstd1).isLessThan(doubleDelta);
        log.info("[OPIK-7400] tiered-length counter compressed bytes | ZSTD(1): {} | T64+ZSTD(1): {} (T64 {}% larger "
                + "here, but 14-26% SMALLER on real data — ships T64) | DoubleDelta+ZSTD(1): {}",
                zstd1, t64Zstd1, Math.round(100.0 * (t64Zstd1 - zstd1) / zstd1), doubleDelta);
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
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(lz4 * WITHIN_10_PCT));
    }

    @Test
    void floatColumnsUseZstd1RegardlessOfNaNFraction() {
        long durUncompressed = uncompressed("dur_zstd1");
        long durZstd1 = compressed("dur_zstd1");
        long durGorilla = compressed("dur_gorilla");
        long ttftZstd1 = compressed("ttft_zstd1");
        long ttftGorilla = compressed("ttft_gorilla");

        // duration is mostly populated; ttft is nearly always the NaN sentinel. In BOTH regimes ZSTD(1) beats the
        // float-specialized Gorilla codec, because ttft/duration are independent across adjacent rows (each a different
        // span), not the correlated time series Gorilla's XOR model targets. So the DDL's ZSTD(1) is right and the
        // choice does not hinge on any NaN-fraction assumption. Prod confirms both regimes: duration compresses 2.07x
        // and ttft 225x. Exact deltas are logged.
        assertThat(durZstd1).isLessThan(durUncompressed);
        assertThat(durZstd1).isLessThanOrEqualTo(durGorilla);
        assertThat(ttftZstd1).isLessThanOrEqualTo(ttftGorilla);
        log.info("[OPIK-7400] float compressed bytes | duration(mostly populated) ZSTD(1): {} vs Gorilla: {} | "
                + "ttft(mostly NaN) ZSTD(1): {} vs Gorilla: {}", durZstd1, durGorilla, ttftZstd1, ttftGorilla);
    }

    @Test
    void isDeletedFlagGainsNothingFromZstd() {
        long defaultCodec = compressed("flag_default");
        long zstd1 = compressed("flag_zstd1");

        // is_deleted is 98% zeros and one byte wide, so it compresses to a few hundred bytes under either codec and the
        // two are within ~1% of each other — no meaningful benefit from ZSTD, so the default is kept.
        assertThat(defaultCodec).isLessThanOrEqualTo(Math.round(zstd1 * WITHIN_5_PCT));
        assertThat(zstd1).isLessThanOrEqualTo(Math.round(defaultCodec * WITHIN_5_PCT));
    }

    @Test
    void serverDefaultCodecColumnsAreNegligibleInSize() {
        long enumDefault = compressed("src_default");
        long enumZstd1 = compressed("src_zstd1");
        long flagDefault = compressed("flag_default");
        long lcDefault = compressed("env_lc_default");
        long lcZstd1 = compressed("env_lc_zstd1");

        // source (Enum8), is_deleted (UInt8) and environment (LowCardinality) each cost well under a byte per row
        // whatever the codec. The ratio is NOT a wash — ZSTD(1) roughly halves enum / LowCardinality — but the columns
        // are tiny, so the decode cost is what the choice turns on, and it is equal.
        assertThat(enumDefault).isLessThan(ROW_COUNT);
        assertThat(flagDefault).isLessThan(ROW_COUNT);
        assertThat(lcDefault).isLessThan(ROW_COUNT);
        log.info("[OPIK-7400] server-default columns compressed bytes default vs ZSTD(1) | source enum: {} vs {} | "
                + "environment lowCardinality: {} vs {}", enumDefault, enumZstd1, lcDefault, lcZstd1);
    }

    @Test
    void sentinelColumnsAreNoLargerThanNullable() {
        // end_time/ttft/duration are non-Nullable with epoch/NaN sentinels rather than Nullable. This measures only the
        // storage effect (codec is ZSTD(1) either way): the sentinel form drops the separate Nullable null-mask stream,
        // and on this slice it is smaller on all three columns.
        //
        // Treat that as directional only — the real-data pass found the change is storage-NEUTRAL, not a saving:
        // end_time 409,981 vs 410,824 and duration 532,703 vs 533,566 (0.2% each), while ttft is marginally smaller as
        // Nullable (764 vs 810 bytes, both negligible). A null-mask of one byte per row compresses to almost nothing on
        // real data, so it never had much to give back. De-nullifying is still right — it removes the null branch from
        // the read path and keeps Nullable out of the hot aggregates — but it should be justified there, not here.
        long etSentinel = compressed("et_zstd1");
        long etNullable = compressed("et_nullable_zstd1");
        long ttftSentinel = compressed("ttft_zstd1");
        long ttftNullable = compressed("ttft_nullable_zstd1");
        long durSentinel = compressed("dur_zstd1");
        long durNullable = compressed("dur_nullable_zstd1");

        assertThat(etSentinel).isLessThanOrEqualTo(etNullable);
        assertThat(ttftSentinel).isLessThanOrEqualTo(ttftNullable);
        assertThat(durSentinel).isLessThanOrEqualTo(durNullable);
        log.info("[OPIK-7400] sentinel vs Nullable compressed bytes | end_time: {} vs {} | ttft: {} vs {} | "
                + "duration: {} vs {}", etSentinel, etNullable, ttftSentinel, ttftNullable, durSentinel, durNullable);
    }

    @Test
    void microsecondConversionTruncatesInsteadOfRounding() {
        execute("DROP TABLE IF EXISTS %s.dt64_spans_src".formatted(DATABASE_NAME));
        execute("DROP TABLE IF EXISTS %s.dt64_spans_dst".formatted(DATABASE_NAME));
        execute("CREATE TABLE %s.dt64_spans_src (dt9 DateTime64(9, 'UTC')) ENGINE = MergeTree ORDER BY tuple()"
                .formatted(DATABASE_NAME));
        execute("CREATE TABLE %s.dt64_spans_dst (dt6 DateTime64(6, 'UTC')) ENGINE = MergeTree ORDER BY tuple()"
                .formatted(DATABASE_NAME));
        // .123456789 -> the 7th digit (7) would flip the 6th (6 -> 7) under rounding.
        execute("INSERT INTO %s.dt64_spans_src VALUES (toDateTime64('2026-01-01 00:00:00.123456789', 9, 'UTC'))"
                .formatted(DATABASE_NAME));
        // The exact operation the backfill runs: INSERT ... SELECT from DateTime64(9) into DateTime64(6).
        execute("INSERT INTO %s.dt64_spans_dst SELECT dt9 FROM %s.dt64_spans_src".formatted(DATABASE_NAME,
                DATABASE_NAME));

        String stored = queryString("SELECT toString(dt6) FROM %s.dt64_spans_dst LIMIT 1".formatted(DATABASE_NAME));

        assertThat(stored).isEqualTo("2026-01-01 00:00:00.123456");
        log.info("[OPIK-7400] DateTime64(9)->(6) conversion of .123456789 stored as {} (truncation confirmed)", stored);
    }

    @Test
    void longTextDecompressionCostIsRecorded() {
        var lz4 = measureScan("text_lz4");
        var zstd1 = measureScan("text_zstd1");
        var zstd3 = measureScan("text_zstd3");
        var usageZstd1 = measureScan("usage_zstd1");
        var usageZstd3 = measureScan("usage_zstd3");

        assertThat(lz4).isNotNull();
        assertThat(zstd1).isNotNull();
        assertThat(zstd3).isNotNull();

        // ZSTD decode cost is independent of the compression level, so ZSTD(3) costs no more to read than ZSTD(1) while
        // compressing better — the trade-off that makes ZSTD(3) worth it on long text, and the reason a usage ZSTD(1)
        // -> ZSTD(3) move would carry no read penalty either. This is a single-shot scan after one warmup (not a
        // sustained concurrent-read load), so the robust takeaway is the ZSTD(3) ~= ZSTD(1) parity; the absolute times
        // are order-sensitive and only logged, never asserted.
        log.info("[OPIK-7400] single-thread decompression scan cost | long text LZ4: {} | ZSTD(1): {} | ZSTD(3): {}",
                lz4, zstd1, zstd3);
        log.info("[OPIK-7400] single-thread decompression scan cost | usage Map ZSTD(1): {} | ZSTD(3): {}",
                usageZstd1, usageZstd3);
    }

    @Test
    void wholeRowStorageBeforeVsAfter() {
        // Headline number: total compressed size of a full spans row in the OLD prod format (all columns on the LZ4
        // default, DateTime64(9), Nullable end_time/ttft/duration, String parent_span_id, Map(String, Int32) usage,
        // plain String model/provider/total_estimated_cost_version) vs the NEW spans_local_v2 format (tuned per-column
        // codecs, DateTime64(6), epoch/NaN sentinels, FixedString parent_span_id, Map(String, Int64) usage,
        // LowCardinality model/provider/version, is_deleted). Same data staged in full_src, inserted into both, so the
        // delta is the format alone. Partition/skip-indexes are omitted (confirmed not to move column compression by the
        // traces benchmark). Realistic proportions: input/output dominate, with the truncated_*/slim derived copies as
        // in prod.
        for (var table : List.of("spans_full_src", "spans_full_before", "spans_full_after")) {
            execute("DROP TABLE IF EXISTS %s.%s".formatted(DATABASE_NAME, table));
        }

        // Raw staging table (no codecs, no materialized columns) — the values the app writes.
        execute("""
                CREATE TABLE {db}.spans_full_src
                (
                    id String, workspace_id String, project_id String, trace_id String, parent_span_id String,
                    name String, type Enum8('unknown' = 0, 'general' = 1, 'tool' = 2, 'llm' = 3, 'guardrail' = 4),
                    start_time DateTime64(9, 'UTC'), end_time DateTime64(9, 'UTC'),
                    input String, output String, metadata String, tags Array(String), usage Map(String, Int64),
                    created_at DateTime64(9, 'UTC'), last_updated_at DateTime64(6, 'UTC'),
                    created_by String, last_updated_by String, model String, provider String,
                    total_estimated_cost Decimal(38, 12), total_estimated_cost_version String,
                    error_info String, truncation_threshold UInt64, input_slim String, output_slim String,
                    ttft Float64,
                    source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5),
                    environment String
                )
                ENGINE = MergeTree ORDER BY tuple()
                """
                .replace("{db}", DATABASE_NAME));

        // Prod-shaped spans: a median span well under 1 KB with a heavy tail, five spans per trace, usage/model/cost on
        // the llm ones only.
        var vocab = "['the','user','asked','the','assistant','about','weather','and','requested','a','concise',"
                + "'summary','of','the','document','with','several','key','points','listed','in','order','please']";
        execute("""
                INSERT INTO {db}.spans_full_src
                WITH
                    toUInt64(toUnixTimestamp64Milli(toDateTime64('2026-01-06 00:00:00', 3, 'UTC')) + number * 3000)
                        AS id_ms,
                    intDiv(number, {spans_per_trace}) AS t_idx,
                    toUInt64(toUnixTimestamp64Milli(toDateTime64('2026-01-06 00:00:00', 3, 'UTC'))
                        + t_idx * {spans_per_trace} * 3000) AS t_ms,
                    multiIf(
                        cityHash64(number, 'ty') % 10000 < 6210, 'general',
                        cityHash64(number, 'ty') % 10000 < 9005, 'llm',
                        cityHash64(number, 'ty') % 10000 < 9905, 'tool',
                        cityHash64(number, 'ty') % 10000 < 9969, 'guardrail',
                        'unknown') AS span_type,
                    span_type = 'llm' AS is_llm,
                    multiIf(
                        cityHash64(number, 'sz') % 1000 < 900, 60,
                        cityHash64(number, 'sz') % 1000 < 990, 600,
                        3000) AS in_words,
                    arrayStringConcat(arrayMap(i -> VOCAB[(cityHash64(number, i, 'in') % 23) + 1],
                        range(in_words)), ' ') AS in_body,
                    arrayStringConcat(arrayMap(i -> VOCAB[(cityHash64(number, i, 'ou') % 23) + 1],
                        range(intDiv(in_words, 4))), ' ') AS out_body,
                    concat('{"messages":[{"role":"user","content":"', in_body, '"}]}') AS input_text,
                    concat('{"choices":[{"message":{"content":"', out_body, '"}}]}') AS output_text,
                    toInt64(200 + cityHash64(number, 'pt') % 20000) AS prompt_tokens,
                    toInt64(cityHash64(number, 'ct') % 1200) AS completion_tokens
                SELECT
                    {id_uuid} AS id,
                    lower(hex(cityHash64(intDiv(number, 1000), 'ws'))) AS workspace_id,
                    lower(hex(cityHash64(intDiv(number, 100), 'pj'))) AS project_id,
                    {trace_uuid} AS trace_id,
                    if(number % {spans_per_trace} = 0, '', {root_uuid}) AS parent_span_id,
                    concat('op-', toString(cityHash64(number, 'nm') % 200)) AS name,
                    span_type AS type,
                    toDateTime64('2026-01-06 00:00:00', 9, 'UTC') + toIntervalMillisecond(number * 3000) AS start_time,
                    toDateTime64('2026-01-06 00:00:00', 9, 'UTC') + toIntervalMillisecond(number * 3000
                        + (cityHash64(number, 'dur') % 30000)) AS end_time,
                    input_text AS input,
                    output_text AS output,
                    concat('{"env":"prod","model":"gpt-4o","note":"', arrayStringConcat(arrayMap(i ->
                        VOCAB[(cityHash64(number, i, 'md') % 23) + 1], range(30)), ' '), '"}') AS metadata,
                    arrayMap(i -> ['Langchain','production','rag','agent','llm'][(cityHash64(number, i) % 5) + 1],
                        range(if(cityHash64(number, 'ntag') % 100 < 80, 0,
                            cityHash64(number, 'ntag2') % 4))) AS tags,
                    if(NOT is_llm,
                        CAST(map(), 'Map(String, Int64)'),
                        mapFromArrays({usage_keys_full},
                            arrayMap(k -> multiIf(
                                k = 'prompt_tokens', prompt_tokens,
                                k = 'completion_tokens', completion_tokens,
                                k = 'total_tokens', prompt_tokens + completion_tokens,
                                k = 'original_usage.prompt_tokens', prompt_tokens,
                                k = 'original_usage.completion_tokens', completion_tokens,
                                k = 'original_usage.total_tokens', prompt_tokens + completion_tokens,
                                toInt64(cityHash64(number, k) % 600)), {usage_keys_full}))) AS usage,
                    toDateTime64('2026-01-06 00:00:00', 9, 'UTC') + toIntervalMillisecond(number * 3000) AS created_at,
                    toDateTime64('2026-01-06 00:00:00', 6, 'UTC') + toIntervalMillisecond(number * 3000
                        + (cityHash64(number, 'lua') % 30000)) AS last_updated_at,
                    concat('user-', toString(cityHash64(intDiv(number, 1000), 'cb') % 5)) AS created_by,
                    concat('user-', toString(cityHash64(intDiv(number, 1000), 'cb') % 5)) AS last_updated_by,
                    if(NOT is_llm, '',
                        ['gpt-4o-mini-2024-07-18','gemini-2.5-flash','gpt-5-mini-2025-08-07','gpt-4o',
                         'claude-sonnet-4-6'][(cityHash64(number, 'mh') % 5) + 1]) AS model,
                    if(NOT is_llm, '',
                        ['openai','google_ai','openrouter','anthropic'][(cityHash64(number, 'pv') % 4) + 1])
                        AS provider,
                    if(NOT is_llm OR cityHash64(number, 'cz') % 100 < 25, toDecimal128('0', 12),
                        toDecimal128(concat('0.',
                            leftPad(toString(cityHash64(number, 'cost') % 1000000000000), 12, '0')), 12))
                        AS total_estimated_cost,
                    if(NOT is_llm OR cityHash64(number, 'cz') % 100 < 25, '', '1.1') AS total_estimated_cost_version,
                    if(cityHash64(number, 'err') % 100 < 90, '', concat('{"exception_type":"ValueError","traceback":"',
                        arrayStringConcat(arrayMap(i -> concat('at m', toString(cityHash64(number, i) % 20), ' '),
                        range(10)), ''), '"}')) AS error_info,
                    toUInt64(10001) AS truncation_threshold,
                    substring(input_text, 1, 1000) AS input_slim,
                    substring(output_text, 1, 1000) AS output_slim,
                    if(NOT is_llm OR cityHash64(number, 'ttft') % 100 < 70, toFloat64('nan'),
                        round(10 + (cityHash64(number, 'tv') % 2000000) / 1000.0, 3)) AS ttft,
                    multiIf(
                        cityHash64(number, 'sr') % 1000 < 743, 'unknown',
                        cityHash64(number, 'sr') % 1000 < 971, 'sdk',
                        'experiment') AS source,
                    if(cityHash64(number, 'ev') % 1000 < 997, '', 'production') AS environment
                FROM numbers(3000) SETTINGS max_insert_threads = 1, max_threads = 1
                """
                .replace("{db}", DATABASE_NAME)
                .replace("{id_uuid}", uuidV7("id_ms", "number"))
                .replace("{trace_uuid}", uuidV7("t_ms", "t_idx"))
                .replace("{root_uuid}", uuidV7("t_ms", "t_idx * " + SPANS_PER_TRACE))
                .replace("{usage_keys_full}", USAGE_KEYS_FULL)
                .replace("{spans_per_trace}", String.valueOf(SPANS_PER_TRACE))
                .replace("VOCAB", vocab));

        var beforeMaterialized = """
                    input_length UInt64 MATERIALIZED length(input),
                    output_length UInt64 MATERIALIZED length(output),
                    metadata_length UInt64 MATERIALIZED length(metadata),
                    truncated_input String MATERIALIZED if(length(input) >= truncation_threshold, substring(input, 1, truncation_threshold), input),
                    truncated_output String MATERIALIZED if(length(output) >= truncation_threshold, substring(output, 1, truncation_threshold), output),
                    duration Nullable(Float64) MATERIALIZED if((end_time IS NOT NULL) AND (start_time IS NOT NULL) AND (start_time != toDateTime64('1970-01-01 00:00:00.000', 9)), dateDiff('microsecond', start_time, end_time) / 1000., NULL),
                    id_at DateTime('UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(id))
                """;
        // OLD prod format: LZ4 default everywhere, DateTime64(9), Nullable end_time/ttft/duration, String
        // parent_span_id, Map(String, Int32) usage, plain String model/provider/version, no is_deleted.
        execute(("""
                CREATE TABLE {db}.spans_full_before
                (
                    id FixedString(36), workspace_id String, project_id FixedString(36), trace_id FixedString(36),
                    parent_span_id String, name String,
                    type Enum8('unknown' = 0, 'general' = 1, 'tool' = 2, 'llm' = 3, 'guardrail' = 4),
                    start_time DateTime64(9, 'UTC'), end_time Nullable(DateTime64(9, 'UTC')),
                    input String, output String, metadata String, tags Array(String), usage Map(String, Int32),
                    created_at DateTime64(9, 'UTC'), last_updated_at DateTime64(6, 'UTC'),
                    created_by String, last_updated_by String, model String, provider String,
                    total_estimated_cost Decimal(38, 12), total_estimated_cost_version String,
                    error_info String, truncation_threshold UInt64, input_slim String, output_slim String,
                    ttft Nullable(Float64),
                    source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5),
                    environment LowCardinality(String),
                """
                + beforeMaterialized + """
                        )
                        ENGINE = MergeTree ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id)
                        """).replace("{db}", DATABASE_NAME));

        var afterMaterialized = """
                    input_length UInt64 MATERIALIZED length(input) CODEC(T64, ZSTD(1)),
                    output_length UInt64 MATERIALIZED length(output) CODEC(T64, ZSTD(1)),
                    metadata_length UInt64 MATERIALIZED length(metadata) CODEC(T64, ZSTD(1)),
                    truncated_input String MATERIALIZED if(length(input) >= truncation_threshold, substring(input, 1, truncation_threshold), input) CODEC(ZSTD(3)),
                    truncated_output String MATERIALIZED if(length(output) >= truncation_threshold, substring(output, 1, truncation_threshold), output) CODEC(ZSTD(3)),
                    duration Float64 MATERIALIZED if(end_time = toDateTime64('1970-01-01 00:00:00', 6) OR start_time = toDateTime64('1970-01-01 00:00:00', 6), toFloat64('nan'), dateDiff('microsecond', start_time, end_time) / 1000.0) CODEC(ZSTD(1)),
                    id_at DateTime64(0, 'UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(id)) CODEC(ZSTD(1))
                """;
        // NEW spans_local_v2 format = the live table's codecs after migrations 000114 (create) and 000115 (the
        // real-data refinements: usage and error_info on ZSTD(3), start_time / created_at / id_at off Delta), matching
        // the pin map above.
        execute(("""
                CREATE TABLE {db}.spans_full_after
                (
                    id FixedString(36) CODEC(ZSTD(1)), workspace_id String CODEC(ZSTD(3)),
                    project_id FixedString(36) CODEC(ZSTD(1)), trace_id FixedString(36) CODEC(ZSTD(1)),
                    parent_span_id FixedString(36) DEFAULT '' CODEC(ZSTD(1)), name String CODEC(ZSTD(3)),
                    type Enum8('unknown' = 0, 'general' = 1, 'tool' = 2, 'llm' = 3, 'guardrail' = 4) CODEC(ZSTD(1)),
                    start_time DateTime64(6, 'UTC') CODEC(ZSTD(1)),
                    end_time DateTime64(6, 'UTC') CODEC(Delta, ZSTD(1)),
                    input String CODEC(ZSTD(3)), output String CODEC(ZSTD(3)), metadata String CODEC(ZSTD(3)),
                    tags Array(String) CODEC(ZSTD(3)), usage Map(String, Int64) CODEC(ZSTD(3)),
                    created_at DateTime64(6, 'UTC') CODEC(ZSTD(1)),
                    last_updated_at DateTime64(6, 'UTC') CODEC(Delta, ZSTD(1)),
                    created_by String CODEC(ZSTD(3)), last_updated_by String CODEC(ZSTD(3)),
                    model LowCardinality(String) CODEC(ZSTD(1)), provider LowCardinality(String) CODEC(ZSTD(1)),
                    total_estimated_cost Decimal(38, 12) CODEC(ZSTD(1)),
                    total_estimated_cost_version LowCardinality(String) CODEC(ZSTD(1)),
                    error_info String CODEC(ZSTD(3)), truncation_threshold UInt64 CODEC(ZSTD(1)),
                    input_slim String CODEC(ZSTD(3)), output_slim String CODEC(ZSTD(3)),
                    ttft Float64 CODEC(ZSTD(1)),
                    source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) CODEC(ZSTD(1)),
                    environment LowCardinality(String) CODEC(ZSTD(1)), is_deleted UInt8,
                """
                + afterMaterialized + """
                        )
                        ENGINE = MergeTree ORDER BY (workspace_id, project_id, trace_id, id)
                        """).replace("{db}", DATABASE_NAME));

        var storedColumns = "id, workspace_id, project_id, trace_id, parent_span_id, name, type, start_time, end_time, "
                + "input, output, metadata, tags, usage, created_at, last_updated_at, created_by, last_updated_by, "
                + "model, provider, total_estimated_cost, total_estimated_cost_version, error_info, "
                + "truncation_threshold, input_slim, output_slim, ttft, source, environment";
        execute(("INSERT INTO {db}.spans_full_before (" + storedColumns + ") SELECT "
                + "id, workspace_id, project_id, trace_id, parent_span_id, name, type, start_time, end_time, input, "
                + "output, metadata, tags, CAST(usage, 'Map(String, Int32)'), created_at, last_updated_at, created_by, "
                + "last_updated_by, model, provider, total_estimated_cost, total_estimated_cost_version, error_info, "
                + "truncation_threshold, input_slim, output_slim, ttft, source, environment "
                + "FROM {db}.spans_full_src SETTINGS max_insert_threads = 1, max_threads = 1")
                .replace("{db}", DATABASE_NAME));
        execute(("INSERT INTO {db}.spans_full_after (" + storedColumns + ", is_deleted) SELECT "
                + "id, workspace_id, project_id, trace_id, toFixedString(parent_span_id, 36), name, type, "
                + "toDateTime64(start_time, 6, 'UTC'), toDateTime64(end_time, 6, 'UTC'), input, output, metadata, "
                + "tags, usage, toDateTime64(created_at, 6, 'UTC'), last_updated_at, created_by, last_updated_by, "
                + "model, provider, total_estimated_cost, total_estimated_cost_version, error_info, "
                + "truncation_threshold, input_slim, output_slim, ttft, source, environment, 0 "
                + "FROM {db}.spans_full_src SETTINGS max_insert_threads = 1, max_threads = 1")
                .replace("{db}", DATABASE_NAME));
        execute("OPTIMIZE TABLE %s.spans_full_before FINAL".formatted(DATABASE_NAME));
        execute("OPTIMIZE TABLE %s.spans_full_after FINAL".formatted(DATABASE_NAME));

        long before = totalCompressedOf("spans_full_before");
        long after = totalCompressedOf("spans_full_after");
        long rows = queryLong("SELECT count() FROM %s.spans_full_before".formatted(DATABASE_NAME));

        // The new format must be materially smaller on the same data.
        assertThat(after).isLessThan(before);
        log.info("[OPIK-7400] whole-row storage before (prod format) vs after (spans_local_v2) | {} vs {} bytes "
                + "over {} rows | {} vs {} bytes/row | after is {}% of before",
                before, after, rows, before / rows, after / rows, Math.round(100.0 * after / before));

        for (var table : List.of("spans_full_src", "spans_full_before", "spans_full_after")) {
            execute("DROP TABLE IF EXISTS %s.%s".formatted(DATABASE_NAME, table));
        }
    }

    /** Renders {@link #UUID_V7_EXPR} for a given millisecond and seed expression. */
    private static String uuidV7(String msExpression, String seedExpression) {
        return UUID_V7_EXPR.replace("{ms}", msExpression).replace("{seed}", seedExpression);
    }

    /** Renders {@link #SENTENCE_EXPR} at a given word count. */
    private static String sentence(int words) {
        return SENTENCE_EXPR.replace("{words}", String.valueOf(words));
    }

    private void logReport() {
        var report = new StringBuilder("\n[OPIK-7400] Per-column codec benchmark on %,d synthetic spans rows\n"
                .formatted(ROW_COUNT));
        report.append("%-20s %16s %16s %8s\n".formatted("column", "uncompressed", "compressed", "ratio"));
        report.repeat("-", 64).append('\n');
        columnStats.values().forEach(stat -> report.append("%-20s %,16d %,16d %8.2fx\n".formatted(
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

    private long totalCompressedOf(String table) {
        return queryLong(("SELECT sum(data_compressed_bytes) FROM system.parts "
                + "WHERE database = '%s' AND table = '%s' AND active").formatted(DATABASE_NAME, table));
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
