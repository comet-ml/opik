package com.comet.opik.infrastructure.queues;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.Instant;

@Mapper(componentModel = "jakarta")
interface RqJobMapper {

    @Mapping(target = "id", source = "message.id")
    @Mapping(target = "createdAt", source = "message.createdAt", qualifiedByName = "instantToString")
    @Mapping(target = "enqueuedAt", source = "message.enqueuedAt", qualifiedByName = "instantToString")
    @Mapping(target = "status", source = "message.status")
    @Mapping(target = "origin", source = "message.origin")
    @Mapping(target = "timeoutInSec", source = "message.timeoutInSec")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "data", source = "data")
    RqJobHash toHash(QueueMessage message, String description, String data);

    @Named("instantToString")
    static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
