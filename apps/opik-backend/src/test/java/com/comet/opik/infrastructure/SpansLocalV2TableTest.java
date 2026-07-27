package com.comet.opik.infrastructure;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Source;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.SentinelTranslation;
import com.fasterxml.jackson.databind.JsonNode;
import io.r2dbc.spi.Row;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.utils.TruncationUtils.createSlimJsonString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Round-trips a row through the spans_local_v2 table at the column level: {@link StoredSpan} mirrors the DDL (in
 * migration order) but holds the domain representations, and the insert/read helpers apply the same sentinel
 * translation and JSON (de)serialization the DAO will use at cutover — so an absent end_time/ttft/parent_span_id is
 * written as the epoch/NaN/empty sentinel and read back as null. This validates the table itself (microsecond
 * precision, the sentinels, the materialized columns, the UUIDv7-derived partition key) and the cutover mapping
 * against it.
 * <p>
 * Counterpart of {@code TracesLocalV2TableTest}; the spans-only columns (trace_id, parent_span_id, type, usage, model,
 * provider, the cost pair) are what this adds over it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpansLocalV2TableTest {

    /**
     * The spans_local_v2 DEFAULT; bound on insert, and one test lowers it to force the truncated_* columns.
     */
    private static final long DEFAULT_TRUNCATION_THRESHOLD = 10_001;

    /**
     * The zero value of both Enum8 columns, and the DEFAULT of each. Ingestion never writes it ({@link SpanType} and
     * {@link Source} deliberately have no such member), but the live spans table carries rows with it, so the cutover
     * INSERT must be able to map them.
     */
    private static final String UNKNOWN_ENUM_VALUE = SpanType.UNKNOWN_VALUE;

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);

    private final TransactionTemplateAsync transactionTemplateAsync;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    {
        Startables.deepStart(zookeeperContainer, clickHouseContainer).join();
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        transactionTemplateAsync = TransactionTemplateAsync.create(
                ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouseContainer, DATABASE_NAME).build());
    }

    /**
     * Every enum value must exist in the DDL Enum8; a missing one fails the insert. Spans carry two independent enums,
     * so the full cross-product is covered deterministically, where a single random Podam pick per column would not.
     */
    private static Stream<Arguments> sourcesAndTypes() {
        return Arrays.stream(Source.values())
                .flatMap(source -> Arrays.stream(SpanType.values()).map(type -> Arguments.of(source, type)));
    }

    @ParameterizedTest(name = "source {0}, type {1}")
    @MethodSource("sourcesAndTypes")
    void allColumnsRoundTrip(Source source, SpanType type) {
        var startTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var endTime = randomFutureInstantFrom(startTime);
        var expectedStoredSpan = newStoredSpan(startTime, endTime, DEFAULT_TRUNCATION_THRESHOLD).toBuilder()
                .source(source)
                .type(type)
                .build();
        insert(expectedStoredSpan);

        var actualStoredSpan = getById(expectedStoredSpan);

        assertEqual(actualStoredSpan, expectedStoredSpan);
    }

    @ParameterizedTest
    @EnumSource(SpanType.class)
    void allAbsentColumnsRoundTripAsNull(SpanType type) {
        var startTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var expectedStoredSpan = newStoredSpan(startTime, null, DEFAULT_TRUNCATION_THRESHOLD).toBuilder()
                .type(type)
                .parentSpanId(null)
                .ttft(null)
                .build();
        insert(expectedStoredSpan);

        var actualStoredSpan = getById(expectedStoredSpan);

        assertEqual(actualStoredSpan, expectedStoredSpan);
        assertThat(actualStoredSpan.endTime()).isNull();
        assertThat(actualStoredSpan.ttft()).isNull();
        assertThat(actualStoredSpan.duration()).isNull();
        // A root span: the empty sentinel is stored as 36 NUL bytes, which must read back as absent, not as a UUID.
        assertThat(actualStoredSpan.parentSpanId()).isNull();
    }

    /**
     * A row written with nothing but its key columns: every other column must be server-defaultable, which is what the
     * cutover INSERT relies on for the columns the live table does not have (is_deleted). It also pins the two Enum8
     * zero values, which neither {@link SpanType} nor {@link Source} can express — the live table holds rows carrying
     * them, so the DDL has to keep accepting them.
     */
    @Test
    void keyColumnsOnlyRowTakesEveryDefault() {
        var storedSpan = newStoredSpan(Instant.now().truncatedTo(ChronoUnit.MICROS), null,
                DEFAULT_TRUNCATION_THRESHOLD);
        insertKeyColumnsOnly(storedSpan);

        assertThat(getColumn(storedSpan, "type", String.class)).isEqualTo(UNKNOWN_ENUM_VALUE);
        assertThat(getColumn(storedSpan, "source", String.class)).isEqualTo(UNKNOWN_ENUM_VALUE);
        assertThat(getColumn(storedSpan, "is_deleted", Byte.class)).isZero();
        // end_time / ttft / duration fall back to their sentinels, which read as absent.
        assertThat(SentinelTranslation.epochToNull(getColumn(storedSpan, "end_time", Instant.class))).isNull();
        assertThat(SentinelTranslation.nanToNull(getColumn(storedSpan, "ttft", Double.class))).isNull();
        assertThat(SentinelTranslation.nanToNull(getColumn(storedSpan, "duration", Double.class))).isNull();
        assertThat(getColumn(storedSpan, "truncation_threshold", Long.class))
                .isEqualTo(DEFAULT_TRUNCATION_THRESHOLD);
    }

    @Test
    void epochStartTimeYieldsNullDuration() {
        var endTime = randomFutureInstantFrom(Instant.now().truncatedTo(ChronoUnit.MICROS));
        // start_time at the epoch sentinel: the duration guard must yield null even though end_time is present.
        var expectedStoredSpan = newStoredSpan(Instant.EPOCH, endTime, DEFAULT_TRUNCATION_THRESHOLD);
        insert(expectedStoredSpan);

        var actualStoredSpan = getById(expectedStoredSpan);

        assertEqual(actualStoredSpan, expectedStoredSpan);
        assertThat(actualStoredSpan.duration()).isNull();
    }

    @Test
    void inputAndOutputTruncatedIfAboveThreshold() {
        var startTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var endTime = randomFutureInstantFrom(startTime);
        // A threshold well below the payload size so the truncated_* materialized columns actually truncate.
        var expectedStoredSpan = newStoredSpan(startTime, endTime, 10L);
        insert(expectedStoredSpan);

        var actualStoredSpan = getById(expectedStoredSpan);

        assertEqual(actualStoredSpan, expectedStoredSpan);
        assertThat(actualStoredSpan.truncatedInput()).hasSize(10).isNotEqualTo(actualStoredSpan.input().toString());
        assertThat(actualStoredSpan.truncatedOutput()).hasSize(10).isNotEqualTo(actualStoredSpan.output().toString());
    }

    @ValueSource(longs = {
            0, // this week
            21, // three weeks ago
            400, // over a year ago
            -14 // two weeks ahead
    })
    @ParameterizedTest(name = "id_at {0} days from now")
    void rowLandsInWeeklyPartitionDerivedFromId(long idAgeDays) {
        var id = ID_GENERATOR.generateId(Instant.now().minus(idAgeDays, ChronoUnit.DAYS));
        var startTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var endTime = randomFutureInstantFrom(startTime);
        var idAt = idAtOf(id);
        var storedSpan = newStoredSpan(startTime, endTime, DEFAULT_TRUNCATION_THRESHOLD).toBuilder()
                .id(id)
                .idAt(idAt)
                .build();
        insert(storedSpan);

        var partitionId = getColumn(storedSpan, "_partition_id", String.class);

        // PARTITION BY toMonday(id_at): the row must land in the partition for the Monday of the week its UUIDv7 id
        // encodes — not the current week. Backdated ids prove the partition follows id_at, not wall-clock (the reason
        // the design chose an id-derived key over created_at). ClickHouse names a Date partition YYYYMMDD.
        var expectedMonday = idAt.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(partitionId).isEqualTo(expectedMonday.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    /**
     * Pins the deployed DDL against the migration's spec in one assertion: types and precisions, defaults, every codec,
     * the engine and its version/is_deleted parameters, the partition and sorting keys, the skip indexes and the
     * granularity settings. Any drift — including one ClickHouse introduces on an upgrade — has to be acknowledged
     * here, because the ORDER BY, partition key and engine parameters cannot be changed after creation.
     */
    @Test
    void showCreateTableMatchesSpec() {
        var actualDdl = transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("SHOW CREATE TABLE spans_local_v2");
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get(0, String.class))));
        }).block();

        assertThat(actualDdl).isEqualTo(EXPECTED_DDL);
    }

    /**
     * A future instant offset by whole seconds plus a sub-millisecond number of microseconds, so the derived duration
     * carries a fractional-millisecond part. This exercises the microsecond dateDiff the duration formula relies on: a
     * millisecond dateDiff would silently drop the fraction and diverge from the expected value.
     */
    private Instant randomFutureInstantFrom(Instant instant) {
        return instant.plusSeconds(RandomUtils.secure().randomLong(2, 301))
                .plus(RandomUtils.secure().randomLong(1, 1000), ChronoUnit.MICROS);
    }

    /**
     * A row reduced to what the table stores, with controlled values where a random one would not work: valid JSON for
     * the JSON columns (so the materialized columns are exercised) and controlled times for a deterministic duration.
     * The server-defaulted and materialized columns carry their expected values.
     */
    private StoredSpan newStoredSpan(Instant startTime, Instant endTime, long truncationThreshold) {
        var id = ID_GENERATOR.generateId();
        var user = randomString("user");
        var input = podamFactory.manufacturePojo(JsonNode.class);
        var output = podamFactory.manufacturePojo(JsonNode.class);
        var metadata = podamFactory.manufacturePojo(JsonNode.class);
        return podamFactory.manufacturePojo(StoredSpan.class).toBuilder()
                .id(id)
                .workspaceId(UUID.randomUUID().toString())
                .projectId(ID_GENERATOR.generateId())
                .traceId(ID_GENERATOR.generateId())
                .parentSpanId(ID_GENERATOR.generateId())
                .startTime(startTime)
                .endTime(endTime)
                .input(input)
                .output(output)
                .metadata(metadata)
                .createdBy(user)
                .lastUpdatedBy(user)
                // Decimal(38, 12): scale the random value to the column's precision so the round-trip compares equal.
                .totalEstimatedCost(randomCost())
                .truncationThreshold(truncationThreshold)
                .inputSlim(createSlimJsonString(input.toString()))
                .outputSlim(createSlimJsonString(output.toString()))
                .isDeleted((byte) 0) // 0 = live row, not a tombstone
                // Materialized columns: the values the DDL formulas are expected to compute
                .inputLength(toByteLength(input))
                .outputLength(toByteLength(output))
                .metadataLength(toByteLength(metadata))
                .truncatedInput(truncate(input.toString(), truncationThreshold))
                .truncatedOutput(truncate(output.toString(), truncationThreshold))
                .duration(durationOf(startTime, endTime))
                .idAt(idAtOf(id))
                .build();
    }

    private String randomString(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }

    /**
     * A positive cost at the column's exact scale, so {@code Decimal(38, 12)} neither rounds nor rescales it.
     */
    private BigDecimal randomCost() {
        return BigDecimal.valueOf(RandomUtils.secure().randomLong(1, 1_000_000_000L), 12);
    }

    /**
     * Mirrors the truncated_* materialized formula: the first `threshold` bytes once the value reaches it. Podam JSON
     * is ASCII, so a char-based substring matches ClickHouse's byte-based substring.
     */
    private String truncate(String value, long threshold) {
        return value.length() >= threshold ? value.substring(0, (int) threshold) : value;
    }

    private void insert(StoredSpan span) {
        transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    INSERT INTO spans_local_v2 (
                        id,
                        workspace_id,
                        project_id,
                        trace_id,
                        parent_span_id,
                        name,
                        type,
                        start_time,
                        end_time,
                        input,
                        output,
                        metadata,
                        tags,
                        usage,
                        created_by,
                        last_updated_by,
                        model,
                        provider,
                        total_estimated_cost,
                        total_estimated_cost_version,
                        error_info,
                        truncation_threshold,
                        input_slim,
                        output_slim,
                        ttft,
                        source,
                        environment
                    )
                    SELECT
                        :id,
                        :workspace_id,
                        :project_id,
                        :trace_id,
                        :parent_span_id,
                        :name,
                        :type,
                        parseDateTime64BestEffort(:start_time, 6, 'UTC'),
                        parseDateTime64BestEffort(:end_time, 6, 'UTC'),
                        :input,
                        :output,
                        :metadata,
                        :tags,
                        mapFromArrays(:usage_keys, :usage_values),
                        :created_by,
                        :last_updated_by,
                        :model,
                        :provider,
                        toDecimal128(:total_estimated_cost, 12),
                        :total_estimated_cost_version,
                        :error_info,
                        :truncation_threshold,
                        :input_slim,
                        :output_slim,
                        :ttft,
                        :source,
                        :environment
                    """)
                    .bind("id", span.id())
                    .bind("workspace_id", span.workspaceId())
                    .bind("project_id", span.projectId())
                    .bind("trace_id", span.traceId())
                    .bind("parent_span_id",
                            SentinelTranslation.nullToEmptyUuid(
                                    span.parentSpanId() == null ? null : span.parentSpanId().toString()))
                    .bind("name", span.name())
                    .bind("type", span.type().name())
                    .bind("start_time", ClickHouseDateTimeFormat.formatMicros(span.startTime()))
                    .bind("end_time",
                            ClickHouseDateTimeFormat.formatMicros(SentinelTranslation.nullToEpoch(span.endTime())))
                    .bind("input", span.input().toString())
                    .bind("output", span.output().toString())
                    .bind("metadata", span.metadata().toString())
                    .bind("tags", span.tags().toArray(String[]::new))
                    .bind("usage_keys", span.usage().keySet().toArray(String[]::new))
                    .bind("usage_values", span.usage().values().toArray(Long[]::new))
                    .bind("created_by", span.createdBy())
                    .bind("last_updated_by", span.lastUpdatedBy())
                    .bind("model", span.model())
                    .bind("provider", span.provider())
                    .bind("total_estimated_cost", span.totalEstimatedCost().toString())
                    .bind("total_estimated_cost_version", span.totalEstimatedCostVersion())
                    .bind("error_info", JsonUtils.valueToTree(span.errorInfo()).toString())
                    .bind("truncation_threshold", span.truncationThreshold())
                    .bind("input_slim", span.inputSlim())
                    .bind("output_slim", span.outputSlim())
                    .bind("ttft", SentinelTranslation.nullToNaN(span.ttft()))
                    .bind("source", span.source().getValue())
                    .bind("environment", span.environment());
            return Mono.from(statement.execute());
        }).block();
    }

    /**
     * Writes only the ORDER BY columns (minus parent_span_id, which is defaulted too), leaving every other column to
     * its DDL default.
     */
    private void insertKeyColumnsOnly(StoredSpan span) {
        transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    INSERT INTO spans_local_v2 (id, workspace_id, project_id, trace_id)
                    SELECT :id, :workspace_id, :project_id, :trace_id
                    """)
                    .bind("id", span.id())
                    .bind("workspace_id", span.workspaceId())
                    .bind("project_id", span.projectId())
                    .bind("trace_id", span.traceId());
            return Mono.from(statement.execute());
        }).block();
    }

    /**
     * SELECT * omits MATERIALIZED columns, so the materialized ones are listed explicitly, and id_at (DateTime) is read
     * as an Instant.
     */
    private StoredSpan getById(StoredSpan span) {
        return transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT *,
                           input_length,
                           output_length,
                           metadata_length,
                           truncated_input,
                           truncated_output,
                           duration,
                           id_at
                    FROM spans_local_v2
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id = :id
                    ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                    """)
                    .bind("workspace_id", span.workspaceId())
                    .bind("project_id", span.projectId())
                    .bind("id", span.id());
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, ignored) -> mapToStoredSpan(row))));
        }).block();
    }

    /**
     * Reads a single column — including a virtual one such as {@code _partition_id} — for the row's key.
     */
    private <T> T getColumn(StoredSpan span, String column, Class<T> type) {
        return transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT %s AS value
                    FROM spans_local_v2
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id = :id
                    ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                    """.formatted(column))
                    .bind("workspace_id", span.workspaceId())
                    .bind("project_id", span.projectId())
                    .bind("id", span.id());
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("value", type))));
        }).block();
    }

    /**
     * Mirrors the DAO read path: the epoch/NaN/empty sentinels translate back to null and the JSON columns are parsed.
     */
    private StoredSpan mapToStoredSpan(Row row) {
        return StoredSpan.builder()
                .id(row.get("id", UUID.class))
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .traceId(row.get("trace_id", UUID.class))
                .parentSpanId(toUuid(SentinelTranslation.emptyUuidToNull(row.get("parent_span_id", String.class))))
                .name(row.get("name", String.class))
                .type(SpanType.fromString(row.get("type", String.class)))
                .startTime(row.get("start_time", Instant.class))
                .endTime(SentinelTranslation.epochToNull(row.get("end_time", Instant.class)))
                .input(JsonUtils.getJsonNodeFromString(row.get("input", String.class)))
                .output(JsonUtils.getJsonNodeFromString(row.get("output", String.class)))
                .metadata(JsonUtils.getJsonNodeFromString(row.get("metadata", String.class)))
                .tags(Set.of(row.get("tags", String[].class)))
                .usage(row.get("usage", Map.class))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .model(row.get("model", String.class))
                .provider(row.get("provider", String.class))
                .totalEstimatedCost(row.get("total_estimated_cost", BigDecimal.class))
                .totalEstimatedCostVersion(row.get("total_estimated_cost_version", String.class))
                .errorInfo(JsonUtils.readValue(row.get("error_info", String.class), ErrorInfo.class))
                .truncationThreshold(row.get("truncation_threshold", Long.class))
                .inputSlim(row.get("input_slim", String.class))
                .outputSlim(row.get("output_slim", String.class))
                .ttft(SentinelTranslation.nanToNull(row.get("ttft", Double.class)))
                .source(Source.fromString(row.get("source", String.class)).orElse(null))
                .environment(row.get("environment", String.class))
                .isDeleted(row.get("is_deleted", Byte.class))
                .inputLength(row.get("input_length", Long.class))
                .outputLength(row.get("output_length", Long.class))
                .metadataLength(row.get("metadata_length", Long.class))
                .truncatedInput(row.get("truncated_input", String.class))
                .truncatedOutput(row.get("truncated_output", String.class))
                .duration(SentinelTranslation.nanToNull(row.get("duration", Double.class)))
                .idAt(row.get("id_at", Instant.class))
                .build();
    }

    private UUID toUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private void assertEqual(StoredSpan actualStoredSpan, StoredSpan expectedStoredSpan) {
        assertThat(actualStoredSpan)
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "lastUpdatedAt")
                .isEqualTo(expectedStoredSpan);
        // created_at / last_updated_at are server-stamped defaults; assert they are populated near now.
        assertThat(actualStoredSpan.createdAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
        assertThat(actualStoredSpan.lastUpdatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    }

    private long toByteLength(JsonNode value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Matches the materialized duration formula: null when either bound is absent/epoch, else milliseconds.
     */
    private Double durationOf(Instant startTime, Instant endTime) {
        if (Instant.EPOCH.equals(startTime) || endTime == null || Instant.EPOCH.equals(endTime)) {
            return null;
        }
        return DurationUtils.getDurationInMillisWithSubMilliPrecision(startTime, endTime);
    }

    /**
     * UUIDv7 encodes unix milliseconds in its top 48 bits; id_at is that instant at second precision.
     */
    private Instant idAtOf(UUID id) {
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16).truncatedTo(ChronoUnit.SECONDS);
    }

    @Builder(toBuilder = true)
    private record StoredSpan(
            UUID id,
            String workspaceId,
            UUID projectId,
            UUID traceId,
            UUID parentSpanId,
            String name,
            SpanType type,
            Instant startTime,
            Instant endTime,
            JsonNode input,
            JsonNode output,
            JsonNode metadata,
            Set<String> tags,
            Map<String, Long> usage,
            Instant createdAt,
            Instant lastUpdatedAt,
            String createdBy,
            String lastUpdatedBy,
            String model,
            String provider,
            BigDecimal totalEstimatedCost,
            String totalEstimatedCostVersion,
            ErrorInfo errorInfo,
            long truncationThreshold,
            String inputSlim,
            String outputSlim,
            Double ttft,
            Source source,
            String environment,
            byte isDeleted,
            // Materialized columns below
            long inputLength,
            long outputLength,
            long metadataLength,
            String truncatedInput,
            String truncatedOutput,
            Double duration,
            Instant idAt) {
    }

    /**
     * The ClickHouse-normalized form of the 000112 migration's DDL, with the test database name substituted in.
     */
    private static final String EXPECTED_DDL = """
            CREATE TABLE %s.spans_local_v2
            (
                `id` FixedString(36) CODEC(ZSTD(1)),
                `workspace_id` String CODEC(ZSTD(3)),
                `project_id` FixedString(36) CODEC(ZSTD(1)),
                `trace_id` FixedString(36) CODEC(ZSTD(1)),
                `parent_span_id` FixedString(36) DEFAULT '' CODEC(ZSTD(1)),
                `name` String DEFAULT '' CODEC(ZSTD(3)),
                `type` Enum8('unknown' = 0, 'general' = 1, 'tool' = 2, 'llm' = 3, 'guardrail' = 4) DEFAULT 'unknown' CODEC(ZSTD(1)),
                `start_time` DateTime64(6, 'UTC') DEFAULT now64(6) CODEC(Delta(8), ZSTD(1)),
                `end_time` DateTime64(6, 'UTC') DEFAULT toDateTime64('1970-01-01 00:00:00', 6) CODEC(Delta(8), ZSTD(1)),
                `input` String DEFAULT '' CODEC(ZSTD(3)),
                `output` String DEFAULT '' CODEC(ZSTD(3)),
                `metadata` String DEFAULT '' CODEC(ZSTD(3)),
                `tags` Array(String) DEFAULT [] CODEC(ZSTD(3)),
                `usage` Map(String, Int64) DEFAULT map() CODEC(ZSTD(1)),
                `created_at` DateTime64(6, 'UTC') DEFAULT now64(6) CODEC(Delta(8), ZSTD(1)),
                `last_updated_at` DateTime64(6, 'UTC') DEFAULT now64(6) CODEC(Delta(8), ZSTD(1)),
                `created_by` String DEFAULT '' CODEC(ZSTD(3)),
                `last_updated_by` String DEFAULT '' CODEC(ZSTD(3)),
                `model` String DEFAULT '' CODEC(ZSTD(3)),
                `provider` String DEFAULT '' CODEC(ZSTD(3)),
                `total_estimated_cost` Decimal(38, 12) DEFAULT 0 CODEC(ZSTD(1)),
                `total_estimated_cost_version` String DEFAULT '' CODEC(ZSTD(3)),
                `error_info` String DEFAULT '' CODEC(ZSTD(1)),
                `truncation_threshold` UInt64 DEFAULT 10001 CODEC(ZSTD(1)),
                `input_slim` String DEFAULT '' CODEC(ZSTD(3)),
                `output_slim` String DEFAULT '' CODEC(ZSTD(3)),
                `ttft` Float64 DEFAULT toFloat64('nan') CODEC(ZSTD(1)),
                `source` Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) DEFAULT 'unknown' CODEC(ZSTD(1)),
                `environment` LowCardinality(String) DEFAULT '' CODEC(ZSTD(1)),
                `is_deleted` UInt8 DEFAULT 0,
                `input_length` UInt64 MATERIALIZED length(input) CODEC(T64, ZSTD(1)),
                `output_length` UInt64 MATERIALIZED length(output) CODEC(T64, ZSTD(1)),
                `metadata_length` UInt64 MATERIALIZED length(metadata) CODEC(T64, ZSTD(1)),
                `truncated_input` String MATERIALIZED if(length(input) >= truncation_threshold, substring(input, 1, truncation_threshold), input) CODEC(ZSTD(3)),
                `truncated_output` String MATERIALIZED if(length(output) >= truncation_threshold, substring(output, 1, truncation_threshold), output) CODEC(ZSTD(3)),
                `duration` Float64 MATERIALIZED if((end_time = toDateTime64('1970-01-01 00:00:00', 6)) OR (start_time = toDateTime64('1970-01-01 00:00:00', 6)), toFloat64('nan'), dateDiff('microsecond', start_time, end_time) / 1000.) CODEC(ZSTD(1)),
                `id_at` DateTime('UTC') MATERIALIZED UUIDv7ToDateTime(toUUID(id)) CODEC(Delta(4), ZSTD(1)),
                INDEX idx_spans_id id TYPE minmax GRANULARITY 1,
                INDEX idx_spans_id_at id_at TYPE minmax GRANULARITY 1,
                INDEX idx_spans_source source TYPE set(0) GRANULARITY 1,
                INDEX idx_spans_environment environment TYPE set(0) GRANULARITY 1,
                INDEX idx_spans_created_at created_at TYPE minmax GRANULARITY 1,
                INDEX idx_spans_last_updated_at last_updated_at TYPE minmax GRANULARITY 1
            )
            ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/%s/spans_local_v2', '{replica}', last_updated_at, is_deleted)
            PARTITION BY toMonday(id_at)
            ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id)
            SETTINGS index_granularity = 8192, index_granularity_bytes = 41943040\
            """
            .formatted(DATABASE_NAME, DATABASE_NAME);
}
