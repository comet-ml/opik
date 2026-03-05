package com.comet.opik.domain;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.sorting.ExperimentSortingFactory;
import com.comet.opik.infrastructure.FeatureFlags;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.Experiment.PromptVersionLink;
import static org.assertj.core.api.Assertions.assertThat;
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @BeforeEach
    void setUp() {
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
                featureFlags);
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
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
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
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
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
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
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
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
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
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
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
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
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
            when(experimentDAO.update(experimentId, experimentUpdate))
                    .thenReturn(Mono.empty());

            // when & then
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
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
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
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
            StepVerifier.create(experimentService.update(experimentId, experimentUpdate))
                    .verifyComplete();

            verify(experimentDAO).getById(experimentId);
            verify(experimentDAO).update(experimentId, experimentUpdate);
        }
    }

    @Nested
    @DisplayName("Enrich Prompt Version Link:")
    class EnrichPromptVersionLinkTests {

        private static final String TEST_WORKSPACE = "test-workspace";
        private static final String COMMIT = "abc123";
        private static final String PROMPT_NAME = "my-prompt";
        private static final String CHANGE_DESCRIPTION = "Initial version";

        static Stream<Arguments> promptVersionInfoCases() {
            return Stream.of(
                    Arguments.of(
                            "null PromptVersionInfo — all enriched fields are null",
                            null, null, null, false),
                    Arguments.of(
                            "PromptVersionInfo with all fields set",
                            COMMIT, PROMPT_NAME, CHANGE_DESCRIPTION, true),
                    Arguments.of(
                            "PromptVersionInfo without changeDescription",
                            COMMIT, PROMPT_NAME, null, true));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("promptVersionInfoCases")
        @DisplayName("when enriching prompt version link, then PromptVersionLink fields are populated correctly")
        void enrichPromptVersionLink_whenVariousInfoStates_thenFieldsMappedCorrectly(
                String description,
                String infoCommit,
                String infoPromptName,
                String infoChangeDescription,
                boolean hasInfo) {
            // given
            var versionId = UUID.randomUUID();
            var promptId = UUID.randomUUID();
            var initialLink = new PromptVersionLink(versionId, null, promptId, null, null);

            var experiment = podamFactory.manufacturePojo(Experiment.class)
                    .toBuilder()
                    .promptVersion(initialLink)
                    .promptVersions(null)
                    .datasetVersionId(null)
                    .projectId(null)
                    .build();

            var request = ExperimentStreamRequest.builder().name(experiment.name()).build();

            Map<UUID, PromptVersionInfo> infoMap = hasInfo
                    ? Map.of(versionId, new PromptVersionInfo(
                            versionId, infoCommit, infoPromptName, infoChangeDescription))
                    : Map.of();

            when(experimentDAO.get(request)).thenReturn(Flux.just(experiment));
            when(promptService.getVersionsInfoByVersionsIds(Set.of(versionId)))
                    .thenReturn(Mono.just(infoMap));
            when(datasetService.findByIds(any(), any())).thenReturn(List.of());

            // when & then
            StepVerifier.create(
                            experimentService.get(request)
                                    .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, TEST_WORKSPACE)))
                    .assertNext(result -> {
                        assertThat(result.promptVersion()).isNotNull();
                        assertThat(result.promptVersion().id()).isEqualTo(versionId);
                        assertThat(result.promptVersion().promptId()).isEqualTo(promptId);
                        assertThat(result.promptVersion().commit()).isEqualTo(infoCommit);
                        assertThat(result.promptVersion().promptName()).isEqualTo(infoPromptName);
                        assertThat(result.promptVersion().changeDescription()).isEqualTo(infoChangeDescription);
                    })
                    .verifyComplete();
        }
    }
}
