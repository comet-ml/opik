package com.comet.opik.domain.threads;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

import static com.comet.opik.domain.threads.TraceThreadModel.Status;

@Mapper
interface TraceThreadMapper {

    TraceThreadMapper INSTANCE = Mappers.getMapper(TraceThreadMapper.class);

    TraceThreadModel mapFromThreadIdModel(TraceThreadIdModel traceThread, String userName, Status status,
            Instant lastUpdatedAt);

}
