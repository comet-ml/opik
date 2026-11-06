package com.comet.opik.domain.utils;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves a dataset or prompt named by a request that carries no project, as older SDKs and raw REST calls do.
 * Names are unique per project, so the name alone identifies the entity only when one project has it.
 */
@UtilityClass
public class UnscopedNameUtils {

    /**
     * How many matches to read: enough to tell one match from several, after setting aside a project-less match
     * that the Default Project's match takes precedence over.
     */
    public static final int MATCH_LIMIT = 3;

    /**
     * Project-less entities belong to the Default Project, so a project-less match and a Default Project match with
     * the same name are the same project's, and the Default Project's one is returned, as project-scoped lookups do.
     *
     * @param defaultProjectId looked up only when that case can apply
     * @return the only match, or empty when there is none
     * @throws ClientErrorException 400 when several projects have the name, as the intended one cannot be told apart
     */
    public static <T> Optional<T> requireUnique(@NonNull List<T> matches, @NonNull Function<T, UUID> projectIdOf,
            @NonNull Supplier<Optional<UUID>> defaultProjectId, @NonNull String entityType, @NonNull String name) {
        var candidates = matches;
        if (matches.size() > 1 && matches.stream().anyMatch(match -> projectIdOf.apply(match) == null)) {
            candidates = defaultProjectId.get()
                    .filter(id -> matches.stream().anyMatch(match -> id.equals(projectIdOf.apply(match))))
                    .map(id -> matches.stream().filter(match -> projectIdOf.apply(match) != null).toList())
                    .orElse(matches);
        }
        if (candidates.size() > 1) {
            throw new ClientErrorException(
                    "%s name exists in several projects, please provide project_name: '%s'".formatted(entityType,
                            name),
                    Response.Status.BAD_REQUEST);
        }
        return candidates.stream().findFirst();
    }
}
