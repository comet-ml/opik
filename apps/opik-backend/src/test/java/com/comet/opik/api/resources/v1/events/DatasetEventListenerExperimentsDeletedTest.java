package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.events.ExperimentsDeleted;
import com.comet.opik.domain.DatasetEventInfoHolder;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.OptimizationService;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The REGULAR-only filtering in {@code onExperimentsDeleted}.
 *
 * <p>Unit rather than black box, because the guard is not reachable from the API. Three things have to line
 * up for an end-to-end case to exist, and the third does not:
 *
 * <ul>
 *   <li>The event shape is reachable — {@code Experiment.type} is writable through the public API, so a
 *       caller really can delete a wholly non-REGULAR set and produce the event that broke.</li>
 *   <li>Nothing observable changes. The listener runs on the {@code AsyncEventBus}
 *       ({@code EventModule#getEventBus}) on a virtual thread, off the request path, with Guava's default
 *       handler logging subscriber failures. So the pre-fix throw never reached the caller: the delete had
 *       already answered 204. And since the event carries no REGULAR dataset, neither version writes
 *       anything. Identical response, identical persisted state — before and after.</li>
 *   <li>That leaves only the listener's own behaviour, which is whether
 *       {@code getMostRecentCreatedExperimentFromDatasets} is called with the empty set its
 *       {@code Preconditions} check rejects. Nothing outside the listener can see that, so it is asserted
 *       here.</li>
 * </ul>
 *
 * <p>The existing black-box coverage, {@code DatasetEventListenerTest.DeleteExperimentEvent}, does not
 * overlap: {@code ExperimentResourceClient#createPartialExperiment} pins {@code type(REGULAR)}, so every
 * experiment it deletes takes the branch that always worked. It remains the high-value coverage for the
 * bookkeeping itself; these cases cover only the guard it cannot construct or observe.
 */
@ExtendWith(MockitoExtension.class)
class DatasetEventListenerExperimentsDeletedTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private DatasetService datasetService;
    @Mock
    private ExperimentService experimentService;
    @Mock
    private OptimizationService optimizationService;

    private DatasetEventListener listener() {
        return new DatasetEventListener(datasetService, experimentService, optimizationService);
    }

    private ExperimentsDeleted event(ExperimentType... types) {
        var datasetInfo = Arrays.stream(types)
                .map(type -> DatasetEventInfoHolder.builder().datasetId(UUID.randomUUID()).type(type).build())
                .toList();
        return experimentsDeleted(datasetInfo);
    }

    // ExperimentsDeleted has no builder: it is a plain class over BaseEvent, declaring its own constructor.
    private ExperimentsDeleted experimentsDeleted(List<DatasetEventInfoHolder> datasetInfo) {
        return new ExperimentsDeleted(datasetInfo, Set.of(UUID.randomUUID()),
                podamFactory.manufacturePojo(String.class), podamFactory.manufacturePojo(String.class));
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
                .collect(Collectors.toSet());

        when(experimentService.getMostRecentCreatedExperimentFromDatasets(anySet())).thenReturn(Flux.empty());
        when(datasetService.recordExperiments(anySet())).thenReturn(Mono.empty());

        assertThatCode(() -> listener().onExperimentsDeleted(event)).doesNotThrowAnyException();

        // Exactly the REGULAR ids, so a future change that widened or narrowed the filter is visible here.
        verify(experimentService).getMostRecentCreatedExperimentFromDatasets(regularIds);
    }

    @Test
    @DisplayName("an event with no datasets at all is still skipped")
    void skipsWhenDatasetInfoIsEmpty() {
        var event = experimentsDeleted(List.of());

        assertThatCode(() -> listener().onExperimentsDeleted(event)).doesNotThrowAnyException();

        verify(experimentService, never()).getMostRecentCreatedExperimentFromDatasets(any());
    }
}
