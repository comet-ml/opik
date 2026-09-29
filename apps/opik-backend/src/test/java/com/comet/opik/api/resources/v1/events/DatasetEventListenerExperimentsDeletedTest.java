package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.domain.DatasetEventInfoHolder;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.OptimizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The REGULAR-only filtering in {@code onExperimentsDeleted}.
 *
 * <p>Driven through the listener with mocked services rather than through the API, because the fix has no
 * other observable effect: before it, the handler threw out of the event bus; after it, the handler returns
 * early. Either way the caller's delete has already answered 204 and nothing downstream is written, so an
 * end-to-end test cannot tell the two apart. What distinguishes them is whether
 * {@code getMostRecentCreatedExperimentFromDatasets} is called with an empty set, which is exactly what it
 * rejects — so that is what these assert.
 */
@ExtendWith(MockitoExtension.class)
class DatasetEventListenerExperimentsDeletedTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final String USER = "user-1";

    @Mock
    private DatasetService datasetService;
    @Mock
    private ExperimentService experimentService;
    @Mock
    private OptimizationService optimizationService;

    private DatasetEventListener listener() {
        return new DatasetEventListener(datasetService, experimentService, optimizationService);
    }

    private static ExperimentsDeleted event(ExperimentType... types) {
        var datasetInfo = java.util.Arrays.stream(types)
                .map(type -> new DatasetEventInfoHolder(UUID.randomUUID(), type))
                .toList();
        return new ExperimentsDeleted(datasetInfo, Set.of(UUID.randomUUID()), WORKSPACE_ID, USER);
    }

    @ParameterizedTest
    @EnumSource(value = ExperimentType.class, names = {"TRIAL", "MINI_BATCH", "MUTATION"})
    @DisplayName("an event carrying no REGULAR dataset is skipped rather than throwing")
    void skipsWhenNoRegularDataset(ExperimentType type) {
        // datasetInfo is not empty, so the old guard let this through and the filtered set — which is what
        // the call below actually receives — was empty. getMostRecentCreatedExperimentFromDatasets rejects
        // that with IllegalArgumentException, out of the listener, on a delete the caller was already told
        // had succeeded (OPIK-8577).
        var event = event(type);

        assertThatCode(() -> listener().onExperimentsDeleted(event)).doesNotThrowAnyException();

        verify(experimentService, never()).getMostRecentCreatedExperimentFromDatasets(anySet());
        verify(datasetService, never()).recordExperiments(anySet());
    }

    @Test
    @DisplayName("a mix of REGULAR and other types still processes the REGULAR ones")
    void stillProcessesTheRegularDatasetsInAMixedEvent() {
        // The filter is not a reason to skip work that is genuinely there: only the wholly non-REGULAR case
        // should short-circuit.
        var event = event(ExperimentType.TRIAL, ExperimentType.REGULAR, ExperimentType.MUTATION);
        var regularIds = event.datasetInfo().stream()
                .filter(holder -> holder.type() == ExperimentType.REGULAR)
                .map(DatasetEventInfoHolder::datasetId)
                .collect(java.util.stream.Collectors.toSet());

        when(experimentService.getMostRecentCreatedExperimentFromDatasets(anySet())).thenReturn(Flux.empty());
        when(datasetService.recordExperiments(anySet())).thenReturn(reactor.core.publisher.Mono.empty());

        assertThatCode(() -> listener().onExperimentsDeleted(event)).doesNotThrowAnyException();

        // Exactly the REGULAR ids, so a future change that widened or narrowed the filter is visible here.
        verify(experimentService).getMostRecentCreatedExperimentFromDatasets(regularIds);
    }

    @Test
    @DisplayName("an event with no datasets at all is still skipped")
    void skipsWhenDatasetInfoIsEmpty() {
        var event = new ExperimentsDeleted(List.of(), Set.of(UUID.randomUUID()), WORKSPACE_ID, USER);

        assertThatCode(() -> listener().onExperimentsDeleted(event)).doesNotThrowAnyException();

        verify(experimentService, never()).getMostRecentCreatedExperimentFromDatasets(any());
    }
}
