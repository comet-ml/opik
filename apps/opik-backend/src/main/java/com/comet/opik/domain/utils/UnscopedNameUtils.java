package com.comet.opik.domain.utils;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a dataset or prompt named by a request that carries no project, as older SDKs and raw REST calls do.
 * Names are unique per project, so the name alone identifies the entity only when one project has it.
 */
@UtilityClass
public class UnscopedNameUtils {

    /**
     * How many matches to read: two are enough to tell one match from several.
     */
    public static final int MATCH_LIMIT = 2;

    /**
     * @return the only match, or empty when there is none
     * @throws ClientErrorException 400 when several projects have the name, as the intended one cannot be told apart
     */
    public static <T> Optional<T> requireUnique(@NonNull List<T> matches, @NonNull String entityType,
            @NonNull String name) {
        if (matches.size() > 1) {
            throw new ClientErrorException(
                    "%s name exists in several projects, please provide project_name: '%s'".formatted(entityType,
                            name),
                    Response.Status.BAD_REQUEST);
        }
        return matches.stream().findFirst();
    }
}
