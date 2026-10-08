package com.comet.opik.domain;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OllieChartWidgetsTest {

    private static final String WIDGET_ID = UUID.randomUUID().toString();
    private static final UUID PROJECT_ID = UUID.randomUUID();

    @Test
    @DisplayName("finds the saved query and project of an Ollie chart by widget id")
    void findQuery__whenOllieChart__thenReturnsSavedQuery() {
        var config = config("ollie_chart", "\"sql\": \"SELECT 1\", \"projectId\": \"%s\"".formatted(PROJECT_ID));

        assertThat(OllieChartWidgets.findQuery(config, WIDGET_ID))
                .contains(new OllieChartWidgets.SavedQuery("SELECT 1", PROJECT_ID));
    }

    @Test
    @DisplayName("finds nothing for another widget type, an unknown id or a missing query")
    void findQuery__whenNotARunnableOllieChart__thenEmpty() {
        assertThat(OllieChartWidgets.findQuery(config("text_markdown", "\"sql\": \"SELECT 1\""), WIDGET_ID)).isEmpty();
        assertThat(OllieChartWidgets.findQuery(config("ollie_chart", "\"sql\": \"SELECT 1\""), "other")).isEmpty();
        assertThat(OllieChartWidgets.findQuery(config("ollie_chart", "\"sql\": \" \""), WIDGET_ID)).isEmpty();
    }

    @Test
    @DisplayName("a project id that is present but not a UUID fails closed instead of widening to the workspace")
    void findQuery__whenProjectIdInvalid__thenEmpty() {
        var config = config("ollie_chart", "\"sql\": \"SELECT 1\", \"projectId\": \"not-a-uuid\"");

        assertThat(OllieChartWidgets.findQuery(config, WIDGET_ID)).isEmpty();
        assertThat(OllieChartWidgets.validate(config)).singleElement().asString().contains("projectId");
    }

    @Test
    @DisplayName("rejects a saved query longer than the limit")
    void validate__whenQueryTooLong__thenError() {
        var sql = "x".repeat(OllieChartWidgets.MAX_QUERY_LENGTH + 1);

        assertThat(OllieChartWidgets.validate(config("ollie_chart", "\"sql\": \"%s\"".formatted(sql))))
                .singleElement().asString().contains("exceeds");
        assertThat(OllieChartWidgets.validate(config("ollie_chart", "\"sql\": \"SELECT 1\""))).isEmpty();
    }

    @Test
    @DisplayName("binds the date range as server-formatted literals")
    void bindWindow__thenReplacesBothPlaceholders() {
        var start = Instant.parse("2026-10-01T00:00:00Z");
        var end = Instant.parse("2026-10-08T12:30:00Z");

        assertThat(DashboardWidgetQueryServiceImpl.bindWindow(
                "WHERE t >= {{window_start}} AND t < {{window_end}}", start, end))
                .isEqualTo("WHERE t >= parseDateTime64BestEffort('2026-10-01T00:00:00Z', 9)"
                        + " AND t < parseDateTime64BestEffort('2026-10-08T12:30:00Z', 9)");
    }

    private static JsonNode config(String type, String query) {
        return JsonUtils.getJsonNodeFromString("""
                {"sections": [{"id": "s1", "widgets": [
                    {"id": "%s", "type": "%s", "config": {"query": {%s}}}
                ]}]}
                """.formatted(WIDGET_ID, type, query));
    }
}
