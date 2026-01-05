package com.comet.opik.infrastructure.queues;

import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Mapper(componentModel = "jakarta")
interface RqJobMapper {

    RqJobMapper INSTANCE = Mappers.getMapper(RqJobMapper.class);

    /**
     * ISO-8601 formatter with microsecond precision (6 digits).
     * Python RQ's strptime uses %f which only supports up to 6 decimal places.
     * Java's Instant.toString() produces nanoseconds (9 digits) which breaks RQ.
     */
    DateTimeFormatter RQ_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    @Mapping(target = "createdAt", source = "message.createdAt", qualifiedByName = "instantToRqTimestamp")
    @Mapping(target = "enqueuedAt", source = "message.enqueuedAt", qualifiedByName = "instantToRqTimestamp")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "timeout", source = "message.timeoutInSec")
    RqJobHash toHash(@NonNull QueueMessage message, @NonNull String description, @NonNull String data);

    /**
     * Convert Instant to RQ-compatible timestamp string with microsecond precision.
     * RQ expects format: yyyy-MM-ddTHH:mm:ss.ffffffZ (6 decimal places max)
     */
    @Named("instantToRqTimestamp")
    static String instantToRqTimestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        // Truncate to microseconds (6 digits) for Python RQ compatibility
        Instant truncated = instant.truncatedTo(ChronoUnit.MICROS);
        return RQ_TIMESTAMP_FORMAT.format(truncated);
    }
}
