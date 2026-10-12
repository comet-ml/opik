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
    private static final UUID QUERY_ID = UUID.randomUUID();

    @Test
    @DisplayName("finds the saved query an Ollie chart references by widget id")
    void findQueryId__whenOllieChart__thenReturnsIt() {
        var config = config("ollie_chart", "\"id\": \"%s\"".formatted(QUERY_ID));

        assertThat(OllieChartWidgets.findQueryId(config, WIDGET_ID)).contains(QUERY_ID);
        assertThat(OllieChartWidgets.queryIds(config)).containsExactly(QUERY_ID);
        assertThat(OllieChartWidgets.validate(config)).isEmpty();
    }

    @Test
    @DisplayName("finds nothing for another widget type, an unknown widget or a query id that is not a UUID")
    void findQueryId__whenNotARunnableOllieChart__thenEmpty() {
        var query = "\"id\": \"%s\"".formatted(QUERY_ID);

        assertThat(OllieChartWidgets.findQueryId(config("text_markdown", query), WIDGET_ID)).isEmpty();
        assertThat(OllieChartWidgets.findQueryId(config("ollie_chart", query), "other")).isEmpty();
        assertThat(OllieChartWidgets.findQueryId(config("ollie_chart", "\"id\": \"not-a-uuid\""), WIDGET_ID))
                .isEmpty();
    }

    @Test
    @DisplayName("refuses SQL in the widget, and a query without a saved query id")
    void validate__whenQueryCarriesSqlOrNoId__thenErrors() {
        assertThat(OllieChartWidgets.validate(config("ollie_chart", "\"id\": \"%s\", \"sql\": \"SELECT 1\""
                .formatted(QUERY_ID)))).singleElement().asString().contains("query.sql");
        assertThat(OllieChartWidgets.validate(config("ollie_chart", "\"sql\": \"SELECT 1\"")))
                .hasSize(2).anySatisfy(error -> assertThat(error).contains("query.id"));
        assertThat(OllieChartWidgets.validate(config("text_markdown", "\"sql\": \"SELECT 1\""))).isEmpty();
    }

    @Test
    @DisplayName("binds the date range as server-formatted literals")
    void bindWindow__thenReplacesBothPlaceholders() {
        var start = Instant.parse("2026-10-01T00:00:00Z");
        var end = Instant.parse("2026-10-08T12:30:00Z");

        assertThat(DashboardWidgetQueryServiceImpl.bindWindow(
                "WHERE t >= {{window_start}} AND t < {{window_end}}", start, end))
                .isEqualTo("WHERE t >= toDateTime64('2026-10-01 00:00:00.000000000', 9, 'UTC')"
                        + " AND t < toDateTime64('2026-10-08 12:30:00.000000000', 9, 'UTC')");
    }

    private static JsonNode config(String type, String query) {
        return JsonUtils.getJsonNodeFromString("""
                {"sections": [{"id": "s1", "widgets": [
                    {"id": "%s", "type": "%s", "config": {"query": {%s}}}
                ]}]}
                """.formatted(WIDGET_ID, type, query));
    }
}
