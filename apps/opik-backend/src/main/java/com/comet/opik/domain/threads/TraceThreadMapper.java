package com.comet.opik.domain.threads;

import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.utils.RowUtils;
import io.r2dbc.spi.Row;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Mapper
interface TraceThreadMapper {

    TraceThreadMapper INSTANCE = Mappers.getMapper(TraceThreadMapper.class);

    @Mapping(target = "lastUpdatedBy", source = "userName")
    @Mapping(target = "tags", ignore = true)
    @Mapping(target = "sampling", expression = "java(java.util.Map.of())")
    @Mapping(target = "scoredAt", ignore = true)
    @Mapping(target = "startTime", ignore = true)
    @Mapping(target = "endTime", ignore = true)
    @Mapping(target = "duration", ignore = true)
    @Mapping(target = "feedbackScores", expression = "java(java.util.Map.of())")
    @Mapping(target = "firstMessage", ignore = true)
    @Mapping(target = "lastMessage", ignore = true)
    @Mapping(target = "numberOfMessages", ignore = true)
    TraceThreadModel mapFromThreadIdModel(TraceThreadIdModel traceThread, String userName, TraceThreadStatus status,
            Instant lastUpdatedAt);

    default TraceThreadModel mapFromRow(Row row) {
        return TraceThreadModel.builder()
                .id(row.get("id", UUID.class))
                .threadId(row.get("thread_id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .status(TraceThreadStatus.fromValue(row.get("status", String.class)).orElse(TraceThreadStatus.ACTIVE))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .tags(Optional.ofNullable(row.get("tags", String[].class))
                        .map(Set::of)
                        .orElse(null))
                .sampling(Optional.ofNullable(row.get("sampling_per_rule", Map.class))
                        .map(sampling -> (Map<String, Boolean>) sampling)
                        .map(samplingMap -> samplingMap.entrySet()
                                .stream()
                                .map(entry -> Map.entry(UUID.fromString(entry.getKey()), entry.getValue()))
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
                        .orElse(Map.of()))
                .scoredAt(row.get("scored_at", Instant.class))
                .startTime(RowUtils.getOptionalValue(row, "start_time", Instant.class))
                .endTime(RowUtils.getOptionalValue(row, "end_time", Instant.class))
                .duration(RowUtils.getOptionalValue(row, "duration", Double.class))
                .feedbackScores(Optional.ofNullable(RowUtils.getOptionalValue(row, "feedback_scores", Map.class))
                        .map(scores -> (Map<String, Integer>) scores)
                        .orElse(Map.of()))
                .firstMessage(RowUtils.getOptionalValue(row, "first_message", String.class))
                .lastMessage(RowUtils.getOptionalValue(row, "last_message", String.class))
                .numberOfMessages(RowUtils.getOptionalValue(row, "number_of_messages", Long.class))
                .build();
    }

    default ProjectWithPendingClosureTraceThreads mapToProjectWithPendingClosureThreads(Row row) {
        return ProjectWithPendingClosureTraceThreads.builder()
                .projectId(row.get("project_id", UUID.class))
                .workspaceId(row.get("workspace_id", String.class))
                .build();
    }
}
