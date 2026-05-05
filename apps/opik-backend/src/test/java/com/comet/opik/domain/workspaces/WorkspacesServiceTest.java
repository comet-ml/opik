package com.comet.opik.domain.workspaces;

import com.comet.opik.api.OpikVersion;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspacesServiceTest {

    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private Handle handle;
    @Mock
    private WorkspacesDAO dao;

    @InjectMocks
    private WorkspacesServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(handle.attach(WorkspacesDAO.class)).thenReturn(dao);
        lenient().when(transactionTemplate.inTransaction(any(), any())).thenAnswer(invocation -> {
            TxAction<?> callback = invocation.getArgument(1);
            return callback.execute(handle);
        });
    }

    @Test
    @DisplayName("upsertVersion delegates the enum's wire value to the DAO")
    void upsertVersionDelegatesWireValue() {
        var workspaceId = "workspace-1";
        var determinedAt = Instant.parse("2026-05-05T10:00:00Z");

        service.upsertVersion(workspaceId, OpikVersion.VERSION_2, determinedAt);

        verify(dao).upsertVersion(workspaceId, "version_2", determinedAt);
    }

    @Test
    @DisplayName("findLastKnownVersion maps recognised stored values to the enum")
    void findLastKnownVersionMapsKnownValues() {
        when(dao.findLastKnownVersion("workspace-1")).thenReturn(Optional.of("version_1"));

        assertThat(service.findLastKnownVersion("workspace-1")).contains(OpikVersion.VERSION_1);
    }

    @Test
    @DisplayName("findLastKnownVersion treats unknown stored values as empty")
    void findLastKnownVersionTreatsUnknownAsEmpty() {
        when(dao.findLastKnownVersion("workspace-1")).thenReturn(Optional.of("version_99"));

        assertThat(service.findLastKnownVersion("workspace-1")).isEmpty();
    }

    @Test
    @DisplayName("findLastKnownVersion returns empty when no row exists")
    void findLastKnownVersionEmptyWhenAbsent() {
        when(dao.findLastKnownVersion("workspace-1")).thenReturn(Optional.empty());

        assertThat(service.findLastKnownVersion("workspace-1")).isEmpty();
    }

    @Test
    @DisplayName("markFirstTraceReported truncates the bound timestamp to microseconds")
    void markFirstTraceReportedTruncatesToMicros() {
        var workspaceId = "workspace-1";
        var nano = Instant.parse("2026-05-05T10:00:00.123456789Z");
        var truncated = nano.truncatedTo(ChronoUnit.MICROS);
        when(dao.findFirstTraceReportedAt(workspaceId)).thenReturn(Optional.of(truncated));

        service.markFirstTraceReported(workspaceId, nano);

        var captor = ArgumentCaptor.forClass(Instant.class);
        verify(dao).upsertFirstTraceReported(eq(workspaceId), captor.capture());
        assertThat(captor.getValue()).isEqualTo(truncated);
    }

    @Test
    @DisplayName("markFirstTraceReported returns true when read-back matches what we wrote")
    void markFirstTraceReportedTrueWhenReadBackMatches() {
        var workspaceId = "workspace-1";
        var reportedAt = Instant.parse("2026-05-05T10:00:00.123456Z");
        when(dao.findFirstTraceReportedAt(workspaceId)).thenReturn(Optional.of(reportedAt));

        assertThat(service.markFirstTraceReported(workspaceId, reportedAt)).isTrue();
    }

    @Test
    @DisplayName("markFirstTraceReported returns false when an earlier writer's value is read back")
    void markFirstTraceReportedFalseWhenAnotherWriterWon() {
        var workspaceId = "workspace-1";
        var ours = Instant.parse("2026-05-05T10:00:00.123456Z");
        var theirs = Instant.parse("2026-05-05T09:59:59.000000Z");
        when(dao.findFirstTraceReportedAt(workspaceId)).thenReturn(Optional.of(theirs));

        assertThat(service.markFirstTraceReported(workspaceId, ours)).isFalse();
    }

    @Test
    @DisplayName("markFirstTraceReported returns false when read-back is empty")
    void markFirstTraceReportedFalseWhenReadBackEmpty() {
        var workspaceId = "workspace-1";
        when(dao.findFirstTraceReportedAt(workspaceId)).thenReturn(Optional.empty());

        assertThat(service.markFirstTraceReported(workspaceId, Instant.now())).isFalse();
    }

    @Test
    @DisplayName("markMigrationSkipped delegates to upsertMigrationSkipped on the DAO")
    void markMigrationSkippedDelegates() {
        var workspaceId = "workspace-1";
        var skippedAt = Instant.parse("2026-05-05T10:00:00Z");

        service.markMigrationSkipped(workspaceId, skippedAt, "deleted_project");

        verify(dao).upsertMigrationSkipped(workspaceId, skippedAt, "deleted_project");
    }

    @Test
    @DisplayName("findMigrationSkippedWorkspaceIds delegates to the DAO")
    void findMigrationSkippedDelegates() {
        when(dao.findMigrationSkippedWorkspaceIds()).thenReturn(List.of("a", "b"));

        assertThat(service.findMigrationSkippedWorkspaceIds()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("countMigrationSkipped delegates to the DAO")
    void countMigrationSkippedDelegates() {
        when(dao.countMigrationSkipped()).thenReturn(7L);

        assertThat(service.countMigrationSkipped()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Read-only methods do not write through the DAO")
    void readOnlyMethodsHaveNoWriteSideEffects() {
        when(dao.countMigrationSkipped()).thenReturn(0L);
        when(dao.findMigrationSkippedWorkspaceIds()).thenReturn(List.of());
        when(dao.findLastKnownVersion(any())).thenReturn(Optional.empty());

        service.countMigrationSkipped();
        service.findMigrationSkippedWorkspaceIds();
        service.findLastKnownVersion("workspace-1");

        verify(dao, org.mockito.Mockito.never()).upsertVersion(any(), any(), any());
        verify(dao, org.mockito.Mockito.never()).upsertFirstTraceReported(any(), any());
        verify(dao, org.mockito.Mockito.never()).upsertMigrationSkipped(any(), any(), any());
    }

    @Test
    @DisplayName("Service forwards an empty interaction set when no work is done")
    void noUnexpectedInteractions() {
        verifyNoInteractions(dao);
    }
}
