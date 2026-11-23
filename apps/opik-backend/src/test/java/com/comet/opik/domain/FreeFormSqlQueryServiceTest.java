package com.comet.opik.domain;

import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.api.metadata.NoSuchColumnException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.DatabaseAnalyticsReadOnlyFreeFormSqlConfig;
import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the enrichment stage the EXTENDED account adds. It runs off the ClickHouse completion thread via
 * boundedElastic, so the contract worth pinning is that the rows still arrive, and that enrichment stays
 * presentation: when it throws, the result ClickHouse already returned is kept rather than lost.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Free-form SQL query service Test")
class FreeFormSqlQueryServiceTest {

    private static final String WORKSPACE = UUID.randomUUID().toString();
    private static final String QUERY = "SELECT toJSONString(1) AS result FROM experiments";

    @Mock
    private FreeFormSqlQueryDAO dao;
    @Mock
    private FreeFormSqlEntityNameEnricher enricher;
    @Mock
    private FreeFormSqlQueryLogReader queryLogReader;

    private FreeFormSqlQueryService service;
    private List<JsonNode> chRows;

    private static final String DATABASE = "opik";
    private static final String STANDARD_USER = "comet_readonly_freeform_sql_user";
    private static final String EXTENDED_USER = "comet_readonly_freeform_extended_sql_user";

