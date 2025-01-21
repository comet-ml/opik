package com.comet.opik.utils;

import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@UtilityClass
@Slf4j
public class ErrorUtils {

    public static NotFoundException failWithNotFound(@NonNull String entity, @NonNull UUID id) {
        String message = "%s id: %s not found".formatted(entity, id);
        return failWithNotFound(message);
    }

    public static NotFoundException failWithNotFound(@NonNull String message) {
        log.info(message);
        return new NotFoundException(Response.status(404)
                .entity(new ErrorMessage(message)).build());
    }
}
