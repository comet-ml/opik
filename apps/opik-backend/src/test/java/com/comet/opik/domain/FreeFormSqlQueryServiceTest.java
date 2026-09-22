package com.comet.opik.domain;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
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

    private void givenClickHouseReturnsOneRow() {
        chRows = List.of(JsonUtils.getJsonNodeFromString("{\"dataset_id\":\"" + UUID.randomUUID() + "\"}"));
        when(dao.explainAst(any(), anyString())).thenReturn(CompletableFuture.completedFuture(List.of("SelectQuery")));
        when(dao.execute(any(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        FreeFormSqlResult.builder().rows(chRows).resultRows(1).readBytes(1).build()));
        service = new FreeFormSqlQueryService(dao, enricher);
    }

    @Test
    @DisplayName("EXTENDED results pass through enrichment")
    void extendedResultsAreEnriched() {
        givenClickHouseReturnsOneRow();
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
}
