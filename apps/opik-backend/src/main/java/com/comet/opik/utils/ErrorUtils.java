package com.comet.opik.utils;

import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@UtilityClass
@Slf4j
public class ErrorUtils {

    public static NotFoundException failWithNotFound(@NonNull String entity, @NonNull String id) {
        String message = "%s id: %s not found".formatted(entity, id);
        return failWithNotFound(message);
    }

    public static NotFoundException failWithNotFoundName(@NonNull String entity, @NonNull String name) {
        String message = "%s name: %s not found".formatted(entity, name);
        return failWithNotFound(message);
    }

    public static NotFoundException failWithNotFound(@NonNull String entity, @NonNull UUID id) {
        return failWithNotFound(entity, id.toString());
    }

    public static NotFoundException failWithNotFound(@NonNull String message) {
        log.info(message);
        return new NotFoundException(message);
    }
}
