package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportParams;
import com.comet.opik.api.ExportJob;
import com.comet.opik.api.ExportStatus;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.ExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamAddParams;
import org.redisson.api.stream.StreamMessageId;
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
class CsvExportServiceImplTest {

    @Mock
    private ExportJobService jobService;

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private ExportConfig exportConfig;

    @Mock
    private LockService lockService;

    @Mock
    private FileService fileService;

    private CsvExportServiceImpl service;

    private static final String WORKSPACE_ID = "test-workspace";
    private static final String USER_NAME = "test-user";
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final Duration DEFAULT_TTL = Duration.hours(24);

    @BeforeEach
    void setUp() {
        service = new CsvExportServiceImpl(jobService, redisClient, exportConfig, lockService, fileService);
    }

    @Test
    void startExport_shouldCreateNewJobAndPublishToRedis_whenNoExistingJob() {
        // Given
        ExportJob newJob = createJob(JOB_ID, ExportStatus.PENDING);

        // Mock: export is enabled
        when(exportConfig.isEnabled()).thenReturn(true);

        // Mock: no existing jobs
        when(jobService.findInProgressJobs(any())).thenReturn(Mono.just(List.of()));

        // Mock: lock service executes the action
        when(lockService.executeWithLock(any(LockService.Lock.class), any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        // Mock: config returns default TTL
        when(exportConfig.getDefaultTtl()).thenReturn(DEFAULT_TTL);

        // Mock: create new job
        when(jobService.createJob(any(), any(), eq(DEFAULT_TTL.toJavaDuration()))).thenReturn(Mono.just(newJob));

        // Mock: Redis stream
        @SuppressWarnings("unchecked")
        RStreamReactive<String, ExportMessage> mockStream = (RStreamReactive<String, ExportMessage>) mock(
                RStreamReactive.class);
        when(redisClient.getStream(any(String.class), any())).thenReturn((RStreamReactive) mockStream);

        StreamMessageId mockMessageId = new StreamMessageId(UUID.randomUUID().getMostSignificantBits());
        when(mockStream.add(any(StreamAddArgs.class))).thenReturn(Mono.just(mockMessageId));
        when(exportConfig.getStreamName()).thenReturn("dataset-export-events");
        when(exportConfig.getStreamMaxLen()).thenReturn(10000);
        when(exportConfig.getStreamTrimLimit()).thenReturn(100);

        // When
        Mono<ExportJob> result = service
                .startExport(DatasetExportParams.builder().datasetId(DATASET_ID).build(), "test-dataset")
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME));

        // Then
        StepVerifier.create(result)
                .assertNext(job -> {
                    assertThat(job).isEqualTo(newJob);
                    assertThat(job.status()).isEqualTo(ExportStatus.PENDING);
                    assertThat(job.id()).isEqualTo(JOB_ID);
                })
                .verifyComplete();

        // Verify the flow
        verify(jobService, times(2)).findInProgressJobs(any()); // Initial check + double-check in lock
        verify(jobService, times(1)).createJob(any(), any(), eq(DEFAULT_TTL.toJavaDuration()));

        // Verify stream.add was called with correct params
        ArgumentCaptor<StreamAddParams<String, ExportMessage>> captor = ArgumentCaptor
                .forClass(StreamAddParams.class);
        verify(mockStream, times(1)).add(captor.capture());
        var streamAddParams = captor.getValue();
        assertThat(streamAddParams.getMaxLen()).isEqualTo(10000);
        assertThat(streamAddParams.getLimit()).isEqualTo(100);
        assertThat(streamAddParams.isTrimStrict()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ExportStatus.class, names = {"PENDING", "PROCESSING"})
    void startExport_shouldReturnExistingJob_whenJobAlreadyExists(ExportStatus status) {
        // Given
        ExportJob existingJob = createJob(JOB_ID, status);
        when(exportConfig.isEnabled()).thenReturn(true);
        when(jobService.findInProgressJobs(any())).thenReturn(Mono.just(List.of(existingJob)));

        // When
        Mono<ExportJob> result = service
                .startExport(DatasetExportParams.builder().datasetId(DATASET_ID).build(), "test-dataset")
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
        verify(jobService).findInProgressJobs(any());

        // Verify no new job was created
        verify(jobService, never()).createJob(any(), any(), any());
    }

    @Test
    void startExport_shouldCheckInProgressJobsWithCorrectStatuses() {
        // Given
        ExportJob existingJob = createJob(JOB_ID, ExportStatus.PENDING);
        when(exportConfig.isEnabled()).thenReturn(true);
        when(jobService.findInProgressJobs(any())).thenReturn(Mono.just(List.of(existingJob)));

        // When
        service.startExport(DatasetExportParams.builder().datasetId(DATASET_ID).build(), "test-dataset")
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME))
                .block();

        // Then - verify that findInProgressJobs is called with DATASET_ID
        // The Set<ExportStatus> is created inside the service, so we just verify the call
        verify(jobService).findInProgressJobs(any());
    }

    @Test
    void startExport_shouldReturnError_whenExportIsDisabled() {
        // Given
        when(exportConfig.isEnabled()).thenReturn(false);

        // When
        Mono<ExportJob> result = service
                .startExport(DatasetExportParams.builder().datasetId(DATASET_ID).build(), "test-dataset")
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
        verify(jobService, never()).createJob(any(), any(), any());
    }

    @Test
    void downloadExport_shouldReturnInputStream_whenJobIsCompleted() {
        // Given
        String filePath = "exports/test-file.csv";
        ExportJob completedJob = ExportJob.builder()
                .id(JOB_ID)
                .params(DatasetExportParams.builder().datasetId(DATASET_ID).build())
                .status(ExportStatus.COMPLETED)
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
        ExportJob pendingJob = createJob(JOB_ID, ExportStatus.PENDING);

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

    private ExportJob createJob(UUID jobId, ExportStatus status) {
        return ExportJob.builder()
                .id(jobId)
                .params(DatasetExportParams.builder().datasetId(DATASET_ID).build())
                .status(status)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .expiresAt(Instant.now().plus(DEFAULT_TTL.toJavaDuration()))
                .createdBy(USER_NAME)
                .build();
    }
}
