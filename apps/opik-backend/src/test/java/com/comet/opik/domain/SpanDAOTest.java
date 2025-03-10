package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanSearchCriteria;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.sorting.SpanSortingFactory;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.uuid.Generators;
import com.google.common.testing.NullPointerTester;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Disabled
class SpanDAOTest {

    private static final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();

    private static SpanDAO spanDAO;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @BeforeAll
    static void beforeAll() throws SQLException {
        CLICK_HOUSE_CONTAINER.start();
        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, MigrationUtils.CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        ConnectionFactory factory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICK_HOUSE_CONTAINER, ClickHouseContainerUtils.DATABASE_NAME)
                .build();

        spanDAO = new SpanDAO(
                factory,
                new FeedbackScoreDAOImpl(TransactionTemplateAsync.create(factory)),
                new FilterQueryBuilder(),
                new SpanSortingFactory(),
                new SortingQueryBuilder());
    }

    @AfterAll
    static void afterAll() {
        CLICK_HOUSE_CONTAINER.stop();
    }

    @Test
    void allPublicConstructors() {
        var nullPointerTester = new NullPointerTester();
        nullPointerTester.testAllPublicConstructors(SpanDAO.class);
    }

    @Test
    void allPublicInstanceMethods() {
        var nullPointerTester = new NullPointerTester();
        nullPointerTester.testAllPublicInstanceMethods(spanDAO);
    }

    @Test
    void insertAndGetById() {
        var expectedSpan = podamFactory.manufacturePojo(Span.class);
        var actualSpan = spanDAO.insert(expectedSpan)
                .then(Mono.defer(() -> spanDAO.getById(expectedSpan.id())))
                .block();

        assertThat(actualSpan).isEqualTo(expectedSpan);
    }

    @Test
    void insertNullableFields() {
        var expectedSpan = podamFactory.manufacturePojo(Span.class).toBuilder().endTime(null).build();
        var actualSpan = spanDAO.insert(expectedSpan)
                .then(Mono.defer(() -> spanDAO.getById(expectedSpan.id())))
                .block();

        assertThat(actualSpan).isEqualTo(expectedSpan);
    }

    @Test
    void getByIdDeduplicatesLastUpdated() {
        var unexpectedSpan = podamFactory.manufacturePojo(Span.class);
        var expectedSpan = unexpectedSpan.toBuilder()
                .lastUpdatedAt(unexpectedSpan.lastUpdatedAt().plusSeconds(60))
                .build();
        var actualSpan = spanDAO.insert(unexpectedSpan)
                .then(Mono.defer(() -> spanDAO.insert(expectedSpan)))
                .then(Mono.defer(() -> spanDAO.getById(expectedSpan.id())))
                .block();

        assertThat(actualSpan).isEqualTo(expectedSpan);
        assertThat(actualSpan).isNotEqualTo(unexpectedSpan);
    }

    @Test
    void getByIdReturnsEmpty() {
        var actualSpan = spanDAO.getById(Generators.timeBasedEpochGenerator().generate()).block();

        assertThat(actualSpan).isNull();
    }

    @Test
    void insertAndFindByTraceId() {
        var traceId = Generators.timeBasedEpochGenerator().generate();
        var expectedSpans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                .stream()
                .map(span -> span.toBuilder().traceId(traceId).build())
                .toList();
        var unexpectedSpan = podamFactory.manufacturePojo(Span.class);

        var actualSpans = Flux.fromIterable(expectedSpans)
                .flatMap(spanDAO::insert)
                .then(Mono.defer(() -> spanDAO.insert(unexpectedSpan)))
                .thenMany(spanDAO.find(1, expectedSpans.size(), SpanSearchCriteria.builder().traceId(traceId).build()))
                .singleOrEmpty()
                .block();

        assertThat(actualSpans.content()).containsExactlyElementsOf(expectedSpans.reversed());
        assertThat(actualSpans.content()).doesNotContain(unexpectedSpan);
    }

    @Test
    void findByTraceIdDeduplicatesLastUpdated() {
        var traceId = Generators.timeBasedEpochGenerator().generate();;
        var unexpectedSpans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                .stream()
                .map(span -> span.toBuilder().traceId(traceId).build())
                .toList();
        var expectedSpans = unexpectedSpans.stream()
                .map(span -> span.toBuilder().lastUpdatedAt(span.lastUpdatedAt().plusSeconds(1)).build())
                .toList();

        var actualSpans = Flux.fromIterable(Stream.concat(unexpectedSpans.stream(), expectedSpans.stream()).toList())
                .flatMap(spanDAO::insert)
                .thenMany(spanDAO.find(1, expectedSpans.size(), SpanSearchCriteria.builder().traceId(traceId).build()))
                .singleOrEmpty()
                .block();

        assertThat(actualSpans.content()).containsExactlyElementsOf(expectedSpans.reversed());
        assertThat(actualSpans.content()).doesNotContainAnyElementsOf(unexpectedSpans);
    }

    @Test
    void findByTraceIdReturnsEmpty() {
        var actualSpans = spanDAO
                .find(1, 1,
                        SpanSearchCriteria.builder().traceId(Generators.timeBasedEpochGenerator().generate()).build())
                .block();

        assertThat(actualSpans.content()).isEmpty();
    }
}
