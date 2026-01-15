package com.comet.opik.domain;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Provider;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatasetVersioningMigrationService Unit Tests")
class DatasetVersioningMigrationServiceTest {

    @Mock
    private LockService lockService;

    @Mock
    private DatasetVersionDAO datasetVersionDAO;

    @Mock
    private DatasetItemVersionDAO datasetItemVersionDAO;

    @Mock
    private Jdbi jdbi;

    @Mock
    private RequestContext requestContext;

    private DatasetVersioningMigrationService migrationService;

    private static final String TEST_WORKSPACE_ID = "test-workspace";
    private static final String TEST_USER_NAME = "test-user";
    private static final UUID UUID_MIN = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        Provider<RequestContext> requestContextProvider = () -> requestContext;
        migrationService = new DatasetVersioningMigrationService(
                lockService,
                datasetItemVersionDAO,
                jdbi,
                requestContextProvider);
    }

    @Test
    @DisplayName("should complete migration when no datasets need migration")
    void shouldCompleteMigration_whenNoDatasetsNeedMigration() {
        // Given
        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        when(datasetItemVersionDAO.findDatasetsWithHashMismatch(100, UUID_MIN))
                .thenReturn(Flux.empty());

        // When
        Mono<Void> result = migrationService.runMigration(100, Duration.ofSeconds(3600));

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(datasetItemVersionDAO).findDatasetsWithHashMismatch(100, UUID_MIN);
    }

    @Test
    @DisplayName("should migrate single dataset successfully")
    void shouldMigrateSingleDataset_successfully() {
        // Given
        UUID datasetId = UUID.randomUUID();

        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        // First batch returns one dataset, second batch returns empty
        when(datasetItemVersionDAO.findDatasetsWithHashMismatch(eq(100), eq(UUID_MIN)))
                .thenReturn(Flux.just(datasetId));
        when(datasetItemVersionDAO.findDatasetsWithHashMismatch(eq(100), eq(datasetId)))
                .thenReturn(Flux.empty());

        // Mock the migration steps
        when(datasetItemVersionDAO.deleteItemsFromVersion(any(), any()))
                .thenReturn(Mono.just(0L));
        when(datasetItemVersionDAO.copyItemsFromLegacy(any(), any()))
                .thenReturn(Mono.just(10L));
        when(datasetItemVersionDAO.countItemsInVersion(any(), any()))
                .thenReturn(Mono.just(10L));

        Context context = Context.of(
                RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID,
                RequestContext.USER_NAME, TEST_USER_NAME);

        // When
        Mono<Void> result = migrationService.runMigration(100, Duration.ofSeconds(3600))
                .contextWrite(context);

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(datasetItemVersionDAO).findDatasetsWithHashMismatch(100, UUID_MIN);
        verify(datasetItemVersionDAO).findDatasetsWithHashMismatch(100, datasetId);
        verify(datasetItemVersionDAO).deleteItemsFromVersion(datasetId, datasetId);
        verify(datasetItemVersionDAO).copyItemsFromLegacy(datasetId, datasetId);
        verify(datasetItemVersionDAO).countItemsInVersion(datasetId, datasetId);
    }

    @Test
    @DisplayName("should process multiple batches with cursor pagination")
    void shouldProcessMultipleBatches_withCursorPagination() {
        // Given
        UUID dataset1 = UUID.randomUUID();
        UUID dataset2 = UUID.randomUUID();
        UUID dataset3 = UUID.randomUUID();

        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        // First batch returns dataset1 and dataset2
        when(datasetItemVersionDAO.findDatasetsWithHashMismatch(eq(2), eq(UUID_MIN)))
                .thenReturn(Flux.just(dataset1, dataset2));

        // Second batch (cursor = dataset2) returns dataset3
        when(datasetItemVersionDAO.findDatasetsWithHashMismatch(eq(2), eq(dataset2)))
                .thenReturn(Flux.just(dataset3));

        // Third batch (cursor = dataset3) returns empty
        when(datasetItemVersionDAO.findDatasetsWithHashMismatch(eq(2), eq(dataset3)))
                .thenReturn(Flux.empty());

        // Mock the migration steps for all datasets
        when(datasetItemVersionDAO.deleteItemsFromVersion(any(), any()))
                .thenReturn(Mono.just(0L));
        when(datasetItemVersionDAO.copyItemsFromLegacy(any(), any()))
                .thenReturn(Mono.just(5L));
        when(datasetItemVersionDAO.countItemsInVersion(any(), any()))
                .thenReturn(Mono.just(5L));

        Context context = Context.of(
                RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID,
                RequestContext.USER_NAME, TEST_USER_NAME);

        // When
        Mono<Void> result = migrationService.runMigration(2, Duration.ofSeconds(3600))
                .contextWrite(context);

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify cursor-based pagination
        verify(datasetItemVersionDAO).findDatasetsWithHashMismatch(2, UUID_MIN);
        verify(datasetItemVersionDAO).findDatasetsWithHashMismatch(2, dataset2);
        verify(datasetItemVersionDAO).findDatasetsWithHashMismatch(2, dataset3);

        // Verify all three datasets were migrated
        verify(datasetItemVersionDAO).deleteItemsFromVersion(dataset1, dataset1);
        verify(datasetItemVersionDAO).deleteItemsFromVersion(dataset2, dataset2);
        verify(datasetItemVersionDAO).deleteItemsFromVersion(dataset3, dataset3);
    }

    @Test
    @DisplayName("should handle empty dataset migration correctly")
    void shouldHandleEmptyDataset_correctly() {
        // Given
        UUID datasetId = UUID.randomUUID();

        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        when(datasetItemVersionDAO.findDatasetsWithHashMismatch(eq(100), eq(UUID_MIN)))
                .thenReturn(Flux.just(datasetId));
        when(datasetItemVersionDAO.findDatasetsWithHashMismatch(eq(100), eq(datasetId)))
                .thenReturn(Flux.empty());

        // Mock empty dataset (no items)
        when(datasetItemVersionDAO.deleteItemsFromVersion(any(), any()))
                .thenReturn(Mono.just(0L));
        when(datasetItemVersionDAO.copyItemsFromLegacy(any(), any()))
                .thenReturn(Mono.just(0L)); // No items copied
        when(datasetItemVersionDAO.countItemsInVersion(any(), any()))
                .thenReturn(Mono.just(0L)); // Count is 0

        Context context = Context.of(
                RequestContext.WORKSPACE_ID, TEST_WORKSPACE_ID,
                RequestContext.USER_NAME, TEST_USER_NAME);

        // When
        Mono<Void> result = migrationService.runMigration(100, Duration.ofSeconds(3600))
                .contextWrite(context);

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(datasetItemVersionDAO).countItemsInVersion(datasetId, datasetId);
    }

    @Test
    @DisplayName("should use lock with correct timeout")
    void shouldUseLock_withCorrectTimeout() {
        // Given
        Duration lockTimeout = Duration.ofSeconds(7200);
        int batchSize = 50;

        when(lockService.executeWithLockCustomExpire(any(), any(), eq(lockTimeout)))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        when(datasetItemVersionDAO.findDatasetsWithHashMismatch(batchSize, UUID_MIN))
                .thenReturn(Flux.empty());

        // When
        Mono<Void> result = migrationService.runMigration(batchSize, lockTimeout);

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(lockService).executeWithLockCustomExpire(any(), any(), eq(lockTimeout));
    }
}