    private void givenClickHouseReturnsOneRow() {
        chRows = List.of(JsonUtils.getJsonNodeFromString("{\"dataset_id\":\"" + UUID.randomUUID() + "\"}"));
        when(dao.explainAst(any(), anyString())).thenReturn(CompletableFuture.completedFuture(List.of("SelectQuery")));
        when(dao.explainQueryTree(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of("QUERY id: 0", "  JOIN TREE",
                        "    TABLE id: 1, table_name: opik.traces")));
        when(dao.explainPlan(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture("[{\"Plan\": {\"Node Type\": \"ReadFromSystemOne\"}}]"));
        when(dao.execute(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        FreeFormSqlResult.builder().rows(chRows).resultRows(1).readBytes(1).build()));
        // By default the query ran under its policy, as the account it ran on.
        givenQueryLog(STANDARD_USER, List.of("opik.traces"), List.of("opik.traces"));
        inMode(FreeFormSqlPostRunCheckConfig.Mode.ENFORCE);
    }

    private void inMode(FreeFormSqlPostRunCheckConfig.Mode mode) {
        var analytics = new DatabaseAnalyticsFactory();
        analytics.setDatabaseName(DATABASE);
        service = new FreeFormSqlQueryService(dao, enricher, queryLogReader,
                postRunCheck(mode), analytics, account(STANDARD_USER),
                account(EXTENDED_USER));
    }

    private void givenPlanReadsTracesWithoutFilter() {
        when(dao.explainPlan(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        "[{\"Plan\": {\"Node Type\": \"ReadFromMergeTree\", \"Description\": \"opik.traces\"}}]"));
    }

    private void givenScalarReadOfSpans() {
        when(dao.explainQueryTree(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of("QUERY id: 0", "  PROJECTION", "    LIST id: 1",
                        "      QUERY id: 2, is_subquery: 1", "        JOIN TREE",
                        "          TABLE id: 3, table_name: opik.spans")));
    }

    private void givenQueryLog(String user, List<String> tables, List<String> policies) {
        // Stubbed for the account the query runs as only; checksTheExecutedQuery pins the query id.
        when(queryLogReader.entries(anyString(), eq(user)))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        FreeFormSqlQueryLogEntry.builder().initial(true).user(user).tables(tables)
                                .policyCoveredTables(policies).build())));
    }

    private static DatabaseAnalyticsReadOnlyFreeFormSqlConfig account(String user) {
        var config = new DatabaseAnalyticsReadOnlyFreeFormSqlConfig();
        config.setUsername(user);
        return config;
    }

    static Stream<Arguments> metadataExpressions() {
        return Stream.of(
                arguments("currentUser()", "Function currentUser"),
                arguments("database()", "Function database"),
                arguments("schema()", "Function schema"),
                arguments("current_database()", "Function current_database"),
                arguments("current_user()", "Function current_user"),
                arguments("current_user", "Identifier current_user"),
                arguments("getMacro('replica')", "Function getMacro"),
                arguments("getServerPort('tcp_port')", "Function getServerPort"),
                arguments("displayName()", "Function displayName"),
                arguments("DISPLAYNAME()", "Function DISPLAYNAME"),
                arguments("enabledRoles()", "Function enabledRoles"),
                arguments("defaultRoles()", "Function defaultRoles"),
                arguments("getOSKernelVersion()", "Function getOSKernelVersion"),
                arguments("tcpPort()", "Function tcpPort"),
                arguments("TCPPORT()", "Function TCPPORT"),
                arguments("authenticatedUser()", "Function authenticatedUser"),
                arguments("enabledProfiles()", "Function enabledProfiles"),
                arguments("defaultProfiles()", "Function defaultProfiles"),
                arguments("current_database", "Identifier current_database"),
                arguments("database", "Identifier database"),
                arguments("schema", "Identifier schema"),
                arguments("user", "Identifier user"),
                arguments("currentDatabase", "Identifier currentDatabase"),
                arguments("currentUser", "Identifier currentUser"));
    }

    static Stream<Arguments> forbiddenAstCases() {
        var catalogs = Stream.of(
                "system.tables", "system.columns", "system.databases", "system.settings", "system.one",
                "information_schema.tables")
                .map(table -> arguments("SELECT toJSONString(count()) AS result FROM " + table,
                        "TableIdentifier " + table));
        var metadata = metadataExpressions().map(shape -> arguments(
                "SELECT toJSONString(map('n', toString(%s))) AS result".formatted(shape.get()[0]), shape.get()[1]));
        return Stream.concat(catalogs, metadata);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenAstCases")
    @DisplayName("catalog and metadata reads are rejected before execution for both accounts")
    void forbiddenAstNodesAreRejected(String query, String astLabel) {
        givenClickHouseReturnsOneRow();
        when(dao.explainAst(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of("SelectWithUnionQuery", "  " + astLabel)));
        if (astLabel.startsWith("Identifier ")) {
            String resolvedFunction = switch (astLabel.substring("Identifier ".length()).toLowerCase()) {
                case "current_user", "user", "currentuser" -> "currentUser";
                default -> "currentDatabase";
            };
            when(dao.explainQueryTree(any(), anyString(), anyString(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(List.of(
                            "QUERY id: 0", "  FUNCTION id: 1, function_name: " + resolvedFunction
                                    + ", function_type: ordinary, result_type: String")));
        }

        for (var account : FreeFormSqlAccount.values()) {
            assertThatThrownBy(() -> service.executeQuery(account, WORKSPACE, null, query).join())
                    .cause().satisfies(error -> {
                        assertThat(error).isInstanceOf(BadRequestException.class);
                        assertThat(((WebApplicationException) error).getResponse().getEntity())
                                .isEqualTo(new ErrorMessage(List.of(FreeFormSqlQueryService.ACCESS_DENIED_MESSAGE)));
                    });
        }
        verify(dao, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a table function is rejected before its arguments can be resolved")
    void tableFunctionIsRejectedBeforeResolution() {
        givenClickHouseReturnsOneRow();
        when(dao.explainAst(any(), anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                "SelectQuery (children 1)", "  TableExpression (children 1)", "   Function remote (children 1)")));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, null, QUERY).join())
                .cause().satisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    assertThat(((WebApplicationException) error).getResponse().getEntity())
                            .isEqualTo(new ErrorMessage(List.of(FreeFormSqlQueryService.ACCESS_DENIED_MESSAGE)));
                });
        verify(dao, never()).explainQueryTree(any(), anyString(), anyString(), anyString());
        verify(dao, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
    }

    private static Stream<Arguments> forbiddenResolvedTables() {
        return Stream.of(
                arguments("system catalog", FreeFormSqlAccount.STANDARD, "TABLE id: 1, table_name: system.tables"),
                arguments("information schema", FreeFormSqlAccount.STANDARD,
                        "TABLE id: 1, table_name: information_schema.tables"),
                arguments("other database", FreeFormSqlAccount.STANDARD, "TABLE id: 1, table_name: other.traces"),
                arguments("extended-only table", FreeFormSqlAccount.STANDARD,
                        "TABLE id: 1, table_name: opik.experiments"),
                arguments("ungranted analytics table", FreeFormSqlAccount.EXTENDED,
                        "TABLE id: 1, table_name: opik.projects"),
                arguments("physical local table", FreeFormSqlAccount.EXTENDED,
                        "TABLE id: 1, table_name: opik.traces_local"),
                arguments("missing table name", FreeFormSqlAccount.STANDARD, "TABLE id: 1, alias: hidden"),
                arguments("table function", FreeFormSqlAccount.STANDARD,
                        "TABLE_FUNCTION id: 1, table_function_name: merge"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenResolvedTables")
    @DisplayName("resolved reads outside the account's tables are rejected in audit mode")
    void forbiddenResolvedTableIsRejected(String label, FreeFormSqlAccount account, String tableNode) {
        givenClickHouseReturnsOneRow();
        inMode(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
        when(dao.explainQueryTree(any(), anyString(), anyString(), anyString()))
                .thenReturn(
                        CompletableFuture.completedFuture(List.of("QUERY id: 0", "  JOIN TREE", "    " + tableNode)));

        assertThatThrownBy(() -> service.executeQuery(account, WORKSPACE, null, QUERY).join())
                .cause().satisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    assertThat(((WebApplicationException) error).getResponse().getEntity())
                            .isEqualTo(new ErrorMessage(List.of(FreeFormSqlQueryService.ACCESS_DENIED_MESSAGE)));
                });
        verify(dao, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a metadata-like identifier resolved as a column is allowed")
    void metadataNamedColumnIsAllowed() {
        givenClickHouseReturnsOneRow();
        when(dao.explainAst(any(), anyString()))
                .thenReturn(
                        CompletableFuture.completedFuture(List.of("SelectWithUnionQuery", "  Identifier currentUser")));
        when(dao.explainQueryTree(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        "QUERY id: 0", "  COLUMN id: 1, column_name: currentUser, result_type: UInt8")));

        service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, null,
                "SELECT toJSONString(map('n', toString(currentUser))) AS result FROM "
                        + "(SELECT 1 AS currentUser)")
                .join();

        verify(dao).execute(any(), anyString(), anyString(), anyString(), anyString());
        verify(dao).explainQueryTree(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a newly niladic metadata function is rejected when ClickHouse resolves it")
    void newlyNiladicMetadataFunctionIsRejected() {
        givenClickHouseReturnsOneRow();
        when(dao.explainAst(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of("SelectWithUnionQuery", "  Identifier version")));
        when(dao.explainQueryTree(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        "QUERY id: 0", "  FUNCTION id: 1, function_name: version, result_type: String")));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, null,
                "SELECT toJSONString(map('n', toString(version))) AS result").join())
                .isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(BadRequestException.class)
                .satisfies(
                        error -> assertThat(String.valueOf(((WebApplicationException) error).getResponse().getEntity()))
                                .contains(FreeFormSqlQueryService.ACCESS_DENIED_MESSAGE));
        verify(dao, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a function alias missing from the AST denylist is rejected by its resolved name")
    void resolvedFunctionAliasIsRejected() {
        givenClickHouseReturnsOneRow();
        inMode(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
        when(dao.explainAst(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of("Function unexpectedAlias")));
        when(dao.explainQueryTree(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        "QUERY id: 0", "  FUNCTION id: 1, function_name: currentUser, result_type: String")));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, null, QUERY).join())
                .cause().satisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    assertThat(((WebApplicationException) error).getResponse().getEntity())
                            .isEqualTo(new ErrorMessage(List.of(FreeFormSqlQueryService.ACCESS_DENIED_MESSAGE)));
                });
        verify(dao, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a failed resolved-tree preflight withholds execution in audit mode")
    void resolvedTreeFailureBlocksExecution() {
        givenClickHouseReturnsOneRow();
        inMode(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
        when(dao.explainQueryTree(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("tree unavailable")));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, null, QUERY).join())
                .cause().satisfies(error -> {
                    assertThat(error).isInstanceOf(WebApplicationException.class);
                    assertThat(((WebApplicationException) error).getResponse().getStatus()).isEqualTo(503);
                });
        verify(dao, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a query returning more than the single result column is a clean 400")
    void nonSingleResultColumnIsRejected() {
        givenClickHouseReturnsOneRow();
        when(dao.execute(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(
                        new NoSuchColumnException("Free-form SQL must return exactly one result column")));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).cause().satisfies(error -> {
                    assertThat(error).isInstanceOf(BadRequestException.class);
                    assertThat(((WebApplicationException) error).getResponse().getEntity())
                            .isEqualTo(
                                    new ErrorMessage(List.of(FreeFormSqlQueryService.RESULT_SHAPE_MESSAGE)));
                });
    }

    @Test
    @DisplayName("EXTENDED results pass through enrichment")
    void extendedResultsAreEnriched() {
        givenClickHouseReturnsOneRow();
        givenQueryLog(EXTENDED_USER, List.of("opik.experiments"), List.of("opik.experiments"));
        var enriched = List.of(JsonUtils.getJsonNodeFromString("{\"dataset_name\":\"resolved\"}"));
        when(enricher.enrich(any(), anyString())).thenReturn(enriched);

        var response = service.executeQuery(FreeFormSqlAccount.EXTENDED, WORKSPACE, null, QUERY).join();

        assertThat(response.results()).isEqualTo(enriched);
        verify(enricher).enrich(chRows, WORKSPACE);
    }

    @Test
    @DisplayName("a failing enrichment keeps the rows ClickHouse returned")
    void failedEnrichmentKeepsClickHouseRows() {
        givenClickHouseReturnsOneRow();
        givenQueryLog(EXTENDED_USER, List.of("opik.experiments"), List.of("opik.experiments"));
        when(enricher.enrich(any(), anyString())).thenThrow(new IllegalStateException("MySQL unavailable"));

        var response = service.executeQuery(FreeFormSqlAccount.EXTENDED, WORKSPACE, null, QUERY).join();

        assertThat(response.results()).isEqualTo(chRows);
    }

    @Test
    @DisplayName("STANDARD results never reach the enricher")
    void standardResultsSkipEnrichment() {
        givenClickHouseReturnsOneRow();

        var response = service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY).join();

        assertThat(response.results()).isEqualTo(chRows);
        verify(enricher, never()).enrich(any(), anyString());
    }

    @Test
    @DisplayName("results are withheld when a table was read without its row policy")
    void resultsWithheldWhenPolicyNotApplied() {
        givenClickHouseReturnsOneRow();
        givenQueryLog(STANDARD_USER, List.of("opik.traces"), List.of());
        givenPlanReadsTracesWithoutFilter();

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).cause().satisfies(withheld(500));
        verify(enricher, never()).enrich(any(), anyString());
    }

    @Test
    @DisplayName("results are withheld when the query has no log entry as the account it ran on")
    void resultsWithheldWithoutLogEntry() {
        givenClickHouseReturnsOneRow();
        when(queryLogReader.entries(anyString(), eq(STANDARD_USER)))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).cause().satisfies(withheld(500));
    }

    @Test
    @DisplayName("results are withheld when the check itself cannot run")
    void resultsWithheldWhenCheckFails() {
        givenClickHouseReturnsOneRow();
        when(queryLogReader.entries(anyString(), eq(STANDARD_USER)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("query_log unreachable")));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).cause().satisfies(withheld(503));
    }

    static Stream<Arguments> limitErrors() {
        return Stream.of(
                Arguments.of(158, FreeFormSqlQueryService.LIMIT_MESSAGE),
                Arguments.of(159, FreeFormSqlQueryService.LIMIT_MESSAGE),
                Arguments.of(241, FreeFormSqlQueryService.LIMIT_MESSAGE),
                Arguments.of(396, FreeFormSqlQueryService.LIMIT_MESSAGE),
                Arguments.of(307, FreeFormSqlQueryService.BYTES_LIMIT_MESSAGE));
    }

    @ParameterizedTest(name = "code {0}")
    @MethodSource("limitErrors")
    @DisplayName("a ClickHouse limit error is a 400 with a constant message, not a 500")
    void limitErrorIsBadRequest(int code, String message) {
        givenClickHouseReturnsOneRow();
        var projectId = UUID.randomUUID();
        var clickHouseMessage = "DB::Exception: " + RandomStringUtils.randomAlphanumeric(20);
        when(dao.execute(eq(FreeFormSqlAccount.STANDARD), eq(WORKSPACE), eq(projectId.toString()), eq(QUERY),
                anyString()))
                .thenReturn(CompletableFuture.failedFuture(
                        new ServerException(code, clickHouseMessage, 500, UUID.randomUUID().toString())));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, projectId, QUERY)
                .join()).cause().satisfies(error -> {
                    assertThat(error).isExactlyInstanceOf(BadRequestException.class);
                    assertThat(((WebApplicationException) error).getResponse().getEntity())
                            .isEqualTo(new ErrorMessage(List.of(message)));
                });
    }

    @Test
    @DisplayName("ClickHouse's concurrent-query cap is a retryable 429, not a failed query")
    void tooManySimultaneousQueriesIsRetryable() {
        givenClickHouseReturnsOneRow();
        when(dao.execute(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(
                        new ServerException(202, "Too many simultaneous queries for user", 500, "query-id")));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).cause().satisfies(retryable());
    }

    @Test
    @DisplayName("the concurrent-query cap hit by the post-run check is a retryable 429, not a failed check")
    void tooManySimultaneousQueriesInCheckIsRetryable() {
        givenClickHouseReturnsOneRow();
        when(dao.explainPlan(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(
                        new ServerException(202, "Too many simultaneous queries for user", 500, "query-id")));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).cause().satisfies(retryable());
    }

    @Test
    @DisplayName("the check reads the log of exactly the execution it follows")
    void checksTheExecutedQuery() {
        givenClickHouseReturnsOneRow();

        service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY).join();

        var queryId = ArgumentCaptor.forClass(String.class);
        verify(dao).execute(any(), anyString(), anyString(), anyString(), queryId.capture());
        verify(queryLogReader).entries(queryId.getValue(), STANDARD_USER);
    }

    @Test
    @DisplayName("enforce: a scalar subquery reading a table is rejected before running, with the rewrite")
    void enforceRejectsScalarReads() {
        givenClickHouseReturnsOneRow();
        givenScalarReadOfSpans();

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).cause().isInstanceOf(BadRequestException.class)
                .satisfies(error -> assertThat(String.valueOf(((WebApplicationException) error).getResponse()
                        .getEntity())).contains("this one reads spans"));
        verify(dao, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("audit: a violation is reported but the results are returned")
    void auditReturnsResultsDespiteViolation() {
        givenClickHouseReturnsOneRow();
        inMode(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
        givenQueryLog(STANDARD_USER, List.of("opik.traces"), List.of());
        givenPlanReadsTracesWithoutFilter();

        var response = service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY).join();

        assertThat(response.results()).isEqualTo(chRows);
        verify(queryLogReader).entries(anyString(), eq(STANDARD_USER));
    }

    @Test
    @DisplayName("audit: a check that cannot run never affects the request")
    void auditIgnoresCheckFailures() {
        givenClickHouseReturnsOneRow();
        inMode(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
        when(dao.explainPlan(any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("thrown before any future"));

        var response = service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY).join();

        assertThat(response.results()).isEqualTo(chRows);
    }

    @Test
    @DisplayName("audit: a query tree that cannot be explained blocks execution")
    void auditRejectsTreeFailureBeforeCheck() {
        givenClickHouseReturnsOneRow();
        inMode(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
        when(dao.explainQueryTree(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("query tree unavailable")));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).cause().isInstanceOf(WebApplicationException.class);
        verify(dao, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
        verify(dao, never()).explainPlan(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("audit: a malformed resolved tree blocks execution")
    void auditRejectsMalformedTreeBeforeCheck() {
        givenClickHouseReturnsOneRow();
        inMode(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
        // A null line makes the parser throw on a tree ClickHouse did return.
        when(dao.explainQueryTree(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(Stream.of("QUERY id: 0", (String) null).toList()));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).cause().isInstanceOf(WebApplicationException.class);
        verify(dao, never()).execute(any(), anyString(), anyString(), anyString(), anyString());
        verify(dao, never()).explainPlan(any(), anyString(), anyString(), anyString());
        verify(queryLogReader, never()).entries(anyString(), anyString());
    }

    @Test
    @DisplayName("audit: a scalar subquery reading a table is reported, and the query runs")
    void auditRunsScalarReads() {
        givenClickHouseReturnsOneRow();
        inMode(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
        givenScalarReadOfSpans();

        var response = service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY).join();

        assertThat(response.results()).isEqualTo(chRows);
        var order = inOrder(dao);
        order.verify(dao).explainQueryTree(any(), anyString(), anyString(), anyString());
        order.verify(dao).execute(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("off: neither the scalar subquery gate nor the post-run check runs")
    void offRunsNoCheck() {
        givenClickHouseReturnsOneRow();
        inMode(FreeFormSqlPostRunCheckConfig.Mode.OFF);
        givenScalarReadOfSpans();

        var response = service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY).join();

        assertThat(response.results()).isEqualTo(chRows);
        verify(dao).explainQueryTree(any(), anyString(), anyString(), anyString());
        verify(dao, never()).explainPlan(any(), anyString(), anyString(), anyString());
        verify(queryLogReader, never()).entries(anyString(), anyString());
    }

    /** The post-run check in {@code mode}, reading the log once with no wait. */
    static FreeFormSqlPostRunCheckConfig postRunCheck(FreeFormSqlPostRunCheckConfig.Mode mode) {
        var config = new FreeFormSqlPostRunCheckConfig();
        config.setMode(mode);
        config.setLogReadDelay(io.dropwizard.util.Duration.milliseconds(0));
        config.setMaxLogReadAttempts(1);
        return config;
    }

    static java.util.function.Consumer<Throwable> retryable() {
        return error -> {
            assertThat(error).isInstanceOf(WebApplicationException.class);
            var response = ((WebApplicationException) error).getResponse();
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeaderString(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        };
    }

    /** A withheld result: the status, and the constant message, with no row and no ClickHouse detail. */
    static java.util.function.Consumer<Throwable> withheld(int status) {
        return error -> {
            assertThat(error).isInstanceOf(WebApplicationException.class);
            var response = ((WebApplicationException) error).getResponse();
            assertThat(response.getStatus()).isEqualTo(status);
            assertThat(String.valueOf(response.getEntity())).contains("Query result withheld: its scope could not be "
                    + "verified");
        };
    }
}
