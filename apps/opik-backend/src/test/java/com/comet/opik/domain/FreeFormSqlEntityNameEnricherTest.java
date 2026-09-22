package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the workspace binding, which is the whole security property of the curated MySQL path: the id in the
 * result set never grants access, the workspace of the authenticated request does. A dataset belonging to another
 * workspace resolves to nothing and its row keeps the raw id.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Free-form SQL result name enricher Test")
class FreeFormSqlEntityNameEnricherTest {

    private static final String CALLER_WORKSPACE = UUID.randomUUID().toString();

    @Mock
    private TransactionTemplate template;
    @Mock
    private DatasetDAO datasetDAO;
    @Mock
    private ProjectDAO projectDAO;

    private FreeFormSqlEntityNameEnricher enricher;

    @BeforeEach
    void setUp() {
        var handle = mock(Handle.class);
        when(handle.attach(DatasetDAO.class)).thenReturn(datasetDAO);
        when(handle.attach(ProjectDAO.class)).thenReturn(projectDAO);
        when(template.inTransaction(any(), any())).thenAnswer(invocation -> {
            TxAction<?> callback = invocation.getArgument(1);
            return callback.execute(handle);
        });
        when(projectDAO.findByIds(anySet(), anyString())).thenReturn(List.of());

        enricher = new FreeFormSqlEntityNameEnricher(template, null);
    }

    @Test
    @DisplayName("resolves names only within the caller's workspace")
    void resolvesOnlyWithinCallerWorkspace() {
        var ownDataset = UUID.randomUUID();
        var foreignDataset = UUID.randomUUID();

        // The DAO filters on workspace_id, so a dataset owned elsewhere simply is not returned.
        when(datasetDAO.findByIds(anySet(), anyString()))
                .thenReturn(List.of(Dataset.builder().id(ownDataset).name("own dataset").build()));

        var rows = enricher.enrich(
                List.of(row(ownDataset), row(foreignDataset)),
                CALLER_WORKSPACE);

        assertThat(rows.get(0).get("dataset_name").asText()).isEqualTo("own dataset");
        assertThat(rows.get(1).get("dataset_name").asText()).isEqualTo(foreignDataset.toString());

        var workspace = ArgumentCaptor.forClass(String.class);
        var ids = ArgumentCaptor.forClass(Set.class);
        verify(datasetDAO).findByIds(ids.capture(), workspace.capture());

        // The bound workspace is the request's, never anything read out of the result set.
        assertThat(workspace.getValue()).isEqualTo(CALLER_WORKSPACE);
        assertThat(ids.getValue()).containsExactlyInAnyOrder(ownDataset, foreignDataset);
    }

    private static JsonNode row(UUID datasetId) {
        return JsonUtils.getJsonNodeFromString("{\"dataset_id\":\"%s\"}".formatted(datasetId));
    }

    @ParameterizedTest
    @DisplayName("an unusable cap falls back to the default rather than disabling the lookup")
    @NullSource
    @ValueSource(strings = {"", "   ", "not-a-number", "0", "-5"})
    void unusableCapFallsBackToDefault(String rawCap) {
        var dataset = UUID.randomUUID();
        when(datasetDAO.findByIds(anySet(), anyString()))
                .thenReturn(List.of(Dataset.builder().id(dataset).name("resolved").build()));

        var rows = new FreeFormSqlEntityNameEnricher(template, rawCap).enrich(List.of(row(dataset)), CALLER_WORKSPACE);

        assertThat(rows.get(0).get("dataset_name").asText()).isEqualTo("resolved");
    }

    @Test
    @DisplayName("a cap below the id count skips the lookup and leaves raw ids")
    void capBelowIdCountSkipsLookup() {
        var rows = new FreeFormSqlEntityNameEnricher(template, "1")
                .enrich(List.of(row(UUID.randomUUID()), row(UUID.randomUUID())), CALLER_WORKSPACE);

        verify(datasetDAO, never()).findByIds(anySet(), anyString());
        assertThat(rows).allSatisfy(
                row -> assertThat(row.get("dataset_name").asText()).isEqualTo(row.get("dataset_id").asText()));
    }
}
