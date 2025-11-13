package com.comet.opik.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;

/**
 * Validator for dashboard configuration JSON.
 * Ensures the config meets the schema requirements and size limits.
 */
@Slf4j
@UtilityClass
public class DashboardConfigValidator {

    private static final int MAX_CONFIG_SIZE_BYTES = 256 * 1024; // 256KB
    private static final int MAX_WIDGETS = 100;

    /**
     * Validates the dashboard configuration JSON.
     *
     * @param config the configuration to validate
     * @throws BadRequestException if validation fails
     */
    public static void validate(@NonNull JsonNode config) {
        // Check if config is an object
        if (!config.isObject()) {
            throw new BadRequestException("Config must be a JSON object");
        }

        // Check config size
        String configString = config.toString();
        int configSize = configString.getBytes().length;
        if (configSize > MAX_CONFIG_SIZE_BYTES) {
            log.warn("Dashboard config size '{}' bytes exceeds maximum '{}'", configSize, MAX_CONFIG_SIZE_BYTES);
            throw new BadRequestException(
                    "Config size exceeds maximum of " + MAX_CONFIG_SIZE_BYTES + " bytes (256KB)");
        }

        // Check for required version field
        if (!config.has("version")) {
            throw new BadRequestException("Config must have a 'version' field");
        }

        JsonNode versionNode = config.get("version");
        if (!versionNode.isInt()) {
            throw new BadRequestException("Config 'version' must be an integer");
        }

        // Currently only version 1 is supported
        int version = versionNode.asInt();
        if (version != 1) {
            throw new BadRequestException("Unsupported config version: " + version + " (only version 1 is supported)");
        }

        // Validate widgets if present
        if (config.has("widgets")) {
            validateWidgets(config.get("widgets"));
        }
    }

    private static void validateWidgets(JsonNode widgets) {
        if (!widgets.isArray()) {
            throw new BadRequestException("Config 'widgets' must be an array");
        }

        int widgetCount = widgets.size();
        if (widgetCount > MAX_WIDGETS) {
            log.warn("Dashboard widgets count '{}' exceeds maximum '{}'", widgetCount, MAX_WIDGETS);
            throw new BadRequestException("Config cannot have more than " + MAX_WIDGETS + " widgets");
        }

        Set<String> widgetIds = new HashSet<>();
        for (int i = 0; i < widgetCount; i++) {
            JsonNode widget = widgets.get(i);

            if (!widget.isObject()) {
                throw new BadRequestException("Widget at index " + i + " must be a JSON object");
            }

            // Check for widget id
            if (!widget.has("id")) {
                throw new BadRequestException("Widget at index " + i + " must have an 'id' field");
            }

            String widgetId = widget.get("id").asText();
            if (widgetId == null || widgetId.trim().isEmpty()) {
                throw new BadRequestException("Widget at index " + i + " must have a non-empty 'id'");
            }

            // Check for duplicate widget ids
            if (!widgetIds.add(widgetId)) {
                throw new BadRequestException("Duplicate widget id: " + widgetId);
            }

            // Check for required widget fields
            if (!widget.has("type")) {
                throw new BadRequestException("Widget '" + widgetId + "' must have a 'type' field");
            }

            String widgetType = widget.get("type").asText();
            if (!isValidWidgetType(widgetType)) {
                throw new BadRequestException("Widget '" + widgetId + "' has invalid type: " + widgetType);
            }

            // Validate position if present
            if (widget.has("position")) {
                validatePosition(widget.get("position"), widgetId);
            }
        }
    }

    private static boolean isValidWidgetType(String type) {
        return "chart".equals(type) || "table".equals(type) || "kpi".equals(type);
    }

    private static void validatePosition(JsonNode position, String widgetId) {
        if (!position.isObject()) {
            throw new BadRequestException("Position for widget '" + widgetId + "' must be a JSON object");
        }

        // Check for required position fields
        String[] requiredFields = {"x", "y", "w", "h"};
        for (String field : requiredFields) {
            if (!position.has(field)) {
                throw new BadRequestException(
                        "Position for widget '" + widgetId + "' must have '" + field + "' field");
            }
            if (!position.get(field).isInt()) {
                throw new BadRequestException(
                        "Position field '" + field + "' for widget '" + widgetId + "' must be an integer");
            }
        }
    }
}
