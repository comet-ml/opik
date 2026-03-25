package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.DashboardScope;
import com.comet.opik.api.DashboardType;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.util.UUID;

@UtilityClass
public class DashboardTestDataFactory {

    public static Dashboard.DashboardBuilder createPartialDashboard(DashboardScope scope) {
        return Dashboard.builder()
                .name(UUID.randomUUID().toString())
                .description("Test dashboard description")
                .type(DashboardType.MULTI_PROJECT)
                .scope(scope)
                .config(createValidConfig());
    }

    public static JsonNode createValidConfig() {
        String configJson = """
                {
                    "version": 1,
                    "layout": {
                        "type": "grid",
                        "columns": 24,
                        "rowHeight": 10
                    },
                    "filters": {
                        "dateRange": {
                            "preset": "last_7_days"
                        }
                    },
                    "widgets": [
                        {
                            "id": "widget-1",
                            "type": "chart",
                            "title": "Latency p95",
                            "position": {"x": 0, "y": 0, "w": 6, "h": 8},
                            "data": {
                                "source": "traces",
                                "aggregation": {"metric": "latency_ms", "op": "p95"}
                            }
                        }
                    ]
                }
                """;
        return JsonUtils.getJsonNodeFromString(configJson);
    }

    public static JsonNode createInvalidConfigTooLarge() {
        StringBuilder largeConfig = new StringBuilder("{\"version\":1,\"data\":\"");
        for (int i = 0; i < 300000; i++) {
            largeConfig.append("x");
        }
        largeConfig.append("\"}");
        return JsonUtils.getJsonNodeFromString(largeConfig.toString());
    }

    public static JsonNode createInvalidConfigTooManyWidgets() {
        StringBuilder config = new StringBuilder("{\"version\":1,\"widgets\":[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                config.append(",");
            }
            config.append(String.format(
                    "{\"id\":\"widget-%d\",\"type\":\"chart\",\"position\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1}}",
                    i));
        }
        config.append("]}");
        return JsonUtils.getJsonNodeFromString(config.toString());
    }
}
