package com.comet.opik.utils;

import com.comet.opik.api.error.ErrorMessage;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

@UtilityClass
@Slf4j
public class ErrorUtils {

    public static Throwable failWithNotFound(String entity, UUID id) {
        String message = "%s id: %s not found".formatted(entity, id);
        log.info(message);
        return new NotFoundException(Response.status(404)
                .entity(new ErrorMessage(List.of(message))).build());
    }
}
