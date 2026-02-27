package com.comet.opik.domain;

import com.comet.opik.infrastructure.DatasetVersioningMigrationConfig;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasetVersioningStartupMigrationTest {

    @Mock
    private DatasetVersioningMigrationService migrationService;

    @Mock
    private TransactionTemplate template;

    @Mock
    private Handle handle;

    @Mock
    private DatasetVersionDAO datasetVersionDAO;

    private DatasetVersioningMigrationConfig config;
    private DatasetVersioningStartupMigration migration;

    @BeforeEach
    void setUp() {
        config = new DatasetVersioningMigrationConfig();
        config.setItemsTotalDatasetsBatchSize(100);
        config.setItemsTotalJobTimeoutSeconds(3600);

        when(template.inTransaction(any(), any())).thenAnswer(invocation -> {
            TxAction<?> callback = invocation.getArgument(1);
            return callback.execute(handle);
        });
        when(handle.attach(DatasetVersionDAO.class)).thenReturn(datasetVersionDAO);

        migration = new DatasetVersioningStartupMigration(migrationService, template, config);
    }

    @Test
    void runOrVerify_shouldSkipWhenAlreadyMigrated() {
        when(datasetVersionDAO.countVersionsNeedingItemsTotalMigration()).thenReturn(0L);

        assertThatCode(() -> migration.runOrVerify()).doesNotThrowAnyException();

        verify(migrationService, never()).runItemsTotalMigration(anyInt());
    }

    @Test
    void runOrVerify_shouldRunMigrationSuccessfully() {
        when(datasetVersionDAO.countVersionsNeedingItemsTotalMigration())
                .thenReturn(5L)
                .thenReturn(0L);
        when(migrationService.runItemsTotalMigration(100)).thenReturn(Mono.empty());

        assertThatCode(() -> migration.runOrVerify()).doesNotThrowAnyException();

        verify(migrationService).runItemsTotalMigration(100);
    }

    @Test
    void runOrVerify_shouldThrowWhenMigrationFails() {
        when(datasetVersionDAO.countVersionsNeedingItemsTotalMigration()).thenReturn(5L);
        when(migrationService.runItemsTotalMigration(100))
                .thenReturn(Mono.error(new RuntimeException("DB error")));

        assertThatThrownBy(() -> migration.runOrVerify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data migration failed")
                .hasMessageContaining("DATASET_VERSIONING_STARTUP_MIGRATION_ENABLED=false")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void runOrVerify_shouldThrowWhenVersionsRemainAfterMigration() {
        when(datasetVersionDAO.countVersionsNeedingItemsTotalMigration())
                .thenReturn(5L)
                .thenReturn(3L);
        when(migrationService.runItemsTotalMigration(100)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> migration.runOrVerify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 dataset versions still need migration");
    }

    @Test
    void runOrVerify_shouldUseBatchSizeFromConfig() {
        config.setItemsTotalDatasetsBatchSize(50);

        when(datasetVersionDAO.countVersionsNeedingItemsTotalMigration())
                .thenReturn(5L)
                .thenReturn(0L);
        when(migrationService.runItemsTotalMigration(50)).thenReturn(Mono.empty());

        assertThatCode(() -> migration.runOrVerify()).doesNotThrowAnyException();

        verify(migrationService).runItemsTotalMigration(50);
    }
}
