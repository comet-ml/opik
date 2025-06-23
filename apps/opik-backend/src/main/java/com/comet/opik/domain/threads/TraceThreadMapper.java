package com.comet.opik.domain.threads;

import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import io.r2dbc.spi.Row;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

@Mapper
interface TraceThreadMapper {

    TraceThreadMapper INSTANCE = Mappers.getMapper(TraceThreadMapper.class);

    TraceThreadModel mapFromThreadIdModel(TraceThreadIdModel traceThread, String userName, TraceThreadStatus status,
            Instant lastUpdatedAt);

    default TraceThreadModel mapFromRow(Row row) {
        return TraceThreadModel.builder()
                .id(row.get("id", UUID.class))
                .threadId(row.get("thread_id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .status(TraceThreadStatus.fromValue(row.get("status", String.class)))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .build();
    }

    default ProjectWithPendingClosureTraceThreads mapToProjectWithPendingClosuseThreads(Row row) {
        return ProjectWithPendingClosureTraceThreads.builder()
                .projectId(row.get("project_id", UUID.class))
                .workspaceId(row.get("workspace_id", String.class))
                .build();
    }
}
