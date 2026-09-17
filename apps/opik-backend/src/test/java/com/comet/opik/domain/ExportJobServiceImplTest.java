package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportParams;
import com.comet.opik.api.ExportJob;
import com.comet.opik.api.ExportStatus;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.NotFoundException;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ExportJobServiceImpl.
 *
 * <p>These tests verify both the service's business logic and DAO interactions by executing
 * the transaction callbacks and mocking the Handle to return the DAO mock. This allows us
 * to verify that the service correctly calls DAO methods with the expected parameters.
 */
@ExtendWith(MockitoExtension.class)
class ExportJobServiceImplTest {

    @Mock
    private ExportJobDAO exportJobDAO;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private TransactionTemplate template;

    @Mock
    private Handle handle;

    private ExportJobServiceImpl service;

    private static final String WORKSPACE_ID = "test-workspace";
    private static final String USER_NAME = "test-user";
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ExportJobServiceImpl(idGenerator, template);

        // Setup common transaction template behavior - execute callbacks
        when(template.inTransaction(any(), any())).thenAnswer(invocation -> {
            TxAction<?> callback = invocation.getArgument(1);
            return callback.execute(handle);
        });
        when(handle.attach(ExportJobDAO.class)).thenReturn(exportJobDAO);
    }

    @Test
    void createJob_shouldCreateNewJobWithGeneratedId() {
        // Given
        Duration ttl = Duration.ofHours(24);
        when(idGenerator.generateId()).thenReturn(JOB_ID);

        // When
        Mono<ExportJob> result = service
                .createJob(DatasetExportParams.builder().datasetId(DATASET_ID).build(), "test-dataset",
                        PROJECT_ID, ttl)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .assertNext(job -> {
                    assertThat(job.id()).isEqualTo(JOB_ID);
                    assertThat(job.params()).isEqualTo(
                            DatasetExportParams.builder().datasetId(DATASET_ID).build());
                    assertThat(job.status()).isEqualTo(ExportStatus.PENDING);
                    assertThat(job.createdBy()).isEqualTo(USER_NAME);
                    assertThat(job.createdAt()).isNotNull();
                    assertThat(job.lastUpdatedAt()).isNotNull();
                    assertThat(job.expiresAt()).isNotNull();
                })
                .verifyComplete();

        // Verify DAO.save() was called
        verify(exportJobDAO, times(1)).save(any(ExportJob.class), eq(WORKSPACE_ID));
    }

    @Test
    void findInProgressJobs_shouldReturnEmptyList_whenNoJobsFound() {
        // Given
        when(exportJobDAO.findInProgressByParams(any(), any(), any(), any(), any())).thenReturn(List.of());

        // When
        Mono<List<ExportJob>> result = service
                .findInProgressJobs(DatasetExportParams.builder().datasetId(DATASET_ID).build())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .assertNext(jobs -> assertThat(jobs).isEmpty())
                .verifyComplete();

        // Verify DAO.findInProgressByParams() was called
        verify(exportJobDAO, times(1)).findInProgressByParams(eq(WORKSPACE_ID), any(), any(), eq(USER_NAME), any());
    }

    @Test
    void findInProgressJobs_shouldReturnJobs_whenJobsExist() {
        // Given
        ExportJob job1 = ExportJob.builder()
                .id(UUID.randomUUID())
                .params(DatasetExportParams.builder().datasetId(DATASET_ID).build())
                .status(ExportStatus.PENDING)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                .createdBy(USER_NAME)
                .build();

        ExportJob job2 = ExportJob.builder()
                .id(UUID.randomUUID())
                .params(DatasetExportParams.builder().datasetId(DATASET_ID).build())
                .status(ExportStatus.PROCESSING)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                .createdBy(USER_NAME)
                .build();

        when(exportJobDAO.findInProgressByParams(any(), any(), any(), any(), any())).thenReturn(List.of(job1, job2));

        // When
        Mono<List<ExportJob>> result = service
                .findInProgressJobs(DatasetExportParams.builder().datasetId(DATASET_ID).build())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .assertNext(jobs -> {
                    assertThat(jobs).hasSize(2);
                    assertThat(jobs).containsExactly(job1, job2);
                })
                .verifyComplete();

        // Verify DAO.findInProgressByParams() was called
        verify(exportJobDAO, times(1)).findInProgressByParams(eq(WORKSPACE_ID), any(), any(), eq(USER_NAME), any());
    }

    @Test
    void getJob_shouldReturnJob_whenJobExists() {
        // Given
        ExportJob job = ExportJob.builder()
                .id(JOB_ID)
                .params(DatasetExportParams.builder().datasetId(DATASET_ID).build())
                .status(ExportStatus.COMPLETED)
                .filePath("workspace/exports/dataset-123/job-456.csv")
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                .createdBy(USER_NAME)
                .build();

        when(exportJobDAO.findById(WORKSPACE_ID, JOB_ID, USER_NAME)).thenReturn(java.util.Optional.of(job));

        // When
        Mono<ExportJob> result = service.getJob(JOB_ID)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .assertNext(returnedJob -> {
                    assertThat(returnedJob).isEqualTo(job);
                })
                .verifyComplete();

        // Verify DAO.findById() was called
        verify(exportJobDAO, times(1)).findById(eq(WORKSPACE_ID), eq(JOB_ID), eq(USER_NAME));
    }

    @Test
    void getJob_shouldThrowNotFoundException_whenJobDoesNotExist() {
        // Given
        when(exportJobDAO.findById(WORKSPACE_ID, JOB_ID, USER_NAME)).thenReturn(java.util.Optional.empty());

        // When
        Mono<ExportJob> result = service.getJob(JOB_ID)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof NotFoundException &&
                        throwable.getMessage().contains(JOB_ID.toString()))
                .verify();

        // Verify DAO.findById() was called
        verify(exportJobDAO, times(1)).findById(eq(WORKSPACE_ID), eq(JOB_ID), eq(USER_NAME));
    }

    @Test
    void updateJobToProcessing_shouldUpdateStatusToProcessing() {
        // Given
        when(exportJobDAO.markPendingJobAsProcessing(any(), any(), any())).thenReturn(1);

        // When
        Mono<Void> result = service.updateJobToProcessing(JOB_ID)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify DAO.markPendingJobAsProcessing() was called
        verify(exportJobDAO, times(1)).markPendingJobAsProcessing(eq(WORKSPACE_ID), eq(JOB_ID), eq(USER_NAME));
    }

    @Test
    void updateJobToProcessing_shouldThrowNotFoundException_whenJobNotPending() {
        // Given - DAO returns 0 because job is not in PENDING state
        when(exportJobDAO.markPendingJobAsProcessing(any(), any(), any())).thenReturn(0);

        // When
        Mono<Void> result = service.updateJobToProcessing(JOB_ID)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof NotFoundException &&
                        throwable.getMessage().contains(JOB_ID.toString()))
                .verify();
    }

    @Test
    void updateJobToCompleted_shouldUpdateStatusAndFilePath() {
        // Given
        String filePath = "workspace/exports/dataset-123/job-456.csv";
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
        when(exportJobDAO.updateToCompleted(any(), any(), any(), any(), any(), any())).thenReturn(1);

        // When
        Mono<Void> result = service.updateJobToCompleted(JOB_ID, filePath, expiresAt)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify DAO.updateToCompleted() was called
        verify(exportJobDAO, times(1)).updateToCompleted(eq(WORKSPACE_ID), eq(JOB_ID),
                eq(ExportStatus.COMPLETED),
                eq(filePath), eq(expiresAt), eq(USER_NAME));
    }

    @Test
    void updateJobToFailed_shouldUpdateStatusAndErrorMessage() {
        // Given
        String errorMessage = "Export failed due to timeout";
        when(exportJobDAO.updateToFailed(any(), any(), any(), any())).thenReturn(1);

        // When
        Mono<Void> result = service.updateJobToFailed(JOB_ID, errorMessage)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify DAO.updateToFailed() was called
        verify(exportJobDAO, times(1)).updateToFailed(eq(WORKSPACE_ID), eq(JOB_ID), eq(errorMessage), eq(USER_NAME));
    }

    @ParameterizedTest
    @MethodSource("provideUpdateJobNotFoundScenarios")
    void updateJob_shouldThrowNotFoundException_whenJobDoesNotExist(
            ExportStatus status, String filePath, Instant expiresAt, String errorMessage) {
        // Given
        if (status == ExportStatus.COMPLETED) {
            when(exportJobDAO.updateToCompleted(any(), any(), any(), any(), any(), any())).thenReturn(0);
        } else {
            when(exportJobDAO.updateToFailed(any(), any(), any(), any())).thenReturn(0);
        }

        // When
        Mono<Void> result = (status == ExportStatus.COMPLETED)
                ? service.updateJobToCompleted(JOB_ID, filePath, expiresAt)
                : service.updateJobToFailed(JOB_ID, errorMessage);

        result = result.contextWrite(ctx -> ctx
                .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof NotFoundException &&
                        throwable.getMessage().contains(JOB_ID.toString()))
                .verify();

        // Verify appropriate DAO method was called
        if (status == ExportStatus.COMPLETED) {
            verify(exportJobDAO, times(1)).updateToCompleted(eq(WORKSPACE_ID), eq(JOB_ID),
                    eq(ExportStatus.COMPLETED), eq(filePath), eq(expiresAt), eq(USER_NAME));
        } else {
            verify(exportJobDAO, times(1)).updateToFailed(eq(WORKSPACE_ID), eq(JOB_ID), eq(errorMessage),
                    eq(USER_NAME));
        }
    }

    private static Stream<Arguments> provideUpdateJobNotFoundScenarios() {
        String filePath = "workspace/exports/dataset-%s/job-%s.csv".formatted(
                UUID.randomUUID(), UUID.randomUUID());
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
        String errorMessage = "Export failed due to timeout";

        return Stream.of(
                Arguments.of(ExportStatus.COMPLETED, filePath, expiresAt, null),
                Arguments.of(ExportStatus.FAILED, null, null, errorMessage));
    }
}
