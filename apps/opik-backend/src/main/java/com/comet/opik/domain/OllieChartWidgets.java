package com.comet.opik.domain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reads the Ollie chart widgets out of a stored dashboard config, which is otherwise opaque to the backend: widgets sit
 * in {@code sections[].widgets[]}, and an {@code ollie_chart} references the query it runs at {@code config.query.id},
 * a {@link com.comet.opik.api.SavedQuery} that only Ollie can create. The config never carries the SQL itself.
 */
@UtilityClass
class OllieChartWidgets {

    static final String TYPE = "ollie_chart";

    /** The saved query the Ollie chart widget {@code widgetId} runs, if there is one. */
    static Optional<UUID> findQueryId(JsonNode config, String widgetId) {
        return ollieCharts(config)
                .filter(widget -> widgetId.equals(widget.path("id").asText(null)))
                .findFirst()
                .flatMap(widget -> queryId(widget.path("config").path("query")));
    }

    /** Every saved query the Ollie charts in {@code config} reference. */
    static Set<UUID> queryIds(JsonNode config) {
        return ollieCharts(config)
                .flatMap(widget -> queryId(widget.path("config").path("query")).stream())
                .collect(Collectors.toSet());
    }

    /**
     * What makes the Ollie charts in {@code config} unsaveable. A chart without a query is valid: it shows the rows
     * captured in chat. Empty when they are all valid.
     */
    static List<String> validate(JsonNode config) {
        var errors = new ArrayList<String>();
        ollieCharts(config).forEach(widget -> {
            var query = widget.path("config").path("query");
            if (query.isMissingNode() || query.isNull()) {
                return;
            }
            String widgetId = widget.path("id").asText();
            if (query.has("sql")) {
                errors.add("Ollie chart '%s': query.sql is not accepted; reference a saved query by query.id"
                        .formatted(widgetId));
            }
            if (queryId(query).isEmpty()) {
                errors.add("Ollie chart '%s': query.id must be a saved query id".formatted(widgetId));
            }
        });
        return errors;
    }

    private static Stream<JsonNode> ollieCharts(JsonNode config) {
        return stream(config.path("sections"))
                .flatMap(section -> stream(section.path("widgets")))
                .filter(widget -> TYPE.equals(widget.path("type").asText()));
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return array.isArray() ? StreamSupport.stream(array.spliterator(), false) : Stream.empty();
    }

    private static Optional<UUID> queryId(JsonNode query) {
        var id = query.path("id");
        if (!id.isTextual()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(id.asText()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
