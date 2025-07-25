package com.comet.opik.api.dashboard;

import com.fasterxml.jackson.annotation.JsonValue;

public enum WidgetType {
    LINE_CHART("line_chart"),
    BAR_CHART("bar_chart"),
    PIE_CHART("pie_chart"),
    TABLE("table"),
    KPI_CARD("kpi_card"),
    HEATMAP("heatmap"),
    AREA_CHART("area_chart"),
    DONUT_CHART("donut_chart"),
    SCATTER_PLOT("scatter_plot"),
    GAUGE_CHART("gauge_chart"),
    PROGRESS_BAR("progress_bar"),
    NUMBER_CARD("number_card"),
    FUNNEL_CHART("funnel_chart"),
    HORIZONTAL_BAR_CHART("horizontal_bar_chart");

    private final String value;

    WidgetType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static WidgetType fromValue(String value) {
        for (WidgetType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown widget type: " + value);
    }
}
