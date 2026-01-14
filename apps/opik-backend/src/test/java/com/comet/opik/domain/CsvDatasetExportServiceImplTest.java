package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.DatasetExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsvDatasetExportServiceImplTest {

    @Mock
    private DatasetExportJobService jobService;

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private DatasetExportConfig exportConfig;

    @Mock
    private LockService lockService;

    @Mock
    private FileService fileService;

    private CsvDatasetExportServiceImpl service;

    private static final String WORKSPACE_ID = "test-workspace";
    private static final String USER_NAME = "test-user";
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final io.dropwizard.util.Duration DEFAULT_TTL = io.dropwizard.util.Duration.hours(24);

    @BeforeEach
    void setUp() {
        service = new CsvDatasetExportServiceImpl(jobService, redisClient, exportConfig, lockService, fileService);
    }

    @Test
    void startExport_shouldCreateNewJobAndPublishToRedis_whenNoExistingJob() {
        // Given
        DatasetExportJob newJob = createJob(JOB_ID, DatasetExportStatus.PENDING);

        // Mock: export is enabled
        when(exportConfig.isEnabled()).thenReturn(true);

        // Mock: no existing jobs
        when(jobService.findInProgressJobs(DATASET_ID)).thenReturn(Mono.just(List.of()));

        // Mock: lock service executes the action
        when(lockService.executeWithLock(any(LockService.Lock.class), any(Mono.class)))
                .thenAnswer(invocation -> {
                    Mono<DatasetExportJob> action = invocation.getArgument(1);
                    return action;
                });

        // Mock: config returns default TTL
        when(exportConfig.getDefaultTtl()).thenReturn(DEFAULT_TTL);

        // Mock: create new job
        when(jobService.createJob(eq(DATASET_ID), eq(DEFAULT_TTL.toJavaDuration()))).thenReturn(Mono.just(newJob));

        // Mock: Redis stream
        @SuppressWarnings("unchecked")
        RStreamReactive<String, DatasetExportMessage> mockStream = (RStreamReactive<String, DatasetExportMessage>) mock(
                RStreamReactive.class);
        when(redisClient.getStream(any(String.class), any())).thenReturn((RStreamReactive) mockStream);

        StreamMessageId mockMessageId = new StreamMessageId(UUID.randomUUID().getMostSignificantBits());
        when(mockStream.add(any(StreamAddArgs.class))).thenReturn(Mono.just(mockMessageId));
        when(exportConfig.getStreamName()).thenReturn("dataset-export-events");

        // When
        Mono<DatasetExportJob> result = service.startExport(DATASET_ID)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .assertNext(job -> {
                    assertThat(job).isEqualTo(newJob);
                    assertThat(job.status()).isEqualTo(DatasetExportStatus.PENDING);
                    assertThat(job.id()).isEqualTo(JOB_ID);
                })
                .verifyComplete();

        // Verify the flow
        verify(jobService, times(2)).findInProgressJobs(eq(DATASET_ID)); // Initial check + double-check in lock
        verify(jobService, times(1)).createJob(eq(DATASET_ID), eq(DEFAULT_TTL.toJavaDuration()));

        // Verify stream.add was called with correct message
        verify(mockStream, times(1)).add(any(StreamAddArgs.class));
    }

    @ParameterizedTest
    @EnumSource(value = DatasetExportStatus.class, names = {"PENDING", "PROCESSING"})
    void startExport_shouldReturnExistingJob_whenJobAlreadyExists(DatasetExportStatus status) {
        // Given
        DatasetExportJob existingJob = createJob(JOB_ID, status);
        when(exportConfig.isEnabled()).thenReturn(true);
        when(jobService.findInProgressJobs(DATASET_ID)).thenReturn(Mono.just(List.of(existingJob)));

        // When
        Mono<DatasetExportJob> result = service.startExport(DATASET_ID)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .assertNext(job -> {
                    assertThat(job).isEqualTo(existingJob);
                    assertThat(job.status()).isEqualTo(status);
                })
                .verifyComplete();

        // Verify jobService was called to check existing jobs
        verify(jobService).findInProgressJobs(eq(DATASET_ID));

        // Verify no new job was created
        verify(jobService, never()).createJob(any(), any());
    }

    @Test
    void startExport_shouldCheckInProgressJobsWithCorrectStatuses() {
        // Given
        DatasetExportJob existingJob = createJob(JOB_ID, DatasetExportStatus.PENDING);
        when(exportConfig.isEnabled()).thenReturn(true);
        when(jobService.findInProgressJobs(DATASET_ID)).thenReturn(Mono.just(List.of(existingJob)));

        // When
        service.startExport(DATASET_ID)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME))
                .block();

        // Then - verify that findInProgressJobs is called with DATASET_ID
        // The Set<DatasetExportStatus> is created inside the service, so we just verify the call
        verify(jobService).findInProgressJobs(eq(DATASET_ID));
    }

    @Test
    void startExport_shouldReturnError_whenExportIsDisabled() {
        // Given
        when(exportConfig.isEnabled()).thenReturn(false);

        // When
        Mono<DatasetExportJob> result = service.startExport(DATASET_ID)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof IllegalStateException &&
                        throwable.getMessage().contains("Dataset export is disabled"))
                .verify();

        // Verify no job service calls were made
        verify(jobService, never()).findInProgressJobs(any());
        verify(jobService, never()).createJob(any(), any());
    }

    @Test
    void downloadExport_shouldReturnInputStream_whenJobIsCompleted() {
        // Given
        String filePath = "exports/test-file.csv";
        DatasetExportJob completedJob = DatasetExportJob.builder()
                .id(JOB_ID)
                .datasetId(DATASET_ID)
                .status(DatasetExportStatus.COMPLETED)
                .filePath(filePath)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .expiresAt(Instant.now().plus(DEFAULT_TTL.toJavaDuration()))
                .createdBy(USER_NAME)
                .build();

        InputStream mockInputStream = new ByteArrayInputStream("test,data".getBytes());

        when(jobService.getJob(JOB_ID)).thenReturn(Mono.just(completedJob));
        when(fileService.download(filePath)).thenReturn(mockInputStream);

        // When
        Mono<InputStream> result = service.downloadExport(JOB_ID)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .assertNext(inputStream -> assertThat(inputStream).isNotNull())
                .verifyComplete();

        verify(jobService).getJob(JOB_ID);
        verify(fileService).download(filePath);
    }

    @Test
    void downloadExport_shouldReturnError_whenJobIsNotCompleted() {
        // Given
        DatasetExportJob pendingJob = createJob(JOB_ID, DatasetExportStatus.PENDING);

        when(jobService.getJob(JOB_ID)).thenReturn(Mono.just(pendingJob));

        // When
        Mono<InputStream> result = service.downloadExport(JOB_ID)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof jakarta.ws.rs.BadRequestException
                        && throwable.getMessage().contains("is not ready for download"))
                .verify();

        verify(jobService).getJob(JOB_ID);
        verify(fileService, never()).download(any());
    }

    private DatasetExportJob createJob(UUID jobId, DatasetExportStatus status) {
        return DatasetExportJob.builder()
                .id(jobId)
                .datasetId(DATASET_ID)
                .status(status)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .expiresAt(Instant.now().plus(DEFAULT_TTL.toJavaDuration()))
                .createdBy(USER_NAME)
                .build();
    }
}
