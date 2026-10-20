package com.comet.opik.domain.ailabels;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java's {@link AiLabelTask#required(Instant)} and the sweep's SQL {@link AiLabelSweepDAO#required} must agree, or the
 * sweep would ask for a version the tick never labels with (or the reverse).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiLabelTaskRequiredSqlTest {

    private static final String REQUIRED = "SELECT %s AS required FROM (SELECT 'summary' AS task, %s AS created_at)"
            .formatted(AiLabelSweepDAO.required("task", "created_at", 1),
                    "parseDateTime64BestEffort(:created_at, 6)");

    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    private ClickHouseContainer clickHouse;
    private ConnectionFactory connectionFactory;

    @BeforeAll
    void setUp() {
        clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false);
        Startables.deepStart(clickHouse).join();
        connectionFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, "default").build();
    }

    @AfterAll
    void tearDown() {
        clickHouse.stop();
    }

    @Test
    void javaAndSqlAgreeOnRandomInputs() {
        var random = new Random(20261015L);
        var cases = new ArrayList<Case>();
        for (int index = 0; index < 300; index++) {
            var task = randomTask(random);
            cases.add(new Case(task, randomCreatedAt(random, task)));
        }

        var disagreements = Flux.fromIterable(cases)
                .concatMap(testCase -> sqlRequired(testCase)
                        .filter(sql -> sql != testCase.task().required(testCase.createdAt()))
                        .map(sql -> "%s at '%s': java '%d', sql '%d'".formatted(testCase.task().versions(),
                                testCase.createdAt(), testCase.task().required(testCase.createdAt()), sql)))
                .collectList()
                .block();

        assertThat(disagreements).isEmpty();
        assertThat(cases).extracting(testCase -> testCase.task().required(testCase.createdAt()))
                .as("the random inputs cover both 'no version applies' and a real version")
                .contains(0, 1, 2);
    }

    /** created_at has microseconds: one in the microsecond before a sub-microsecond applies_from is before it. */
    @Test
    void subMicrosecondAppliesFromAgrees() {
        var appliesFrom = BASE.plusNanos(500);
        var task = AiLabelTaskFixtures.generate("summary", false).toBuilder()
                .version(1)
                .appliesFrom(appliesFrom)
                .build();
        var cases = List.of(new Case(task, BASE), new Case(task, BASE.plus(1, ChronoUnit.MICROS)));

        var required = Flux.fromIterable(cases).concatMap(this::sqlRequired).collectList().block();

        assertThat(required).containsExactly(0, 1);
        assertThat(cases).extracting(testCase -> testCase.task().required(testCase.createdAt()))
                .containsExactly(0, 1);
    }

    private Mono<Integer> sqlRequired(Case testCase) {
        return Mono.usingWhen(Mono.from(connectionFactory.create()),
                connection -> Mono.from(AiLabelSweepDAO.bindTasks(connection.createStatement(REQUIRED),
                        List.of(AiLabelSweepDAO.SweepTask.of(testCase.task())))
                        .bind("created_at", testCase.createdAt().toString())
                        .execute())
                        .flatMap(result -> Mono.from(result.map(
                                (row, metadata) -> row.get("required", Number.class).intValue()))),
                connection -> Mono.from(connection.close()));
    }

    /** 1 to 4 versions; each applies_from may move earlier than the one before it, as an explicit relabel does. */
    private static AiLabelTask randomTask(Random random) {
        var versions = 1 + random.nextInt(4);
        var history = new ArrayList<AiLabelTask.Version>();
        for (int version = 1; version < versions; version++) {
            history.add(new AiLabelTask.Version(version, randomInstant(random)));
        }
        return AiLabelTaskFixtures.generate("summary", false).toBuilder()
                .version(versions)
                .appliesFrom(randomInstant(random))
                .history(history)
                .build();
    }

    /** Often exactly at an applies_from, or a microsecond either side of it: the boundaries. */
    private static Instant randomCreatedAt(Random random, AiLabelTask task) {
        var versions = task.versions();
        var pick = versions.get(random.nextInt(versions.size())).appliesFrom();
        return switch (random.nextInt(4)) {
            case 0 -> pick;
            case 1 -> pick.minus(1, ChronoUnit.MICROS);
            case 2 -> pick.plus(1, ChronoUnit.MICROS);
            default -> randomInstant(random);
        };
    }

    private static Instant randomInstant(Random random) {
        return BASE.plus(random.nextLong(60L * 24 * 3600 * 1_000_000), ChronoUnit.MICROS);
    }

    private record Case(AiLabelTask task, Instant createdAt) {
    }
}
