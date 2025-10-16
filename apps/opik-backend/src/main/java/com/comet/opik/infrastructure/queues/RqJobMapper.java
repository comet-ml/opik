package com.comet.opik.infrastructure.queues;

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
    RqJobHash toHash(QueueMessage message, String description, String data);

    @Named("instantToString")
    static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
