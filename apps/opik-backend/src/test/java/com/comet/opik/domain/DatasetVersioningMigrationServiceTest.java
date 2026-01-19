package com.comet.opik.domain;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;

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
    private DatasetDAO datasetDAO;

    @Mock
    private TransactionTemplate template;

    @Mock
    private RequestContext requestContext;

    private DatasetVersioningMigrationService migrationService;

    private static final String TEST_WORKSPACE_ID = "test-workspace";
    private static final String TEST_WORKSPACE_ID_2 = "test-workspace-2";

    @BeforeEach
    void setUp() {
        Provider<RequestContext> requestContextProvider = () -> requestContext;
        migrationService = new DatasetVersioningMigrationService(
                lockService,
                datasetItemVersionDAO,
                template,
                requestContextProvider);
    }

    @Test
    @DisplayName("should complete migration when no workspaces exist")
    void shouldCompleteMigration_whenNoWorkspacesExist() {
        // Given
        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        // Mock TransactionTemplate to return empty workspace list
        when(template.inTransaction(any(), any()))
                .thenAnswer(invocation -> java.util.Collections.emptyList());

        // When
        Mono<Void> result = migrationService.runMigration(100, Duration.ofSeconds(3600));

        // Then
        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    @DisplayName("should query workspace batch and process workspaces")
    void shouldQueryWorkspaceBatch_andProcessWorkspaces() {
        // Given
        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        // Mock TransactionTemplate to return workspace list, then empty list
        when(template.inTransaction(any(), any()))
                .thenReturn(java.util.List.of(TEST_WORKSPACE_ID))
                .thenReturn(java.util.Collections.emptyList());

        // Mock workspace query returns no datasets (so no DAO mocking needed)
        when(datasetItemVersionDAO.findDatasetsWithHashMismatchInWorkspace(TEST_WORKSPACE_ID))
                .thenReturn(Flux.empty());

        // When
        Mono<Void> result = migrationService.runMigration(100, Duration.ofSeconds(3600));

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(datasetItemVersionDAO).findDatasetsWithHashMismatchInWorkspace(TEST_WORKSPACE_ID);
    }

    @Test
    @DisplayName("should process multiple workspace batches with cursor pagination")
    void shouldProcessMultipleWorkspaceBatches_withCursorPagination() {
        // Given
        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        // Mock workspace pagination: first batch returns workspace1, second batch returns workspace2, third batch empty
        when(template.inTransaction(any(), any()))
                .thenReturn(java.util.List.of(TEST_WORKSPACE_ID)) // First workspace batch
                .thenReturn(java.util.List.of(TEST_WORKSPACE_ID_2)) // Second workspace batch
                .thenReturn(java.util.Collections.emptyList()); // Third batch empty

        // Mock workspace queries - both return no datasets
        when(datasetItemVersionDAO.findDatasetsWithHashMismatchInWorkspace(TEST_WORKSPACE_ID))
                .thenReturn(Flux.empty());
        when(datasetItemVersionDAO.findDatasetsWithHashMismatchInWorkspace(TEST_WORKSPACE_ID_2))
                .thenReturn(Flux.empty());

        // When
        Mono<Void> result = migrationService.runMigration(1, Duration.ofSeconds(3600));

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        // Verify both workspaces were queried
        verify(datasetItemVersionDAO).findDatasetsWithHashMismatchInWorkspace(TEST_WORKSPACE_ID);
        verify(datasetItemVersionDAO).findDatasetsWithHashMismatchInWorkspace(TEST_WORKSPACE_ID_2);
    }

    @Test
    @DisplayName("should handle workspace with no datasets needing migration")
    void shouldHandleWorkspace_withNoDatasetsNeedingMigration() {
        // Given
        when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        // Mock workspace batch returns one workspace, then empty
        when(template.inTransaction(any(), any()))
                .thenAnswer(invocation -> java.util.List.of(TEST_WORKSPACE_ID))
                .thenAnswer(invocation -> java.util.Collections.emptyList());

        // Mock workspace query returns no datasets
        when(datasetItemVersionDAO.findDatasetsWithHashMismatchInWorkspace(TEST_WORKSPACE_ID))
                .thenReturn(Flux.empty());

        // When
        Mono<Void> result = migrationService.runMigration(100, Duration.ofSeconds(3600));

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(datasetItemVersionDAO).findDatasetsWithHashMismatchInWorkspace(TEST_WORKSPACE_ID);
    }

    @Test
    @DisplayName("should use lock with correct timeout")
    void shouldUseLock_withCorrectTimeout() {
        // Given
        Duration lockTimeout = Duration.ofSeconds(7200);
        int workspaceBatchSize = 50;

        when(lockService.executeWithLockCustomExpire(any(), any(), eq(lockTimeout)))
                .thenAnswer(invocation -> invocation.getArgument(1, Mono.class));

        when(template.inTransaction(any(), any()))
                .thenReturn(java.util.Collections.emptyList());

        // When
        Mono<Void> result = migrationService.runMigration(workspaceBatchSize, lockTimeout);

        // Then
        StepVerifier.create(result)
                .verifyComplete();

        verify(lockService).executeWithLockCustomExpire(any(), any(), eq(lockTimeout));
    }
}
