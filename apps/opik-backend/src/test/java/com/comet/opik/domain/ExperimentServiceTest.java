package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.sorting.ExperimentSortingFactory;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregationPublisher;
import com.comet.opik.infrastructure.ExperimentAggregatesConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceTest {

    private static final String TEST_WORKSPACE_ID = "test-workspace-id";
    private static final String TEST_USER_NAME = "test-user";

    private ExperimentService experimentService;

    @Mock
    private ExperimentDAO experimentDAO;

    @Mock
    private ExperimentItemDAO experimentItemDAO;

    @Mock
    private DatasetService datasetService;

    @Mock
    private DatasetVersionService datasetVersionService;

    @Mock
    private ProjectService projectService;

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

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private OpikConfiguration config;

    @Mock
    private ExperimentGroupEnricher experimentGroupEnricher;

    @Mock
    private ExperimentAggregatesService experimentAggregatesService;

    @Mock
    private ExperimentAggregationPublisher experimentAggregationPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @BeforeEach
    void setUp() {
        var aggregatesConfig = new ExperimentAggregatesConfig();
        lenient().when(config.getExperimentAggregates()).thenReturn(aggregatesConfig);

        experimentService = new ExperimentService(
                experimentDAO,
                experimentItemDAO,
                datasetService,
                datasetVersionService,
                projectService,
                idGenerator,
                nameGenerator,
                eventBus,
                promptService,
                sortingFactory,
                responseBuilder,
                featureFlags,
                config,
                experimentGroupEnricher,
                experimentAggregatesService,
                experimentAggregationPublisher);
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
                    .tags(Set.of("tag1", "tag2"))
                    .build();

            var existingExperiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .id(experimentId)
                    .build();

            when(experimentDAO.getById(experimentId))
                    .thenReturn(Mono.just(existingExperiment));
            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .verifyComplete();

            verify(experimentDAO).getById(experimentId);
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

            var existingExperiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .id(experimentId)
                    .build();

            when(experimentDAO.getById(experimentId))
                    .thenReturn(Mono.just(existingExperiment));
            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .verifyComplete();

            verify(experimentDAO).getById(experimentId);
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

            var existingExperiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .id(experimentId)
                    .build();

            when(experimentDAO.getById(experimentId))
                    .thenReturn(Mono.just(existingExperiment));
            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .verifyComplete();

            verify(experimentDAO).getById(experimentId);
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

            var existingExperiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .id(experimentId)
                    .build();

            when(experimentDAO.getById(experimentId))
                    .thenReturn(Mono.just(existingExperiment));
            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .verifyComplete();

            verify(experimentDAO).getById(experimentId);
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

            var existingExperiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .id(experimentId)
                    .build();

            when(experimentDAO.getById(experimentId))
                    .thenReturn(Mono.just(existingExperiment));
            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .verifyComplete();

            verify(experimentDAO).getById(experimentId);
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

            var existingExperiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .id(experimentId)
                    .build();

            when(experimentDAO.getById(experimentId))
                    .thenReturn(Mono.just(existingExperiment));
            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .verifyComplete();

            verify(experimentDAO).getById(experimentId);
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

            when(experimentDAO.getById(experimentId))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .expectError(NotFoundException.class)
                    .verify();

            verify(experimentDAO).getById(experimentId);
        }

        @Test
        @DisplayName("when DAO update fails, then exception is propagated")
        void updateExperiment_whenDAOUpdateFails_thenExceptionPropagated() {
            // given
            var experimentId = UUID.randomUUID();
            var experimentUpdate = ExperimentUpdate.builder()
                    .name("Update Failed")
                    .build();

            var existingExperiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .id(experimentId)
                    .build();

            var expectedError = new RuntimeException("Database error");
            when(experimentDAO.getById(experimentId))
                    .thenReturn(Mono.just(existingExperiment));
            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.error(expectedError));

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .expectError(RuntimeException.class)
                    .verify();

            verify(experimentDAO).getById(experimentId);
            verify(experimentDAO).update(experimentId, experimentUpdate);
        }

        @Test
        @DisplayName("when experiment update is empty, then update succeeds")
        void updateExperiment_whenEmptyUpdate_thenUpdateSucceeds() {
            // given
            var experimentId = UUID.randomUUID();
            var experimentUpdate = ExperimentUpdate.builder().build();

            var existingExperiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .id(experimentId)
                    .build();

            when(experimentDAO.getById(experimentId))
                    .thenReturn(Mono.just(existingExperiment));
            when(experimentDAO.update(eq(experimentId), any(ExperimentUpdate.class)))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .verifyComplete();

            verify(experimentDAO).getById(experimentId);
            verify(experimentDAO).update(experimentId, experimentUpdate);
        }
    }

    @Nested
    @DisplayName("GetById Lazy Aggregation:")
    class GetByIdLazyAggregation {

        private Experiment buildExperimentWithEnrichmentMocks(UUID experimentId, ExperimentStatus status) {
            var datasetId = UUID.randomUUID();
            var experiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .id(experimentId)
                    .status(status)
                    .datasetId(datasetId)
                    .datasetVersionId(null)
                    .projectId(null)
                    .promptVersion(null)
                    .promptVersions(null)
                    .build();

            when(experimentDAO.getById(experimentId))
                    .thenReturn(Mono.just(experiment));
            when(promptService.getVersionsInfoByVersionsIds(any()))
                    .thenReturn(Mono.just(Map.of()));
            when(datasetService.getById(eq(datasetId), any()))
                    .thenReturn(Optional.of(Dataset.builder()
                            .id(datasetId)
                            .name("test-dataset")
                            .build()));

            return experiment;
        }

        @Test
        @DisplayName("when COMPLETED experiment not in aggregates, then publish is called")
        void getByIdWhenCompletedExperimentNotInAggregatesTriggersPublish() {
            // given
            var experimentId = UUID.randomUUID();
            buildExperimentWithEnrichmentMocks(experimentId, ExperimentStatus.COMPLETED);

            when(experimentAggregatesService.getExperimentFromAggregates(experimentId))
                    .thenReturn(Mono.empty());
            when(experimentAggregationPublisher.publish(any(), any(), any()))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.getById(experimentId)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(experimentAggregationPublisher, timeout(1000))
                    .publish(eq(Set.of(experimentId)), eq(TEST_WORKSPACE_ID), eq(TEST_USER_NAME));
        }

        @Test
        @DisplayName("when RUNNING experiment, then publish is NOT called")
        void getByIdWhenRunningExperimentDoesNotTriggerPublish() {
            // given
            var experimentId = UUID.randomUUID();
            buildExperimentWithEnrichmentMocks(experimentId, ExperimentStatus.RUNNING);

            // when & then
            StepVerifier.create(experimentService.getById(experimentId)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(experimentAggregatesService, never()).getExperimentFromAggregates(any());
            verify(experimentAggregationPublisher, never()).publish(any(), any(), any());
        }

        @Test
        @DisplayName("when experiment already in aggregates, then publish is NOT called")
        void getByIdWhenExperimentAlreadyAggregatedDoesNotTriggerPublish() {
            // given
            var experimentId = UUID.randomUUID();
            var experiment = buildExperimentWithEnrichmentMocks(experimentId, ExperimentStatus.COMPLETED);

            when(experimentAggregatesService.getExperimentFromAggregates(experimentId))
                    .thenReturn(Mono.just(experiment));

            // when & then
            StepVerifier.create(experimentService.getById(experimentId)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, TEST_USER_NAME)))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(experimentAggregationPublisher, never()).publish(any(), any(), any());
        }
    }
}
