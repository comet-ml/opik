package com.comet.opik.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reads the Ollie chart widgets out of a stored dashboard config, which is otherwise opaque to the backend: widgets sit
 * in {@code sections[].widgets[]}, and an {@code ollie_chart} keeps its pinned query at {@code config.query.sql} with
 * an optional {@code config.query.projectId}.
 */
@UtilityClass
class OllieChartWidgets {

    static final String TYPE = "ollie_chart";

    // Well above any generated query, and below ClickHouse's 256 KiB max_query_size.
    static final int MAX_QUERY_LENGTH = 65_536;

    record SavedQuery(String sql, @Nullable UUID projectId) {
    }

    /** The query saved on the Ollie chart widget {@code widgetId}, if there is one. */
    static Optional<SavedQuery> findQuery(JsonNode config, String widgetId) {
        return widgets(config)
                .filter(widget -> widgetId.equals(widget.path("id").asText(null)))
                .findFirst()
                .filter(widget -> TYPE.equals(widget.path("type").asText()))
                .map(widget -> widget.path("config").path("query"))
                .filter(query -> query.path("sql").isTextual() && !query.path("sql").asText().isBlank())
                // A project id that is present but unreadable must not widen the query to the whole workspace.
                .filter(query -> !hasProjectId(query) || projectIdOrInvalid(query).isPresent())
                .map(query -> new SavedQuery(query.path("sql").asText(), projectId(query)));
    }

    /**
     * What makes the saved queries in {@code config} unrunnable, so a bad one is refused when saved rather than when
     * someone opens the dashboard. Empty when they are all valid.
     */
    static List<String> validate(JsonNode config) {
        var errors = new ArrayList<String>();
        widgets(config)
                .filter(widget -> TYPE.equals(widget.path("type").asText()))
                .forEach(widget -> {
                    var query = widget.path("config").path("query");
                    if (query.isMissingNode() || query.isNull()) {
                        return;
                    }
                    String widgetId = widget.path("id").asText();
                    var sql = query.path("sql");
                    if (!sql.isTextual() || sql.asText().isBlank()) {
                        errors.add("Ollie chart '%s': query.sql must be a non-blank string".formatted(widgetId));
                    } else if (sql.asText().length() > MAX_QUERY_LENGTH) {
                        errors.add("Ollie chart '%s': query.sql exceeds %d characters"
                                .formatted(widgetId, MAX_QUERY_LENGTH));
                    }
                    if (hasProjectId(query) && projectIdOrInvalid(query).isEmpty()) {
                        errors.add("Ollie chart '%s': query.projectId must be a UUID".formatted(widgetId));
                    }
                });
        return errors;
    }

    private static Stream<JsonNode> widgets(JsonNode config) {
        return stream(config.path("sections")).flatMap(section -> stream(section.path("widgets")));
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return array.isArray() ? StreamSupport.stream(array.spliterator(), false) : Stream.empty();
    }

    private static boolean hasProjectId(JsonNode query) {
        var projectId = query.path("projectId");
        return !projectId.isMissingNode() && !projectId.isNull();
    }

    @Nullable private static UUID projectId(JsonNode query) {
        return hasProjectId(query) ? projectIdOrInvalid(query).orElse(null) : null;
    }

    private static Optional<UUID> projectIdOrInvalid(JsonNode query) {
        try {
            return Optional.of(UUID.fromString(query.path("projectId").asText()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
