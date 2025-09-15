package com.comet.opik.domain;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.sorting.ExperimentSortingFactory;
import com.comet.opik.service.DatasetService;
import com.comet.opik.service.PromptService;
import com.comet.opik.utils.IdGenerator;
import com.comet.opik.utils.NameGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.EventBus;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceTest {

    private ExperimentService experimentService;

    @Mock
    private ExperimentDAO experimentDAO;

    @Mock
    private ExperimentItemDAO experimentItemDAO;

    @Mock
    private DatasetService datasetService;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private NameGenerator nameGenerator;

    @Mock
    private EventBus eventBus;

    @Mock
    private PromptService promptService;

    @Mock
    private ExperimentSortingFactory sortingFactory;

    @Mock
    private ExperimentResponseBuilder responseBuilder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        experimentService = new ExperimentService(
                experimentDAO,
                experimentItemDAO,
                datasetService,
                idGenerator,
                nameGenerator,
                eventBus,
                promptService,
                sortingFactory,
                responseBuilder);
    }

    @Nested
    @DisplayName("Update Experiment:")
    class UpdateExperiment {

        @Test
        @DisplayName("when updating experiment with valid data, then experiment is updated successfully")
        void updateExperiment_whenValidData_thenExperimentUpdatedSuccessfully() {
            // given
            var experimentId = UUID.randomUUID();
            var metadata = objectMapper.createObjectNode().put("version", "1.0");
            var experimentUpdate = ExperimentUpdate.builder()
                    .name("Updated Experiment")
                    .metadata(metadata)
                    .type(ExperimentType.TRIAL)
                    .status(ExperimentStatus.RUNNING)
                    .build();

            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
                    .verifyComplete();

            verify(experimentDAO).update(experimentId, experimentUpdate);
        }

        @Test
        @DisplayName("when updating experiment with only name, then only name is updated")
        void updateExperiment_whenOnlyName_thenOnlyNameUpdated() {
            // given
            var experimentId = UUID.randomUUID();
            var experimentUpdate = ExperimentUpdate.builder()
                    .name("New Name Only")
                    .build();

            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
                    .verifyComplete();

            verify(experimentDAO).update(experimentId, experimentUpdate);
        }

        @Test
        @DisplayName("when updating experiment with only metadata, then only metadata is updated")
        void updateExperiment_whenOnlyMetadata_thenOnlyMetadataUpdated() {
            // given
            var experimentId = UUID.randomUUID();
            var metadata = objectMapper.createObjectNode()
                    .put("temperature", 0.7)
                    .put("max_tokens", 100);
            var experimentUpdate = ExperimentUpdate.builder()
                    .metadata(metadata)
                    .build();

            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
                    .verifyComplete();

            verify(experimentDAO).update(experimentId, experimentUpdate);
        }

        @Test
        @DisplayName("when updating experiment with only type, then only type is updated")
        void updateExperiment_whenOnlyType_thenOnlyTypeUpdated() {
            // given
            var experimentId = UUID.randomUUID();
            var experimentUpdate = ExperimentUpdate.builder()
                    .type(ExperimentType.MINI_BATCH)
                    .build();

            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
                    .verifyComplete();

            verify(experimentDAO).update(experimentId, experimentUpdate);
        }

        @Test
        @DisplayName("when updating experiment with only status, then only status is updated")
        void updateExperiment_whenOnlyStatus_thenOnlyStatusUpdated() {
            // given
            var experimentId = UUID.randomUUID();
            var experimentUpdate = ExperimentUpdate.builder()
                    .status(ExperimentStatus.COMPLETED)
                    .build();

            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
                    .verifyComplete();

            verify(experimentDAO).update(experimentId, experimentUpdate);
        }

        @Test
        @DisplayName("when updating experiment with all fields, then all fields are updated")
        void updateExperiment_whenAllFields_thenAllFieldsUpdated() {
            // given
            var experimentId = UUID.randomUUID();
            var metadata = objectMapper.createObjectNode()
                    .put("model", "gpt-4")
                    .put("temperature", 0.8);
            var experimentUpdate = ExperimentUpdate.builder()
                    .name("Complete Update")
                    .metadata(metadata)
                    .type(ExperimentType.TRIAL)
                    .status(ExperimentStatus.CANCELLED)
                    .build();

            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
                    .verifyComplete();

            verify(experimentDAO).update(experimentId, experimentUpdate);
        }

        @Test
        @DisplayName("when updating non-existent experiment, then NotFoundException is thrown")
        void updateExperiment_whenNonExistentExperiment_thenNotFoundExceptionThrown() {
            // given
            var experimentId = UUID.randomUUID();
            var experimentUpdate = ExperimentUpdate.builder()
                    .name("Update Non-Existent")
                    .build();

            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.error(new NotFoundException("Experiment not found")));

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
                    .expectError(NotFoundException.class)
                    .verify();

            verify(experimentDAO).update(experimentId, experimentUpdate);
        }

        @Test
        @DisplayName("when DAO update fails, then exception is propagated")
        void updateExperiment_whenDAOUpdateFails_thenExceptionPropagated() {
            // given
            var experimentId = UUID.randomUUID();
            var experimentUpdate = ExperimentUpdate.builder()
                    .name("Update Failed")
                    .build();

            var expectedError = new RuntimeException("Database error");
            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.error(expectedError));

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
                    .expectError(RuntimeException.class)
                    .verify();

            verify(experimentDAO).update(experimentId, experimentUpdate);
        }

        @Test
        @DisplayName("when experiment update is empty, then update succeeds")
        void updateExperiment_whenEmptyUpdate_thenUpdateSucceeds() {
            // given
            var experimentId = UUID.randomUUID();
            var experimentUpdate = ExperimentUpdate.builder().build();

            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
                    .verifyComplete();

            verify(experimentDAO).update(experimentId, experimentUpdate);
        }
    }
}
