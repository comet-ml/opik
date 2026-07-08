package com.comet.opik.domain;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Source;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.SentinelTranslation;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Row;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.utils.TruncationUtils.createSlimJsonString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Round-trips a row through the traces_local_v2 table at the column level: {@link StoredTrace} mirrors the DDL (in
 * migration order) but holds the domain representations, and the insert/read helpers apply the same sentinel
 * translation and JSON (de)serialization the DAO will use at cutover — so an absent end_time/ttft is written as the
 * epoch/NaN sentinel and read back as null. This validates the table itself (microsecond precision, the sentinels,
 * the materialized columns, the UUIDv7-derived partition key) and the cutover mapping against it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesLocalV2TableTest {

    /**
     * The traces_local_v2 DEFAULT; bound on insert, and one test lowers it to force the truncated_* columns.
     */
    private static final long DEFAULT_TRUNCATION_THRESHOLD = 10_001;

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mySQLContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);

    private final WireMockRuntime wireMock;

    private final TransactionTemplateAsync transactionTemplateAsync;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mySQLContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mySQLContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        transactionTemplateAsync = TransactionTemplateAsync.create(databaseAnalyticsFactory.build());
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(mySQLContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(redisContainer.getRedisURI())
                        .build());
    }

    /**
     * Every enum value must exist in the DDL Enum8; a missing one fails the insert.
     * Covering each value deterministically guards that parity, where a single random Podam pick would not.
     */
    @ParameterizedTest
    @EnumSource(Source.class)
    void allColumnsRoundTrip(Source source) {
        var startTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var endTime = randomFutureInstantFrom(startTime);
        var expectedStoredTrace = newStoredTrace(startTime, endTime, DEFAULT_TRUNCATION_THRESHOLD).toBuilder()
                .source(source)
                .build();
        insert(expectedStoredTrace);

        var actualStoredTrace = getById(expectedStoredTrace);

        assertEqual(actualStoredTrace, expectedStoredTrace);
    }

    @ParameterizedTest
    @EnumSource(VisibilityMode.class)
    void allAbsentColumnsRoundTripAsNull(VisibilityMode visibilityMode) {
        var startTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var expectedStoredTrace = newStoredTrace(startTime, null, DEFAULT_TRUNCATION_THRESHOLD).toBuilder()
                .ttft(null)
                .visibilityMode(visibilityMode)
                .build();
        insert(expectedStoredTrace);

        var actualStoredTrace = getById(expectedStoredTrace);

        assertEqual(actualStoredTrace, expectedStoredTrace);
        assertThat(actualStoredTrace.endTime()).isNull();
        assertThat(actualStoredTrace.ttft()).isNull();
        assertThat(actualStoredTrace.duration()).isNull();
    }

    @Test
    void epochStartTimeYieldsNullDuration() {
        var endTime = randomFutureInstantFrom(Instant.now().truncatedTo(ChronoUnit.MICROS));
        // start_time at the epoch sentinel: the duration guard must yield null even though end_time is present.
        var expectedStoredTrace = newStoredTrace(Instant.EPOCH, endTime, DEFAULT_TRUNCATION_THRESHOLD);
        insert(expectedStoredTrace);

        var actualStoredTrace = getById(expectedStoredTrace);

        assertEqual(actualStoredTrace, expectedStoredTrace);
        assertThat(actualStoredTrace.duration()).isNull();
    }

    @Test
    void inputAndOutputTruncatedIfAboveThreshold() {
        var startTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var endTime = randomFutureInstantFrom(startTime);
        // A threshold well below the payload size so the truncated_* materialized columns actually truncate.
        var expectedStoredTrace = newStoredTrace(startTime, endTime, 10L);
        insert(expectedStoredTrace);

        var actualStoredTrace = getById(expectedStoredTrace);

        assertEqual(actualStoredTrace, expectedStoredTrace);
        assertThat(actualStoredTrace.truncatedInput()).hasSize(10).isNotEqualTo(actualStoredTrace.input().toString());
        assertThat(actualStoredTrace.truncatedOutput()).hasSize(10).isNotEqualTo(actualStoredTrace.output().toString());
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
        var storedTrace = newStoredTrace(startTime, endTime, DEFAULT_TRUNCATION_THRESHOLD).toBuilder()
                .id(id)
                .idAt(idAt)
                .build();
        insert(storedTrace);

        var partitionId = getPartitionId(storedTrace);

        // PARTITION BY toMonday(id_at): the row must land in the partition for the Monday of the week its UUIDv7 id
        // encodes — not the current week. Backdated ids prove the partition follows id_at, not wall-clock (the reason
        // the design chose an id-derived key over created_at). ClickHouse names a Date partition YYYYMMDD.
        var expectedMonday = idAt.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
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
    private StoredTrace newStoredTrace(Instant startTime, Instant endTime, long truncationThreshold) {
        var id = ID_GENERATOR.generateId();
        var user = randomString("user");
        var input = podamFactory.manufacturePojo(JsonNode.class);
        var output = podamFactory.manufacturePojo(JsonNode.class);
        var metadata = podamFactory.manufacturePojo(JsonNode.class);
        return podamFactory.manufacturePojo(StoredTrace.class).toBuilder()
                .id(id)
                .workspaceId(UUID.randomUUID().toString())
                .projectId(ID_GENERATOR.generateId())
                .startTime(startTime)
                .endTime(endTime)
                .input(input)
                .output(output)
                .metadata(metadata)
                .createdBy(user)
                .lastUpdatedBy(user)
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
                .outputKeys(outputKeysOf(output))
                .duration(durationOf(startTime, endTime))
                .idAt(idAtOf(id))
                .build();
    }

    private String randomString(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }

    /**
     * Mirrors the truncated_* materialized formula: the first `threshold` bytes once the value reaches it. Podam JSON
     * is ASCII, so a char-based substring matches ClickHouse's byte-based substring.
     */
    private String truncate(String value, long threshold) {
        return value.length() >= threshold ? value.substring(0, (int) threshold) : value;
    }

    private void insert(StoredTrace trace) {
        transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    INSERT INTO traces_local_v2 (
                        id,
                        workspace_id,
                        project_id,
                        name,
                        start_time,
                        end_time,
                        input,
                        output,
                        metadata,
                        tags,
                        created_by,
                        last_updated_by,
                        error_info,
                        thread_id,
                        visibility_mode,
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
                        :name,
                        parseDateTime64BestEffort(:start_time, 6, 'UTC'),
                        parseDateTime64BestEffort(:end_time, 6, 'UTC'),
                        :input,
                        :output,
                        :metadata,
                        :tags,
                        :created_by,
                        :last_updated_by,
                        :error_info,
                        :thread_id,
                        :visibility_mode,
                        :truncation_threshold,
                        :input_slim,
                        :output_slim,
                        :ttft,
                        :source,
                        :environment
                    """)
                    .bind("id", trace.id())
                    .bind("workspace_id", trace.workspaceId())
                    .bind("project_id", trace.projectId())
                    .bind("name", trace.name())
                    .bind("start_time", ClickHouseDateTimeFormat.formatMicros(trace.startTime()))
                    .bind("end_time",
                            ClickHouseDateTimeFormat.formatMicros(SentinelTranslation.nullToEpoch(trace.endTime())))
                    .bind("input", trace.input().toString())
                    .bind("output", trace.output().toString())
                    .bind("metadata", trace.metadata().toString())
                    .bind("tags", trace.tags().toArray(String[]::new))
                    .bind("created_by", trace.createdBy())
                    .bind("last_updated_by", trace.lastUpdatedBy())
                    .bind("error_info", JsonUtils.valueToTree(trace.errorInfo()).toString())
                    .bind("thread_id", trace.threadId())
                    .bind("visibility_mode", trace.visibilityMode().getValue())
                    .bind("truncation_threshold", trace.truncationThreshold())
                    .bind("input_slim", trace.inputSlim())
                    .bind("output_slim", trace.outputSlim())
                    .bind("ttft", SentinelTranslation.nullToNaN(trace.ttft()))
                    .bind("source", trace.source().getValue())
                    .bind("environment", trace.environment());
            return Mono.from(statement.execute());
        }).block();
    }

    /**
     * SELECT * omits MATERIALIZED columns, so the materialized ones are listed explicitly; output_keys (Array(Tuple))
     * is split into two Array(String) projections and id_at (DateTime) is read as an Instant.
     */
    private StoredTrace getById(StoredTrace trace) {
        return transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT *,
                           input_length,
                           output_length,
                           metadata_length,
                           truncated_input,
                           truncated_output,
                           arrayMap(entry -> entry.1, output_keys) AS output_key_names,
                           arrayMap(entry -> entry.2, output_keys) AS output_key_types,
                           duration,
                           id_at
                    FROM traces_local_v2
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id = :id
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                    """)
                    .bind("workspace_id", trace.workspaceId())
                    .bind("project_id", trace.projectId())
                    .bind("id", trace.id);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, ignored) -> mapToStoredTrace(row))));
        }).block();
    }

    private String getPartitionId(StoredTrace trace) {
        return transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT _partition_id AS partition_id
                    FROM traces_local_v2
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id = :id
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                    """)
                    .bind("workspace_id", trace.workspaceId())
                    .bind("project_id", trace.projectId())
                    .bind("id", trace.id());
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("partition_id", String.class))));
        }).block();
    }

    /**
     * Mirrors the DAO read path: the epoch/NaN sentinels translate back to null and the JSON columns are parsed.
     */
    private StoredTrace mapToStoredTrace(Row row) {
        return StoredTrace.builder()
                .id(row.get("id", UUID.class))
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .name(row.get("name", String.class))
                .startTime(row.get("start_time", Instant.class))
                .endTime(SentinelTranslation.epochToNull(row.get("end_time", Instant.class)))
                .input(JsonUtils.getJsonNodeFromString(row.get("input", String.class)))
                .output(JsonUtils.getJsonNodeFromString(row.get("output", String.class)))
                .metadata(JsonUtils.getJsonNodeFromString(row.get("metadata", String.class)))
                .tags(Set.of(row.get("tags", String[].class)))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .errorInfo(JsonUtils.readValue(row.get("error_info", String.class), ErrorInfo.class))
                .threadId(row.get("thread_id", String.class))
                .visibilityMode(VisibilityMode.fromString(row.get("visibility_mode", String.class)).orElse(null))
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
                .outputKeys(toOutputKeys(
                        row.get("output_key_names", String[].class),
                        row.get("output_key_types", String[].class)))
                .duration(SentinelTranslation.nanToNull(row.get("duration", Double.class)))
                .idAt(row.get("id_at", Instant.class))
                .build();
    }

    private void assertEqual(StoredTrace actualStoredTrace, StoredTrace expectedStoredTrace) {
        assertThat(actualStoredTrace)
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "lastUpdatedAt")
                .isEqualTo(expectedStoredTrace);
        // created_at / last_updated_at are server-stamped defaults; assert they are populated near now.
        assertThat(actualStoredTrace.createdAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
        assertThat(actualStoredTrace.lastUpdatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    }

    private long toByteLength(JsonNode value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Podam builds each JSON as a flat object of string values, so every key's JSONType is String.
     */
    private Map<String, String> outputKeysOf(JsonNode output) {
        var keys = new LinkedHashMap<String, String>();
        output.fieldNames().forEachRemaining(name -> keys.put(name, "String"));
        return keys;
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

    private Map<String, String> toOutputKeys(String[] names, String[] types) {
        var keys = new LinkedHashMap<String, String>();
        for (int i = 0; i < names.length; i++) {
            keys.put(names[i], types[i]);
        }
        return keys;
    }

    @Builder(toBuilder = true)
    private record StoredTrace(
            UUID id,
            String workspaceId,
            UUID projectId,
            String name,
            Instant startTime,
            Instant endTime,
            JsonNode input,
            JsonNode output,
            JsonNode metadata,
            Set<String> tags,
            Instant createdAt,
            Instant lastUpdatedAt,
            String createdBy,
            String lastUpdatedBy,
            ErrorInfo errorInfo,
            String threadId,
            VisibilityMode visibilityMode,
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
            Map<String, String> outputKeys,
            Double duration,
            Instant idAt) {
    }
}
