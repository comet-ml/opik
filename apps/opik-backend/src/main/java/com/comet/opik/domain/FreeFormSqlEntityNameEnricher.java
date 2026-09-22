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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.api.filter.Field.DATASET_ID_QUERY_PARAM;
import static com.comet.opik.api.filter.Field.PROJECT_ID_QUERY_PARAM;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * Resolves the opaque id columns of a ClickHouse result to human-readable names, in place.
 *
 * <p>ClickHouse holds {@code dataset_id} and {@code project_id} but not the names — {@code datasets} and
 * {@code projects} live in the MySQL state DB. The names are resolved afterwards through the existing DAO lookups.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class FreeFormSqlEntityNameEnricher {

    private static final Map<String, String> ID_TO_NAME_COLUMNS = Map.of(
            DATASET_ID_QUERY_PARAM, "dataset_name",
            PROJECT_ID_QUERY_PARAM, "project_name");

    private final @NonNull TransactionTemplate template;
    private final @NonNull @Config("freeFormSql") FreeFormSqlConfig freeFormSqlConfig;

    public List<JsonNode> enrich(@NonNull List<JsonNode> rows, @NonNull String workspaceId) {
        Map<String, Map<UUID, String>> labelsByColumn = new HashMap<>();
        ID_TO_NAME_COLUMNS.keySet().forEach(idColumnType -> {
            Map<UUID, String> labels = rawLabels(rows, idColumnType);
            if (!labels.isEmpty()) {
                labelsByColumn.put(idColumnType, labels);
            }
        });
        if (labelsByColumn.isEmpty()) {
            return rows;
        }

        resolveNames(labelsByColumn, workspaceId);
        rows.forEach(row -> labelsByColumn.forEach((idColumn, labels) -> addName(row, idColumn, labels)));
        return rows;
    }

    private void resolveNames(Map<String, Map<UUID, String>> labelsByColumn, String workspaceId) {
        template.inTransaction(READ_ONLY, connection -> {
            labelsByColumn.forEach(
                    (idColumnType, namesByIds) -> namesByIds
                            .putAll(names(connection, idColumnType, namesByIds.keySet(), workspaceId)));
            return null;
        });
    }

    private Map<UUID, String> names(Handle connection, String idColumnType, Set<UUID> ids, String workspaceId) {
        int maxIds = freeFormSqlConfig.getMaxNameLookupIds();
        if (ids.size() > maxIds) {
            log.info("Skipping '{}' name lookup: {} distinct ids exceeds the {} cap", idColumnType, ids.size(), maxIds);
            return Map.of();
        }
        return switch (idColumnType) {
            case "dataset_id" ->
                index(connection.attach(DatasetDAO.class).findByIds(ids, workspaceId), Dataset::id, Dataset::name);
            case "project_id" ->
                index(connection.attach(ProjectDAO.class).findByIds(ids, workspaceId), Project::id, Project::name);
            default -> {
                log.error("No name lookup defined for id column '{}'; rows keep their raw ids", idColumnType);
                yield Map.of();
            }
        };
    }

    private <T> Map<UUID, String> index(List<T> entities, Function<T, UUID> id, Function<T, String> name) {
        Map<UUID, String> nameById = new HashMap<>();
        entities.forEach(entity -> nameById.put(id.apply(entity), name.apply(entity)));
        return nameById;
    }

    private static Map<UUID, String> rawLabels(List<JsonNode> rows, String idColumnType) {
        Map<UUID, String> labels = new LinkedHashMap<>();
        rows.forEach(row -> parseId(row, idColumnType)
                .ifPresent(id -> labels.putIfAbsent(id, row.get(idColumnType).asText())));
        return labels;
    }

    private static void addName(JsonNode row, String idColumn, Map<UUID, String> labels) {
        String nameColumn = ID_TO_NAME_COLUMNS.get(idColumn);
        if (!(row instanceof ObjectNode object) || row.has(nameColumn)) {
            return;
        }
        parseId(row, idColumn).ifPresent(id -> object.put(nameColumn, labels.get(id)));
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
