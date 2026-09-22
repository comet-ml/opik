package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.Project;
import com.comet.opik.infrastructure.FreeFormSqlConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * Resolves the opaque id columns of a ClickHouse result to human-readable names, in place.
 *
 * <p>ClickHouse holds {@code dataset_id} and {@code project_id} but not the names — {@code datasets} and
 * {@code projects} live in the MySQL state DB. Rather than reach them with generated SQL (MySQL has no row-level
 * security, so tenancy would have to be reconstructed in a query rewriter), they are resolved afterwards through
 * the existing workspace-bound DAO lookups. See the OPIK-8329 design, 8.12.
 *
 * <p>The workspace always comes from the authenticated request. An id belonging to another workspace resolves to
 * nothing, which is the property the whole approach rests on.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class EntityNameEnricher {

    /** Id column -> the sibling it gets. A column the query already selected under the sibling name is left alone. */
    private static final Map<String, String> NAME_COLUMNS = Map.of(
            "dataset_id", "dataset_name",
            "project_id", "project_name");

    private final @NonNull TransactionTemplate template;
    private final @NonNull @Config("freeFormSql") FreeFormSqlConfig freeFormSqlConfig;

    /**
     * Adds the name sibling for every id column present, whether or not the lookup resolved it — an HTML template
     * can then rely on the column existing. An id that resolved to nothing keeps its raw value as the label, which
     * is honest where an empty cell would read as "no name".
     */
    public List<JsonNode> enrich(@NonNull List<JsonNode> rows, @NonNull String workspaceId) {
        Map<String, Set<UUID>> idsByColumn = new HashMap<>();
        NAME_COLUMNS.keySet().forEach(idColumn -> {
            Set<UUID> ids = collectIds(rows, idColumn);
            if (!ids.isEmpty()) {
                idsByColumn.put(idColumn, ids);
            }
        });
        if (idsByColumn.isEmpty()) {
            return rows;
        }

        Map<String, Map<UUID, String>> namesByColumn = lookUpNames(idsByColumn, workspaceId);
        rows.forEach(row -> namesByColumn.forEach((idColumn, names) -> addName(row, idColumn, names)));
        return rows;
    }

    /** One transaction for both lookups: this runs on the render path, so it should cost one round trip, not two. */
    private Map<String, Map<UUID, String>> lookUpNames(Map<String, Set<UUID>> idsByColumn, String workspaceId) {
        return template.inTransaction(READ_ONLY, handle -> {
            Map<String, Map<UUID, String>> namesByColumn = new HashMap<>();
            idsByColumn.forEach((idColumn, ids) -> namesByColumn.put(idColumn, names(handle, idColumn, ids,
                    workspaceId)));
            return namesByColumn;
        });
    }

    private Map<UUID, String> names(Handle handle, String idColumn, Set<UUID> ids, String workspaceId) {
        int maxIds = freeFormSqlConfig.getMaxNameLookupIds();
        if (ids.size() > maxIds) {
            log.info("Skipping '{}' name lookup: {} distinct ids exceeds the {} cap", idColumn, ids.size(), maxIds);
            return Map.of();
        }
        // Exhaustive on purpose: an unrecognised column resolves nothing rather than falling through to whichever
        // lookup happens to be last. Reaching the default means NAME_COLUMNS gained an entry this switch did not.
        return switch (idColumn) {
            case "dataset_id" ->
                index(handle.attach(DatasetDAO.class).findByIds(ids, workspaceId), Dataset::id, Dataset::name);
            case "project_id" ->
                index(handle.attach(ProjectDAO.class).findByIds(ids, workspaceId), Project::id, Project::name);
            default -> {
                log.error("No name lookup defined for id column '{}'; rows keep their raw ids", idColumn);
                yield Map.of();
            }
        };
    }

    private static <T> Map<UUID, String> index(List<T> entities, Function<T, UUID> id, Function<T, String> name) {
        Map<UUID, String> byId = new HashMap<>();
        entities.forEach(entity -> byId.put(id.apply(entity), name.apply(entity)));
        return byId;
    }

    /** A null, blank or unparseable id is skipped: a malformed value is a bug in one chart, not a reason to fail it. */
    private static Set<UUID> collectIds(List<JsonNode> rows, String idColumn) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            parseId(row, idColumn).ifPresent(ids::add);
        }
        return ids;
    }

    private static void addName(JsonNode row, String idColumn, Map<UUID, String> names) {
        String nameColumn = NAME_COLUMNS.get(idColumn);
        if (!(row instanceof ObjectNode object) || row.has(nameColumn)) {
            return;
        }
        parseId(row, idColumn).ifPresent(id -> object.put(nameColumn,
                names.getOrDefault(id, row.get(idColumn).asText())));
    }

    private static Optional<UUID> parseId(JsonNode row, String idColumn) {
        JsonNode value = row.get(idColumn);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.asText()));
        } catch (IllegalArgumentException e) {
            log.debug("Ignoring unparseable '{}' value during name enrichment", idColumn);
            return Optional.empty();
        }
    }
}
