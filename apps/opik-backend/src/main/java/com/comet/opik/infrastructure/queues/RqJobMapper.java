package com.comet.opik.infrastructure.queues;

import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

@Mapper(componentModel = "jakarta")
interface RqJobMapper {

    RqJobMapper INSTANCE = Mappers.getMapper(RqJobMapper.class);

    @Mapping(target = "createdAt", source = "message.createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "enqueuedAt", source = "message.enqueuedAt", qualifiedByName = "instantToString")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "timeout", source = "message.timeoutInSec")
    RqJobHash toHash(@NonNull QueueMessage message, @NonNull String description, @NonNull String data);

    @Named("instantToString")
    static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
