package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.NotFoundException;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DatasetExportJobServiceImpl.
 *
 * <p>These tests verify both the service's business logic and DAO interactions by executing
 * the transaction callbacks and mocking the Handle to return the DAO mock. This allows us
 * to verify that the service correctly calls DAO methods with the expected parameters.
 */
@ExtendWith(MockitoExtension.class)
class DatasetExportJobServiceImplTest {

    @Mock
    private DatasetExportJobDAO exportJobDAO;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private TransactionTemplate template;

    @Mock
    private Handle handle;

    private DatasetExportJobServiceImpl service;

    private static final String WORKSPACE_ID = "test-workspace";
    private static final String USER_NAME = "test-user";
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DatasetExportJobServiceImpl(exportJobDAO, idGenerator, template);

        // Setup common transaction template behavior - execute callbacks
        when(template.inTransaction(any(), any())).thenAnswer(invocation -> {
            TxAction<?> callback = invocation.getArgument(1);
            return callback.execute(handle);
        });
        when(handle.attach(DatasetExportJobDAO.class)).thenReturn(exportJobDAO);
    }

    @Test
    void createJob_shouldCreateNewJobWithGeneratedId() {
        // Given
        Duration ttl = Duration.ofHours(24);
        when(idGenerator.generateId()).thenReturn(JOB_ID);

        // When
        Mono<DatasetExportJob> result = service.createJob(DATASET_ID, ttl)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .assertNext(job -> {
                    assertThat(job.id()).isEqualTo(JOB_ID);
                    assertThat(job.datasetId()).isEqualTo(DATASET_ID);
                    assertThat(job.status()).isEqualTo(DatasetExportStatus.PENDING);
                    assertThat(job.createdBy()).isEqualTo(USER_NAME);
                    assertThat(job.createdAt()).isNotNull();
                    assertThat(job.lastUpdatedAt()).isNotNull();
                    assertThat(job.expiresAt()).isNotNull();
                })
                .verifyComplete();

        // Verify DAO.save() was called
        verify(exportJobDAO, times(1)).save(any(DatasetExportJob.class), eq(WORKSPACE_ID));
    }

    @Test
    void findInProgressJobs_shouldReturnEmptyList_whenNoJobsFound() {
        // Given
        when(exportJobDAO.findInProgressByDataset(any(), any(), any())).thenReturn(List.of());

        // When
        Mono<List<DatasetExportJob>> result = service.findInProgressJobs(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(jobs -> assertThat(jobs).isEmpty())
                .verifyComplete();

        // Verify DAO.findInProgressByDataset() was called
        verify(exportJobDAO, times(1)).findInProgressByDataset(eq(WORKSPACE_ID), eq(DATASET_ID), any());
    }

    @Test
    void findInProgressJobs_shouldReturnJobs_whenJobsExist() {
        // Given
        DatasetExportJob job1 = DatasetExportJob.builder()
                .id(UUID.randomUUID())
                .datasetId(DATASET_ID)
                .status(DatasetExportStatus.PENDING)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                .createdBy(USER_NAME)
                .build();

        DatasetExportJob job2 = DatasetExportJob.builder()
                .id(UUID.randomUUID())
                .datasetId(DATASET_ID)
                .status(DatasetExportStatus.PROCESSING)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                .createdBy(USER_NAME)
                .build();

        when(exportJobDAO.findInProgressByDataset(any(), any(), any())).thenReturn(List.of(job1, job2));

        // When
        Mono<List<DatasetExportJob>> result = service.findInProgressJobs(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(jobs -> {
                    assertThat(jobs).hasSize(2);
                    assertThat(jobs).containsExactly(job1, job2);
                })
                .verifyComplete();

        // Verify DAO.findInProgressByDataset() was called
        verify(exportJobDAO, times(1)).findInProgressByDataset(eq(WORKSPACE_ID), eq(DATASET_ID), any());
    }

    @Test
    void getJob_shouldReturnJob_whenJobExists() {
        // Given
        DatasetExportJob job = DatasetExportJob.builder()
                .id(JOB_ID)
                .datasetId(DATASET_ID)
                .status(DatasetExportStatus.COMPLETED)
                .filePath("workspace/exports/dataset-123/job-456.csv")
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                .createdBy(USER_NAME)
                .build();

        when(exportJobDAO.findById(WORKSPACE_ID, JOB_ID)).thenReturn(java.util.Optional.of(job));

        // When
        Mono<DatasetExportJob> result = service.getJob(JOB_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(returnedJob -> {
                    assertThat(returnedJob).isEqualTo(job);
                })
                .verifyComplete();

        // Verify DAO.findById() was called
        verify(exportJobDAO, times(1)).findById(eq(WORKSPACE_ID), eq(JOB_ID));
    }

    @Test
    void getJob_shouldThrowNotFoundException_whenJobDoesNotExist() {
        // Given
        when(exportJobDAO.findById(WORKSPACE_ID, JOB_ID)).thenReturn(java.util.Optional.empty());

        // When
        Mono<DatasetExportJob> result = service.getJob(JOB_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof NotFoundException &&
                        throwable.getMessage().contains(JOB_ID.toString()))
                .verify();

        // Verify DAO.findById() was called
        verify(exportJobDAO, times(1)).findById(eq(WORKSPACE_ID), eq(JOB_ID));
    }

    @Test
    void updateJobToCompleted_shouldUpdateStatusAndFilePath() {
        // Given
        String filePath = "workspace/exports/dataset-123/job-456.csv";
        when(exportJobDAO.updateToCompleted(any(), any(), any(), any())).thenReturn(1);

        // When
        Mono<Void> result = service.updateJobToCompleted(JOB_ID, filePath)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify DAO.updateToCompleted() was called
        verify(exportJobDAO, times(1)).updateToCompleted(eq(WORKSPACE_ID), eq(JOB_ID),
                eq(DatasetExportStatus.COMPLETED),
                eq(filePath));
    }

    @Test
    void updateJobToFailed_shouldUpdateStatusAndErrorMessage() {
        // Given
        String errorMessage = "Export failed due to timeout";
        when(exportJobDAO.updateToFailed(any(), any(), any(), any())).thenReturn(1);

        // When
        Mono<Void> result = service.updateJobToFailed(JOB_ID, errorMessage)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify DAO.updateToFailed() was called
        verify(exportJobDAO, times(1)).updateToFailed(eq(WORKSPACE_ID), eq(JOB_ID), eq(DatasetExportStatus.FAILED),
                eq(errorMessage));
    }

    @Test
    void updateJobToCompleted_shouldThrowNotFoundException_whenJobDoesNotExist() {
        // Given
        String filePath = "workspace/exports/dataset-123/job-456.csv";
        when(exportJobDAO.updateToCompleted(any(), any(), any(), any())).thenReturn(0); // No rows updated

        // When
        Mono<Void> result = service.updateJobToCompleted(JOB_ID, filePath)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof NotFoundException &&
                        throwable.getMessage().contains(JOB_ID.toString()))
                .verify();

        // Verify DAO.updateToCompleted() was called
        verify(exportJobDAO, times(1)).updateToCompleted(eq(WORKSPACE_ID), eq(JOB_ID),
                eq(DatasetExportStatus.COMPLETED),
                eq(filePath));
    }

    @Test
    void updateJobToFailed_shouldThrowNotFoundException_whenJobDoesNotExist() {
        // Given
        String errorMessage = "Export failed due to timeout";
        when(exportJobDAO.updateToFailed(any(), any(), any(), any())).thenReturn(0); // No rows updated

        // When
        Mono<Void> result = service.updateJobToFailed(JOB_ID, errorMessage)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof NotFoundException &&
                        throwable.getMessage().contains(JOB_ID.toString()))
                .verify();

        // Verify DAO.updateToFailed() was called
        verify(exportJobDAO, times(1)).updateToFailed(eq(WORKSPACE_ID), eq(JOB_ID), eq(DatasetExportStatus.FAILED),
                eq(errorMessage));
    }
}
