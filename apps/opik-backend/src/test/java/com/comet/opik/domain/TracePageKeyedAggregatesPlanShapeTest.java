package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.utils.template.TemplateUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Plan-shape guard for the page-keyed aggregate mode of {@code TraceDAOImpl#SELECT_BY_PROJECT_ID}.
 * <p>
 * Functional tests assert result correctness, but the point of {@code page_keyed_aggregates} is a plan
 * property: the aggregate CTEs (spans, feedback scores, comments, guardrails, annotation queues,
 * experiments) must perform page-sized, primary-key-pruned lookups instead of whole-project scans, and the
 * {@code (SELECT groupArray(id) FROM page_ids)} scalar must be evaluated once and cached rather than re-run
 * per reference site. Both degrade silently — results stay correct while cost regresses to whole-project
 * scans — so this test renders the real template against the real migrated schema and asserts the plan
 * shape from {@code system.query_log}.
 * <p>
 * Seeded so the two failure modes are unmistakable: a small trace set (the pagination source) and a large
 * span set. If pruning breaks, the query reads the whole span table for the project and blows through the
 * read-rows budget; if scalar caching breaks, the cache-hit counter drops to zero.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracePageKeyedAggregatesPlanShapeTest {

    private static final int TRACE_COUNT = 500;
    private static final int SPANS_PER_TRACE = 600;
    private static final int SPAN_COUNT = TRACE_COUNT * SPANS_PER_TRACE;
    private static final int PAGE_SIZE = 10;

    // page-keyed predicate sites per aggregate CTE: feedback_scores_deduped and experiments_agg key two
    // scans each (legacy + authored scores; experiment_items twice), the rest one
    private static final Map<String, Integer> PAGE_KEYED_SITES_PER_CTE = Map.of(
            "feedback_scores_deduped", 2,
            "guardrails_agg", 1,
            "target_spans", 1,
            "spans_agg", 1,
            "comments_agg", 1,
            "trace_annotation_queue_ids", 1,
            "experiments_agg", 2);

    // per-run identifiers: the containers are shared/reused across tests and runs, so the seeded data and
    // the query_log lookup must not collide with previous executions
    private final String workspaceId = "plan-shape-" + UUID.randomUUID();
    private final String projectId = UUID.randomUUID().toString();
    private final String logComment = "trace-page-keyed-plan-shape-" + UUID.randomUUID();

    private final GenericContainer<?> zooKeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zooKeeperContainer);

    private Connection connection;

    @BeforeAll
    void setUp() throws SQLException {
        Startables.deepStart(zooKeeperContainer, clickHouseContainer).join();
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        connection = clickHouseContainer.createConnection("");
        seedData();
    }

    @AfterAll
    void tearDown() throws SQLException {
        // the containers are reusable and shared across tests; ClickHouseContainerUtils' shutdown hook
        // owns their lifecycle, so only the connection is closed here
        if (connection != null) {
            connection.close();
        }
    }

    private void seedData() throws SQLException {
        // small trace set: the pagination source; ids are zero-padded so the page is the highest ids
        execute("""
                INSERT INTO %s.traces (id, workspace_id, project_id, name)
                SELECT
                    toFixedString(concat('00000000-0000-0000-0001-', leftPad(toString(number), 12, '0')), 36),
                    '%s', toFixedString('%s', 36), concat('trace-', toString(number))
                FROM numbers(%d)
                """.formatted(DATABASE_NAME, workspaceId, projectId, TRACE_COUNT));

        // large span population across all traces of the project: whole-project aggregate scans
        // (target_spans / spans_agg) have to read all of it, page-keyed scans only the page's slice
        execute("""
                INSERT INTO %s.spans (id, workspace_id, project_id, trace_id, name, type)
                SELECT
                    toFixedString(concat('00000000-0000-0000-0002-', leftPad(toString(number), 12, '0')), 36),
                    '%s', toFixedString('%s', 36),
                    toFixedString(concat('00000000-0000-0000-0001-', leftPad(toString(number %% %d), 12, '0')), 36),
                    concat('span-', toString(number)), 'general'
                FROM numbers(%d)
                """.formatted(DATABASE_NAME, workspaceId, projectId, TRACE_COUNT, SPAN_COUNT));

        // trace-level feedback scores (legacy + authored) and comments for every trace: those aggregate CTE
        // paths must also be exercised so their pruning regressions surface in the plan-shape assertions
        execute("""
                INSERT INTO %s.feedback_scores (entity_id, entity_type, project_id, workspace_id, name, value, source)
                SELECT
                    toFixedString(concat('00000000-0000-0000-0001-', leftPad(toString(number), 12, '0')), 36),
                    'trace', toFixedString('%s', 36), '%s', 'accuracy', toDecimal32(0.5, 4), 'sdk'
                FROM numbers(%d)
                """.formatted(DATABASE_NAME, projectId, workspaceId, TRACE_COUNT));
        execute("""
                INSERT INTO %s.authored_feedback_scores
                    (entity_id, entity_type, project_id, workspace_id, author, name, value, source)
                SELECT
                    toFixedString(concat('00000000-0000-0000-0001-', leftPad(toString(number), 12, '0')), 36),
                    'trace', toFixedString('%s', 36), '%s', 'author', 'relevance', toDecimal64(0.7, 9), 'ui'
                FROM numbers(%d)
                """.formatted(DATABASE_NAME, projectId, workspaceId, TRACE_COUNT));
        execute("""
                INSERT INTO %s.comments (id, entity_id, entity_type, project_id, workspace_id, text)
                SELECT
                    toFixedString(concat('00000000-0000-0000-0003-', leftPad(toString(number), 12, '0')), 36),
                    toFixedString(concat('00000000-0000-0000-0001-', leftPad(toString(number), 12, '0')), 36),
                    'trace', toFixedString('%s', 36), '%s', concat('comment-', toString(number))
                FROM numbers(%d)
                """.formatted(DATABASE_NAME, projectId, workspaceId, TRACE_COUNT));
    }

    @Test
    void pageKeyedAggregatesReadPageSizedSlicesAndCacheThePageIdScalar() throws SQLException {
        var query = renderPageKeyedQuery();
        assertThat(query).doesNotContain("trace_id_prefilter");
        // every aggregate CTE must carry the page-keyed predicate at its expected sites; asserting per CTE
        // block (not a global count) catches a drop in one CTE masked by a duplicate in another
        var pageKeyedPredicate = "IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))";
        PAGE_KEYED_SITES_PER_CTE.forEach((cte, sites) -> assertThat(
                StringUtils.countMatches(cteBlock(query, cte), pageKeyedPredicate))
                .as("page-keyed predicate sites in CTE '%s'", cte)
                .isEqualTo(sites));
        assertThat(StringUtils.countMatches(query, pageKeyedPredicate))
                .as("total page-keyed predicate sites")
                .isEqualTo(9);

        int resultRows = 0;
        try (var statement = connection.createStatement()) {
            var resultSet = statement.executeQuery(query);
            while (resultSet.next()) {
                resultRows++;
            }
        }
        assertThat(resultRows).isEqualTo(PAGE_SIZE);

        List<Long> planShape = queryLogRow(
                "read_rows, ProfileEvents['ScalarSubqueriesGlobalCacheHit'] + ProfileEvents['ScalarSubqueriesLocalCacheHit']");
        long readRows = planShape.get(0);
        long scalarCacheHits = planShape.get(1);

        // Page-sized plan: the pagination source (traces) plus the page's pruned span granules. A regression
        // to whole-project aggregate scans reads all SPAN_COUNT rows.
        assertThat(readRows)
                .as("rows read by the page-keyed trace page query")
                .isLessThan(SPAN_COUNT / 3);

        // The page-id scalar must be evaluated once and served from the cache at the remaining reference
        // sites; zero hits means every aggregate CTE re-runs the pagination scan.
        assertThat(scalarCacheHits)
                .as("scalar subquery cache hits for the page-id set")
                .isGreaterThanOrEqualTo(2);
    }

    private String renderPageKeyedQuery() {
        var template = TemplateUtils.newST(TraceDAOImpl.SELECT_BY_PROJECT_ID);
        template.add("page_keyed_aggregates", true);
        template.add("log_comment", logComment);
        var rendered = template.render()
                .replace(":workspace_id", "'%s'".formatted(workspaceId))
                .replace(":project_id", "'%s'".formatted(projectId))
                .replace(":limit", String.valueOf(PAGE_SIZE));
        // qualify the bare table names against the migrated database; the trailing word-boundary guard keeps
        // CTE references (traces_deduped, spans_agg, feedback_scores_grouped, ...) untouched
        for (var table : List.of("traces", "spans", "authored_feedback_scores", "feedback_scores", "guardrails",
                "comments", "annotation_queue_items", "annotation_queues", "experiment_items",
                "dataset_item_versions", "experiments")) {
            rendered = rendered.replaceAll(
                    "(FROM|JOIN) %s(?![\\w.])".formatted(table),
                    "$1 %s.%s".formatted(DATABASE_NAME, table));
        }
        return rendered;
    }

    /**
     * Slices the rendered query down to one named CTE definition: from {@code <name> AS (} up to the next
     * CTE definition (or the end of the query), so predicate assertions are scoped per CTE block.
     */
    private String cteBlock(String query, String cteName) {
        var startMarker = cteName + " AS (";
        var start = query.indexOf(startMarker);
        assertThat(start).as("CTE '%s' present in the rendered query", cteName).isNotNegative();
        var nextCte = java.util.regex.Pattern.compile("\\w+ AS \\(").matcher(query);
        var end = query.length();
        while (nextCte.find()) {
            if (nextCte.start() > start + startMarker.length()) {
                end = nextCte.start();
                break;
            }
        }
        return query.substring(start, end);
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<Long> queryLogRow(String columns) {
        var row = new AtomicReference<List<Long>>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    execute("SYSTEM FLUSH LOGS");
                    try (var statement = connection.createStatement()) {
                        var resultSet = statement.executeQuery("""
                                SELECT %s FROM system.query_log
                                WHERE log_comment = '%s' AND type = 'QueryFinish'
                                ORDER BY event_time_microseconds DESC LIMIT 1
                                """.formatted(columns, logComment));
                        assertThat(resultSet.next())
                                .as("query_log entry for log_comment '%s'", logComment)
                                .isTrue();
                        var columnCount = resultSet.getMetaData().getColumnCount();
                        var values = new ArrayList<Long>(columnCount);
                        for (int i = 1; i <= columnCount; i++) {
                            values.add(resultSet.getLong(i));
                        }
                        row.set(values);
                    }
                });
        return row.get();
    }
}
