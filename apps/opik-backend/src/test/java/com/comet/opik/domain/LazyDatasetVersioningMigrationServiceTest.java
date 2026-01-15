package com.comet.opik.domain;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatasetVersioningMigrationService Lazy Migration Unit Tests")
class LazyDatasetVersioningMigrationServiceTest {

    @Mock
    private LockService lockService;

    @Mock
    private DatasetVersionDAO datasetVersionDAO;

    @Mock
    private DatasetItemVersionDAO datasetItemVersionDAO;

    @Mock
    private Jdbi jdbi;

    @Mock
    private Handle handle;

    @Mock
    private RequestContext requestContext;

    private DatasetVersioningMigrationService migrationService;

    private static final String TEST_WORKSPACE_ID = "test-workspace";
    private static final String TEST_USER_NAME = "test-user";
    private static final UUID TEST_DATASET_ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    @BeforeEach
    void setUp() {
        migrationService = new DatasetVersioningMigrationService(
                lockService,
                datasetVersionDAO,
                datasetItemVersionDAO,
                jdbi,
                requestContext);
    }

    @Test
    @DisplayName("should skip migration when dataset already has versions")
    void shouldSkipMigration_whenDatasetAlreadyHasVersions() {
        // Given
        when(jdbi.inTransaction(any(), any())).thenAnswer(invocation -> {
            // Simulate that the dataset has versions
            return 1L;
        });

        // When
        Mono<Void> result = migrationService.ensureDatasetMigrated(
                TEST_DATASET_ID, TEST_WORKSPACE_ID, TEST_USER_NAME);

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify that lock was never acquired since dataset already has versions
        verify(lockService, never()).executeWithLockCustomExpire(any(), any(), any());
    }

    @Test
    @DisplayName("should perform migration when dataset has no versions")
    void shouldPerformMigration_whenDatasetHasNoVersions() {
        // Given
        UUID versionId = TEST_DATASET_ID; // version 1 ID = dataset ID

        // First call returns 0 (no versions), second call after migration returns 1
        when(jdbi.inTransaction(any(), any()))
                .thenReturn(0L) // hasVersions check before lock
                .thenReturn(0L) // hasVersions check inside lock
                .thenReturn(null) // ensureVersion1Exists
                .thenReturn(null) // updateItemsTotal
                .thenReturn(null); // ensureLatestTagExists

        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        when(datasetItemVersionDAO.deleteItemsFromVersion(eq(TEST_DATASET_ID), eq(versionId)))
                .thenReturn(Mono.just(0L));

        when(datasetItemVersionDAO.copyItemsFromLegacy(eq(TEST_DATASET_ID), eq(versionId)))
                .thenReturn(Mono.just(10L));

        when(datasetItemVersionDAO.countItemsInVersion(eq(TEST_DATASET_ID), eq(versionId)))
                .thenReturn(Mono.just(10L));

        // When
        Mono<Void> result = migrationService.ensureDatasetMigrated(
                TEST_DATASET_ID, TEST_WORKSPACE_ID, TEST_USER_NAME);

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify that lock was acquired
        verify(lockService, times(1)).executeWithLockCustomExpire(any(), any(), any());

        // Verify migration steps were executed
        verify(datasetItemVersionDAO).deleteItemsFromVersion(TEST_DATASET_ID, versionId);
        verify(datasetItemVersionDAO).copyItemsFromLegacy(TEST_DATASET_ID, versionId);
        verify(datasetItemVersionDAO).countItemsInVersion(TEST_DATASET_ID, versionId);
    }

    @Test
    @DisplayName("should skip migration inside lock when another thread already migrated")
    void shouldSkipMigrationInsideLock_whenAnotherThreadAlreadyMigrated() {
        // Given
        // First call returns 0 (no versions before lock), second call returns 1 (migrated by another thread)
        when(jdbi.inTransaction(any(), any()))
                .thenReturn(0L) // hasVersions check before lock
                .thenReturn(1L); // hasVersions check inside lock - already migrated

        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        // When
        Mono<Void> result = migrationService.ensureDatasetMigrated(
                TEST_DATASET_ID, TEST_WORKSPACE_ID, TEST_USER_NAME);

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify that lock was acquired
        verify(lockService, times(1)).executeWithLockCustomExpire(any(), any(), any());

        // Verify migration steps were NOT executed since another thread already migrated
        verify(datasetItemVersionDAO, never()).deleteItemsFromVersion(any(), any());
        verify(datasetItemVersionDAO, never()).copyItemsFromLegacy(any(), any());
        verify(datasetItemVersionDAO, never()).countItemsInVersion(any(), any());
    }

    @Test
    @DisplayName("should handle migration error gracefully")
    void shouldHandleMigrationError_gracefully() {
        // Given
        UUID versionId = TEST_DATASET_ID;

        when(jdbi.inTransaction(any(), any()))
                .thenReturn(0L) // hasVersions check before lock
                .thenReturn(0L) // hasVersions check inside lock
                .thenReturn(null); // ensureVersion1Exists

        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        when(datasetItemVersionDAO.deleteItemsFromVersion(eq(TEST_DATASET_ID), eq(versionId)))
                .thenReturn(Mono.just(0L));

        // Error occurs during copyItemsFromLegacy
        when(datasetItemVersionDAO.copyItemsFromLegacy(eq(TEST_DATASET_ID), eq(versionId)))
                .thenReturn(Mono.error(new RuntimeException("Database error")));

        // When
        Mono<Void> result = migrationService.ensureDatasetMigrated(
                TEST_DATASET_ID, TEST_WORKSPACE_ID, TEST_USER_NAME);

        // Then - verify that the error is propagated
        StepVerifier.create(result)
                .expectError(RuntimeException.class)
                .verify();

        // Verify that lock was acquired
        verify(lockService, times(1)).executeWithLockCustomExpire(any(), any(), any());
    }

    @Test
    @DisplayName("should copy correct number of items during migration")
    void shouldCopyCorrectNumberOfItems_duringMigration() {
        // Given
        UUID versionId = TEST_DATASET_ID;
        long expectedItemCount = 42L;

        when(jdbi.inTransaction(any(), any()))
                .thenReturn(0L) // hasVersions check before lock
                .thenReturn(0L) // hasVersions check inside lock
                .thenReturn(null) // ensureVersion1Exists
                .thenReturn(null) // updateItemsTotal
                .thenReturn(null); // ensureLatestTagExists

        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        when(datasetItemVersionDAO.deleteItemsFromVersion(eq(TEST_DATASET_ID), eq(versionId)))
                .thenReturn(Mono.just(0L));

        when(datasetItemVersionDAO.copyItemsFromLegacy(eq(TEST_DATASET_ID), eq(versionId)))
                .thenReturn(Mono.just(expectedItemCount));

        when(datasetItemVersionDAO.countItemsInVersion(eq(TEST_DATASET_ID), eq(versionId)))
                .thenReturn(Mono.just(expectedItemCount));

        // When
        Mono<Void> result = migrationService.ensureDatasetMigrated(
                TEST_DATASET_ID, TEST_WORKSPACE_ID, TEST_USER_NAME);

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify that the correct number of items was copied
        verify(datasetItemVersionDAO).copyItemsFromLegacy(TEST_DATASET_ID, versionId);
        verify(datasetItemVersionDAO).countItemsInVersion(TEST_DATASET_ID, versionId);
    }
}
