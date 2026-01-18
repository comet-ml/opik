package com.comet.opik.domain;

import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.DatasetExportConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasetExportCleanupJobTest {

    @Mock
    private DatasetExportJobService exportJobService;

    @Mock
    private FileService fileService;

    @Mock
    private LockService lockService;

    @Mock
    private DatasetExportConfig exportConfig;

    private DatasetExportCleanupJob cleanupJob;

    @BeforeEach
    void setUp() {
        cleanupJob = new DatasetExportCleanupJob(exportJobService, fileService, lockService, exportConfig);

        // Mock configuration
        lenient().when(exportConfig.getCleanupTimeout()).thenReturn(Duration.minutes(5));
        lenient().when(exportConfig.getCleanupLockWaitTime()).thenReturn(Duration.seconds(1));
        lenient().when(exportConfig.getCleanupBatchSize()).thenReturn(100);

        // Mock lock service to execute the operation synchronously by returning it
        lenient().when(lockService.bestEffortLock(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1)); // Return the operation Mono

        // Mock findExpiredCompletedJobs to return empty by default
        lenient().when(exportJobService.findExpiredCompletedJobs(any(Instant.class), anyInt()))
                .thenReturn(Mono.just(List.of()));
        // Mock findViewedFailedJobs to return empty by default
        lenient().when(exportJobService.findViewedFailedJobs(anyInt())).thenReturn(Mono.just(List.of()));
    }

    @Test
    void doJob_shouldDeleteFilesAndRecords_whenExpiredJobsExist() {
        // Given
        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();
        String filePath1 = "exports/workspace1/datasets/dataset1/job1.csv";
        String filePath2 = "exports/workspace1/datasets/dataset2/job2.csv";

        DatasetExportJob expiredJob1 = DatasetExportJob.builder()
                .id(jobId1)
                .datasetId(UUID.randomUUID())
                .status(DatasetExportStatus.COMPLETED)
                .filePath(filePath1)
                .createdAt(Instant.now().minus(java.time.Duration.ofDays(2)))
                .lastUpdatedAt(Instant.now().minus(java.time.Duration.ofDays(2)))
                .expiresAt(Instant.now().minus(java.time.Duration.ofHours(1)))
                .createdBy("user1")
                .build();

        DatasetExportJob expiredJob2 = DatasetExportJob.builder()
                .id(jobId2)
                .datasetId(UUID.randomUUID())
                .status(DatasetExportStatus.COMPLETED)
                .filePath(filePath2)
                .createdAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .lastUpdatedAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .expiresAt(Instant.now().minus(java.time.Duration.ofMinutes(30)))
                .createdBy("user2")
                .build();

        when(exportJobService.findExpiredCompletedJobs(any(Instant.class), anyInt()))
                .thenReturn(Mono.just(List.of(expiredJob1, expiredJob2)), Mono.just(List.of()));
        when(exportJobService.deleteExpiredJobs(any())).thenReturn(Mono.just(2));

        // When
        cleanupJob.doJob(null);

        // Then
        // Verify findExpiredCompletedJobs was called twice (batch loop)
        verify(exportJobService, times(2)).findExpiredCompletedJobs(any(Instant.class), eq(100));

        // Verify files were deleted
        ArgumentCaptor<Set<String>> filePathsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(fileService, times(1)).deleteObjects(filePathsCaptor.capture());
        Set<String> deletedPaths = filePathsCaptor.getValue();
        assertThat(deletedPaths).containsExactlyInAnyOrder(filePath1, filePath2);

        // Verify database records were deleted
        ArgumentCaptor<Set<UUID>> jobIdsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(exportJobService, times(1)).deleteExpiredJobs(jobIdsCaptor.capture());
        Set<UUID> deletedIds = jobIdsCaptor.getValue();
        assertThat(deletedIds).containsExactlyInAnyOrder(jobId1, jobId2);
    }

    @Test
    void doJob_shouldSkipFilesDeletion_whenFilePathIsNull() {
        // Given
        UUID jobId = UUID.randomUUID();

        DatasetExportJob expiredJob = DatasetExportJob.builder()
                .id(jobId)
                .datasetId(UUID.randomUUID())
                .status(DatasetExportStatus.FAILED)
                .filePath(null) // No file path
                .createdAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .lastUpdatedAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .expiresAt(Instant.now().minus(java.time.Duration.ofHours(1)))
                .createdBy("user1")
                .build();

        when(exportJobService.findExpiredCompletedJobs(any(Instant.class), anyInt()))
                .thenReturn(Mono.just(List.of(expiredJob)), Mono.just(List.of()));
        when(exportJobService.deleteExpiredJobs(any())).thenReturn(Mono.just(1));

        // When
        cleanupJob.doJob(null);

        // Then
        // Verify files were NOT deleted (empty set)
        verify(fileService, never()).deleteObjects(any());

        // Verify database record was still deleted
        verify(exportJobService, times(1)).deleteExpiredJobs(eq(Set.of(jobId)));
    }

    @Test
    void doJob_shouldDoNothing_whenNoExpiredJobsFound() {
        // Given - using default mocks from setUp() (both return empty lists)

        // When
        cleanupJob.doJob(null);

        // Then
        verify(exportJobService, times(1)).findExpiredCompletedJobs(any(Instant.class), eq(100));
        verify(exportJobService, times(1)).findViewedFailedJobs(eq(100));
        verify(fileService, never()).deleteObjects(any());
        verify(exportJobService, never()).deleteExpiredJobs(any());
    }

    @Test
    void doJob_shouldHandleBlankFilePaths() {
        // Given
        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();
        String validFilePath = "exports/workspace1/datasets/dataset1/job1.csv";

        DatasetExportJob jobWithValidPath = DatasetExportJob.builder()
                .id(jobId1)
                .datasetId(UUID.randomUUID())
                .status(DatasetExportStatus.COMPLETED)
                .filePath(validFilePath)
                .createdAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .lastUpdatedAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .expiresAt(Instant.now().minus(java.time.Duration.ofHours(1)))
                .createdBy("user1")
                .build();

        DatasetExportJob jobWithBlankPath = DatasetExportJob.builder()
                .id(jobId2)
                .datasetId(UUID.randomUUID())
                .status(DatasetExportStatus.COMPLETED)
                .filePath("   ") // Blank path
                .createdAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .lastUpdatedAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .expiresAt(Instant.now().minus(java.time.Duration.ofHours(1)))
                .createdBy("user2")
                .build();

        when(exportJobService.findExpiredCompletedJobs(any(Instant.class), anyInt()))
                .thenReturn(Mono.just(List.of(jobWithValidPath, jobWithBlankPath)), Mono.just(List.of()));
        when(exportJobService.deleteExpiredJobs(any())).thenReturn(Mono.just(2));

        // When
        cleanupJob.doJob(null);

        // Then
        // Verify only the valid file path was deleted
        ArgumentCaptor<Set<String>> filePathsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(fileService, times(1)).deleteObjects(filePathsCaptor.capture());
        Set<String> deletedPaths = filePathsCaptor.getValue();
        assertThat(deletedPaths).containsExactly(validFilePath);

        // Verify both database records were deleted
        ArgumentCaptor<Set<UUID>> jobIdsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(exportJobService, times(1)).deleteExpiredJobs(jobIdsCaptor.capture());
        Set<UUID> deletedIds = jobIdsCaptor.getValue();
        assertThat(deletedIds).containsExactlyInAnyOrder(jobId1, jobId2);
    }

    @Test
    void doJob_shouldDeleteViewedFailedJobs() {
        // Given
        UUID failedJobId1 = UUID.randomUUID();
        UUID failedJobId2 = UUID.randomUUID();
        String filePath1 = "exports/workspace1/datasets/dataset1/failed-job1.csv";

        // Failed job with partial file (failure after upload started)
        DatasetExportJob viewedFailedJob1 = DatasetExportJob.builder()
                .id(failedJobId1)
                .datasetId(UUID.randomUUID())
                .status(DatasetExportStatus.FAILED)
                .filePath(filePath1)
                .errorMessage("Export failed due to error")
                .viewedAt(Instant.now().minus(java.time.Duration.ofHours(2)))
                .createdAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .lastUpdatedAt(Instant.now().minus(java.time.Duration.ofDays(1)))
                .createdBy("user1")
                .build();

        // Failed job without file (failure before upload)
        DatasetExportJob viewedFailedJob2 = DatasetExportJob.builder()
                .id(failedJobId2)
                .datasetId(UUID.randomUUID())
                .status(DatasetExportStatus.FAILED)
                .errorMessage("Another export failure")
                .viewedAt(Instant.now().minus(java.time.Duration.ofMinutes(30)))
                .createdAt(Instant.now().minus(java.time.Duration.ofHours(12)))
                .lastUpdatedAt(Instant.now().minus(java.time.Duration.ofHours(12)))
                .createdBy("user2")
                .build();

        when(exportJobService.findExpiredCompletedJobs(any(Instant.class), anyInt()))
                .thenReturn(Mono.just(List.of()));
        when(exportJobService.findViewedFailedJobs(anyInt()))
                .thenReturn(Mono.just(List.of(viewedFailedJob1, viewedFailedJob2)), Mono.just(List.of()));
        when(exportJobService.deleteExpiredJobs(any())).thenReturn(Mono.just(2));

        // When
        cleanupJob.doJob(null);

        // Then
        // Verify findViewedFailedJobs was called twice (batch loop)
        verify(exportJobService, times(2)).findViewedFailedJobs(eq(100));

        // Verify file from failed job was deleted
        ArgumentCaptor<Set<String>> filePathCaptor = ArgumentCaptor.forClass(Set.class);
        verify(fileService, times(1)).deleteObjects(filePathCaptor.capture());
        Set<String> deletedFilePaths = filePathCaptor.getValue();
        assertThat(deletedFilePaths).containsExactly(filePath1);

        // Verify database records were deleted
        ArgumentCaptor<Set<UUID>> jobIdsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(exportJobService, times(1)).deleteExpiredJobs(jobIdsCaptor.capture());
        Set<UUID> deletedIds = jobIdsCaptor.getValue();
        assertThat(deletedIds).containsExactlyInAnyOrder(failedJobId1, failedJobId2);
    }
}
