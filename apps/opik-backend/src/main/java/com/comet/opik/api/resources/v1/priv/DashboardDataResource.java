package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;

@Path("/v1/private/dashboard-data")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@Tag(name = "Dashboard Data", description = "Mock data endpoints for dashboard widgets")
public class DashboardDataResource {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random();

    @GET
    @Path("/time-series")
    @Operation(operationId = "getTimeSeriesData", summary = "Get time series data", description = "Get mock time series data for line charts")
    public Response getTimeSeriesData(
            @QueryParam("hours") Integer hours,
            @QueryParam("series") String series) {

        int dataPoints = hours != null ? hours : 24;
        Instant now = Instant.now();

        ArrayNode data = mapper.createArrayNode();

        for (int i = dataPoints - 1; i >= 0; i--) {
            ObjectNode point = mapper.createObjectNode();
            point.put("timestamp", now.minus(i, ChronoUnit.HOURS).toString());
            point.put("value", 500 + random.nextInt(500));
            point.put("series", series != null ? series : "requests");
            data.add(point);
        }

        ObjectNode response = mapper.createObjectNode();
        response.set("data", data);

        return Response.ok(response).build();
    }

    @GET
    @Path("/categorical")
    @Operation(operationId = "getCategoricalData", summary = "Get categorical data", description = "Get mock categorical data for bar/pie charts")
    public Response getCategoricalData() {
        ArrayNode data = mapper.createArrayNode();

        String[] categories = {"success", "error", "timeout", "cancelled"};
        int[] baseCounts = {850, 45, 12, 8};

        for (int i = 0; i < categories.length; i++) {
            ObjectNode point = mapper.createObjectNode();
            point.put("category", categories[i]);
            point.put("count", baseCounts[i] + random.nextInt(50));
            data.add(point);
        }

        ObjectNode response = mapper.createObjectNode();
        response.set("data", data);

        return Response.ok(response).build();
    }

    @GET
    @Path("/table")
    @Operation(operationId = "getTableData", summary = "Get table data", description = "Get mock table data")
    public Response getTableData(
            @QueryParam("limit") Integer limit,
            @QueryParam("page") Integer page) {

        int recordCount = limit != null ? limit : 10;
        int currentPage = page != null ? page : 1;

        ArrayNode data = mapper.createArrayNode();
        String[] statuses = {"success", "error", "timeout"};

        for (int i = 1; i <= recordCount; i++) {
            ObjectNode record = mapper.createObjectNode();
            record.put("id", (currentPage - 1) * recordCount + i);
            record.put("name", "Trace " + ((currentPage - 1) * recordCount + i));
            record.put("duration", 100 + random.nextInt(900));
            record.put("status", statuses[random.nextInt(statuses.length)]);
            record.put("timestamp", Instant.now().minus(random.nextInt(3600), ChronoUnit.SECONDS).toString());
            data.add(record);
        }

        ObjectNode pagination = mapper.createObjectNode();
        pagination.put("page", currentPage);
        pagination.put("totalPages", 5);
        pagination.put("totalItems", 50);

        ObjectNode response = mapper.createObjectNode();
        response.set("data", data);
        response.set("pagination", pagination);

        return Response.ok(response).build();
    }

    @GET
    @Path("/kpi")
    @Operation(operationId = "getKPIData", summary = "Get KPI data", description = "Get mock KPI data")
    public Response getKPIData(@QueryParam("metric") String metric) {
        ObjectNode kpiData = mapper.createObjectNode();

        switch (metric != null ? metric : "success_rate") {
            case "success_rate" :
                kpiData.put("value", 98.5);
                kpiData.put("label", "Success Rate");
                kpiData.put("format", "percentage");
                break;
            case "response_time" :
                kpiData.put("value", 245);
                kpiData.put("label", "Avg Response Time");
                kpiData.put("format", "duration");
                break;
            case "requests_today" :
                kpiData.put("value", 12547);
                kpiData.put("label", "Requests Today");
                kpiData.put("format", "number");
                break;
            case "error_rate" :
                kpiData.put("value", 1.5);
                kpiData.put("label", "Error Rate");
                kpiData.put("format", "percentage");
                break;
            default :
                kpiData.put("value", 100);
                kpiData.put("label", "Custom Metric");
                kpiData.put("format", "number");
        }

        ObjectNode trend = mapper.createObjectNode();
        trend.put("direction", random.nextBoolean() ? "up" : "down");
        trend.put("percentage", 1 + random.nextDouble() * 5);
        kpiData.set("trend", trend);

        ObjectNode response = mapper.createObjectNode();
        response.set("data", kpiData);

        return Response.ok(response).build();
    }

    @GET
    @Path("/heatmap")
    @Operation(operationId = "getHeatmapData", summary = "Get heatmap data", description = "Get mock heatmap data")
    public Response getHeatmapData() {
        ArrayNode data = mapper.createArrayNode();

        String[] hours = {"00", "06", "12", "18"};
        String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

        for (String hour : hours) {
            for (String day : days) {
                ObjectNode point = mapper.createObjectNode();
                point.put("x", hour);
                point.put("y", day);
                point.put("value", random.nextInt(100));
                data.add(point);
            }
        }

        ObjectNode response = mapper.createObjectNode();
        response.set("data", data);

        return Response.ok(response).build();
    }
}
