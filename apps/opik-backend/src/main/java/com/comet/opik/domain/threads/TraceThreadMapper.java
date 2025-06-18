package com.comet.opik.domain.threads;

import io.r2dbc.spi.Row;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static com.comet.opik.domain.threads.TraceThreadModel.Status;

@Mapper
interface TraceThreadMapper {

    TraceThreadMapper INSTANCE = Mappers.getMapper(TraceThreadMapper.class);

    TraceThreadModel mapFromThreadIdModel(TraceThreadIdModel traceThread, String userName, Status status,
            Instant lastUpdatedAt);

    default TraceThreadModel mapFromRow(Row row) {
        return TraceThreadModel.builder()
                .id(row.get("id", UUID.class))
                .threadId(row.get("thread_id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .status(TraceThreadModel.Status.fromValue(row.get("status", String.class)))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .build();
    }
}
