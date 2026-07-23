package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.planshape.CapturingSqlLogger;
import com.comet.opik.api.resources.utils.planshape.MySqlPlanShapeAsserter;
import com.comet.opik.api.resources.utils.planshape.PlanShapeBaseline;
import com.comet.opik.api.resources.utils.planshape.PlanShapeViolation;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlStatements;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan-shape regression gate for the MySQL read paths (OPIK-7448, slice 1).
 *
 * <p>Result identity is already covered by the resource tests; this gate covers plan <b>shape</b>. It installs a
 * {@link CapturingSqlLogger} on the app's {@link Jdbi}, drives the real MySQL-backed read endpoints through the REST
 * API (so it captures exactly the SQL production renders), then runs {@code EXPLAIN FORMAT=JSON} over every captured
 * {@code SELECT} and fails on any plan that materializes a subquery / uses an internal temporary table (the OPIK-7198
 * {@code SQLSyntaxErrorException: Table '#sql...' doesn't exist} class) or full-scans a tenant-growing table.</p>
 *
 * <p>The read paths are exercised against empty tables: MySQL plans a query the same way regardless of row count, so
 * the plan shape the gate inspects is identical to production's. Enforcement is <b>net-new only</b> vs. the checked-in
 * {@code planshape/mysql-baseline.json} allowlist, which is ratcheted down as legacy offenders are fixed. A follow-up
 * will move capture into the shared test wiring so every resource test contributes queries passively.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("🐬 MySQL Query Plan Gate")
@ExtendWith(DropwizardAppExtensionProvider.class)
class MySqlQueryPlanGateTest {

    private static final String BASELINE_RESOURCE = "planshape/mysql-baseline.json";

    // MySQL tables that grow with tenant data — a full scan here is a latency cliff at scale. The high-volume analytics
    // tables (traces, spans, dataset_items) live in ClickHouse and are out of scope for the MySQL gate.
    private static final Set<String> FULL_SCAN_SENSITIVE_TABLES = Set.of(
            "datasets", "experiments", "prompts", "prompt_versions", "feedback_definitions", "projects");

    // MySQL-backed list endpoints. Each renders the SELECT of a DAO the gate must vet.
    private static final List<String> READ_PATHS = List.of(
            "/v1/private/datasets",
            "/v1/private/prompts",
            "/v1/private/projects",
            "/v1/private/feedback-definitions",
            "/v1/private/automations/evaluators");

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final CapturingSqlLogger capturingSqlLogger = new CapturingSqlLogger();

    private ClientSupport client;
    private String baseURI;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(setup.wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);

        // The SqlLogger fires for every statement executed through any handle obtained from this Jdbi.
        jdbi.getConfig(SqlStatements.class).setSqlLogger(capturingSqlLogger);
    }

    @AfterAll
    void tearDownAll() {
        setup.wireMock.server().stop();
    }

    @Test
    @DisplayName("🐬 no MySQL read path introduces a materialized/temporary-table plan or a tenant-table full scan")
    void mySqlReadPathsHaveNoPlanShapeRegressions() {
        READ_PATHS.forEach(this::getPage);

        var asserter = new MySqlPlanShapeAsserter(FULL_SCAN_SENSITIVE_TABLES);
        var violations = capturingSqlLogger.capturedPlans().entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .flatMap(entry -> asserter.findViolations(entry.getKey(), entry.getValue()).stream())
                .toList();

        assertThat(capturingSqlLogger.capturedPlans())
                .as("the gate must capture SELECTs; an empty capture means the read paths did not exercise MySQL")
                .isNotEmpty();

        var netNew = PlanShapeBaseline.loadFromClasspath(BASELINE_RESOURCE).netNew(violations);

        assertThat(netNew)
                .withFailMessage(() -> renderFailure(netNew))
                .isEmpty();
    }

    private void getPage(String path) {
        try (var response = client.target(baseURI + path)
                .queryParam("page", 1)
                .queryParam("size", 100)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .get()) {

            assertThat(response.getStatus())
                    .as("read endpoint %s must return 200 so its query is actually rendered", path)
                    .isEqualTo(200);
        }
    }

    private static String renderFailure(List<PlanShapeViolation> netNew) {
        return netNew.stream()
                .map(v -> "%n  [%s] %s%n    fingerprint: %s%n    sql: %s".formatted(
                        v.type(), v.detail(), v.fingerprint(), v.renderedSql()))
                .collect(Collectors.joining("",
                        "🐬 Net-new MySQL plan-shape violations (add to planshape/mysql-baseline.json only with a "
                                + "tracking ticket, or fix the query):",
                        ""));
    }
}
