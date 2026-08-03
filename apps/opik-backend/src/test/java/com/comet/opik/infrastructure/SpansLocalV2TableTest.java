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
import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;
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

    /**
     * The two halves of the root-span sentinel contract, which differ and are easy to conflate. ClickHouse stores the
     * empty {@code FixedString(36)} NUL-padded, but trims the padding when casting to String — so the DAO's
     * {@code LENGTH(CAST(parent_span_id AS Nullable(String))) > 0} presence checks behave on this column exactly as they
     * do on the live String one, while a bare {@code LENGTH} would always be 36. The driver, by contrast, hands Java the
     * padded form, and NUL is not whitespace, so a {@code !isBlank()} guard does not catch it and {@code UUID.fromString}
     * throws: reads must go through {@link SentinelTranslation#emptyUuidToNull(String)}.
     */
    @Test
    void rootSpanSentinelCastsToEmptyInSqlButReachesJavaNulPadded() {
        var storedSpan = newStoredSpan(Instant.now().truncatedTo(ChronoUnit.MICROS), null,
                DEFAULT_TRUNCATION_THRESHOLD).toBuilder()
                .parentSpanId(null)
                .build();
        insert(storedSpan);

        assertThat(getColumn(storedSpan, "LENGTH(CAST(parent_span_id AS Nullable(String)))", Long.class)).isZero();
        // Plain String, without the Nullable wrapper, trims the padding the same way: it is the FixedString -> String
        // conversion that drops it, so neither form of the DAO's predicate sees a length of 36.
        assertThat(getColumn(storedSpan, "LENGTH(CAST(parent_span_id AS String))", Long.class)).isZero();
        assertThat(getColumn(storedSpan, "LENGTH(parent_span_id)", Long.class)).isEqualTo(36);

        var raw = getColumn(storedSpan, "parent_span_id", String.class);
        assertThat(raw).isEqualTo(CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE).isNotBlank();
        assertThat(SentinelTranslation.emptyUuidToNull(raw)).isNull();
    }

    /**
     * Prod already holds ids whose UUIDv7 timestamp is dated in 2199, past the 2106-02-07 ceiling of ClickHouse's
     * {@code DateTime}, which wraps rather than failing: the same id read through {@code DateTime} comes back as
     * 2063-05-08. id_at is {@code DateTime64} so the instant the id encodes survives instead.
     */
    @Test
    void farFutureIdBeyondDateTimeCeilingKeepsItsInstant() {
        var farFuture = Instant.parse("2199-06-15T00:00:00Z");
        var id = ID_GENERATOR.generateId(farFuture);
        var startTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var storedSpan = newStoredSpan(startTime, randomFutureInstantFrom(startTime), DEFAULT_TRUNCATION_THRESHOLD)
                .toBuilder()
                .id(id)
                .idAt(idAtOf(id))
                .build();
        insert(storedSpan);

        assertThat(getColumn(storedSpan, "id_at", Instant.class)).isEqualTo(farFuture);
        assertThat(getColumn(storedSpan, "CAST(id_at AS DateTime('UTC'))", Instant.class)).isNotEqualTo(farFuture);
    }

    /**
     * id_at is DateTime64(0): whole seconds, so the sub-second part of the id's timestamp is dropped, not carried.
     */
    @Test
    void idAtIsStoredAtSecondPrecision() {
        var withMillis = Instant.parse("2026-03-04T05:06:07.891Z");
        var id = ID_GENERATOR.generateId(withMillis);
        var startTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var storedSpan = newStoredSpan(startTime, randomFutureInstantFrom(startTime), DEFAULT_TRUNCATION_THRESHOLD)
                .toBuilder()
                .id(id)
                .idAt(idAtOf(id))
                .build();
        insert(storedSpan);

        assertThat(getColumn(storedSpan, "id_at", Instant.class))
                .isEqualTo(withMillis.truncatedTo(ChronoUnit.SECONDS));
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

        // PARTITION BY the honest Date32 weekly Monday of id_at: the row must land in the partition for the Monday of
        // the week its UUIDv7 id encodes — not the current week. Backdated ids prove the partition follows id_at, not
        // wall-clock (the reason the design chose an id-derived key over created_at). The partition id is that Monday's
        // YYYYMMDD.
        var expectedMonday = idAt.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(partitionId).isEqualTo(expectedMonday.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    /**
     * The far-future id guard (OPIK_7456), the spans counterpart of {@code TracesLocalV2TableTest}. A litellm bug
     * (BerriAI/litellm#31294) mints UUIDv7 ids whose embedded timestamp is ~2201, and prod already holds ids dated 2199
     * — the rows are legitimate customer data, just carrying a future timestamp. {@code id_at} as {@code DateTime64(0)}
     * makes it read back as the honest 2201 (so the {@code id_at > now()} audit surfaces it, where a 32-bit
     * {@code DateTime} would wrap it into a plausible past year), and the honest {@code Date32} weekly partition places
     * the row in its own honest ~2201 week — not the recent week a 16-bit {@code toMonday} would fold it into, where a
     * per-week DROP PARTITION / retention / tiering operation would then touch it alongside real rows.
     * <p>
     * The partition is what this adds over {@link #farFutureIdBeyondDateTimeCeilingKeepsItsInstant}, which covers the
     * {@code id_at} column itself. The {@code id_at} assertions here are still not redundant with it: the expected
     * Monday is derived from the value read back, so without anchoring that value to 2201 the partition assertion would
     * pass just as happily if {@code id_at} and the partition wrapped together.
     */
    @Test
    void farFutureIdLandsInHonestPartitionNotAWrappedYear() {
        var farFutureId = ID_GENERATOR.generateId(Instant.parse("2201-06-01T00:00:00Z"));
        var startTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var storedSpan = newStoredSpan(startTime, randomFutureInstantFrom(startTime), DEFAULT_TRUNCATION_THRESHOLD)
                .toBuilder()
                .id(farFutureId)
                .idAt(idAtOf(farFutureId))
                .build();
        insert(storedSpan);

        var idAt = getById(storedSpan).idAt();
        var partitionId = getColumn(storedSpan, "_partition_id", String.class);

        // id_at is the honest 2201, not a wrapped year — and it is in the future, so the audit catches it.
        assertThat(idAt.atZone(ZoneOffset.UTC).getYear()).isEqualTo(2201);
        assertThat(idAt).isEqualTo(idAtOf(farFutureId)).isAfter(Instant.now());
        // The partition is the honest 2201 Monday (YYYYMMDD ~22010601), not the recent week a 16-bit toMonday would
        // wrap it into — the Date32 weekly expression never wraps.
        var expectedMonday = idAt.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(expectedMonday.getYear()).isEqualTo(2201);
        assertThat(partitionId).isEqualTo(expectedMonday.format(DateTimeFormatter.BASIC_ISO_DATE));
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
     * A positive cost at the column's exact scale, so {@code Decimal(38, 12)} neither rounds nor rescales it. The
     * unscaled value spans a whole-unit part and all twelve fractional digits, so the round-trip exercises the scale
     * rather than a value whose leading fraction digits are always zero.
     */
    private BigDecimal randomCost() {
        var units = RandomUtils.secure().randomLong(1, 1_000L);
        var fraction = RandomUtils.secure().randomLong(1, 1_000_000_000_000L);
        return BigDecimal.valueOf(units * 1_000_000_000_000L + fraction, 12);
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
                    .bind("parent_span_id", SentinelTranslation.nullToEmptyUuid(span.parentSpanId()))
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
     * asterisk_include_materialized_columns makes SELECT * cover the materialized columns too, so the projection does not
     * have to repeat the DDL's list and cannot drift from it.
     */
    private StoredSpan getById(StoredSpan span) {
        return transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT *
                    FROM spans_local_v2
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id = :id
                    ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                    SETTINGS asterisk_include_materialized_columns = 1
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
                    ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
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
                .parentSpanId(SentinelTranslation.emptyUuidToNullableUuid(row.get("parent_span_id", String.class)))
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

}
