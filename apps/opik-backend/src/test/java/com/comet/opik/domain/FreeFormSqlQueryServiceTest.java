package com.comet.opik.domain;

import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.DatabaseAnalyticsReadOnlyFreeFormSqlConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        var analytics = new DatabaseAnalyticsFactory();
        analytics.setDatabaseName(DATABASE);
        service = new FreeFormSqlQueryService(dao, enricher, analytics, account(STANDARD_USER), account(EXTENDED_USER));
    }

    private void givenQueryLog(String user, List<String> tables, List<String> policies) {
        when(dao.queryLogEntries(anyString())).thenReturn(CompletableFuture.completedFuture(List.of(
                FreeFormSqlPolicyCheck.LogEntry.builder().initial(true).user(user).tables(tables)
                        .policedTables(policies).build())));
    }

    private static DatabaseAnalyticsReadOnlyFreeFormSqlConfig account(String user) {
        var config = new DatabaseAnalyticsReadOnlyFreeFormSqlConfig();
        config.setUsername(user);
        return config;
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
        when(dao.explainPlan(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        "[{\"Plan\": {\"Node Type\": \"ReadFromMergeTree\", \"Description\": \"opik.traces\"}}]"));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).hasCauseInstanceOf(InternalServerErrorException.class);
        verify(enricher, never()).enrich(any(), anyString());
    }

    @Test
    @DisplayName("results are withheld when the query has no log entry as the account it ran on")
    void resultsWithheldWithoutLogEntry() {
        givenClickHouseReturnsOneRow();
        when(dao.queryLogEntries(anyString())).thenReturn(CompletableFuture.completedFuture(List.of()));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).hasCauseInstanceOf(InternalServerErrorException.class);
    }

    @Test
    @DisplayName("results are withheld when the check itself cannot run")
    void resultsWithheldWhenCheckFails() {
        givenClickHouseReturnsOneRow();
        when(dao.queryLogEntries(anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("flush timed out")));

        assertThatThrownBy(() -> service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY)
                .join()).hasCauseInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    @DisplayName("the check reads the log of exactly the execution it follows")
    void checksTheExecutedQuery() {
        givenClickHouseReturnsOneRow();

        service.executeQuery(FreeFormSqlAccount.STANDARD, WORKSPACE, UUID.randomUUID(), QUERY).join();

        var queryId = ArgumentCaptor.forClass(String.class);
        verify(dao).execute(any(), anyString(), anyString(), anyString(), queryId.capture());
        verify(dao).queryLogEntries(queryId.getValue());
    }
}
