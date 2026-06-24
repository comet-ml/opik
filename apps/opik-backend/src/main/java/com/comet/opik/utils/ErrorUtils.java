package com.comet.opik.utils;

import com.clickhouse.client.ClickHouseException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@UtilityClass
@Slf4j
public class ErrorUtils {

    /**
     * Returns true when the throwable is ClickHouse rejecting a malformed JSON path with a
     * BAD_ARGUMENTS "Unable to parse JSONPath" error. Callers treat this as an empty result rather
     * than surfacing a 500. The surrounding wording varies across ClickHouse versions (e.g.
     * "JSONPath. (BAD_ARGUMENTS)" vs "JSONPath: In scope ... (BAD_ARGUMENTS)"), so this matches on
     * the stable substrings instead of an exact phrase.
     */
    public static boolean isMalformedJsonPath(Throwable e) {
        return e instanceof ClickHouseException && e.getMessage() != null
                && e.getMessage().contains("Unable to parse JSONPath")
                && e.getMessage().contains("BAD_ARGUMENTS");
    }

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
