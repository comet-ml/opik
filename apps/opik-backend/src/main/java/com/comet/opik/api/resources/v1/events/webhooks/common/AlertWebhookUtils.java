package com.comet.opik.api.resources.v1.events.webhooks.common;

import com.comet.opik.api.AlertEventType;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Shared helpers for webhook alert payload mappers (Slack, Feishu, ...).
 * Centralizes formatting logic so per-destination mappers stay free of duplication.
 */
@UtilityClass
public class AlertWebhookUtils {

    public static final String BASE_URL_METADATA_KEY = "base_url";
    private static final String DEFAULT_BASE_URL = "http://localhost:5173";

    /**
     * Resolves the workspace base URL from alert metadata, falling back to the default when the
     * key is missing, null or blank, and guaranteeing a single trailing slash before the workspace.
     */
    public static String resolveBaseUrl(@NonNull Map<String, String> alertMetadata, @NonNull String workspaceName) {
        String metadataUrl = Optional.ofNullable(alertMetadata.get(BASE_URL_METADATA_KEY))
                .filter(StringUtils::isNotBlank)
                .orElse(DEFAULT_BASE_URL);
        metadataUrl = metadataUrl.endsWith("/") ? metadataUrl : metadataUrl + "/";
        return metadataUrl + workspaceName;
    }

    /**
     * Formats a window duration in seconds to a human-readable string (e.g. "5 minutes", "1 day").
     */
    public static String formatWindowDuration(long seconds) {
        Duration duration = Duration.ofSeconds(seconds);
        if (duration.toDays() > 0) {
            long days = duration.toDays();
            return days + " day" + (days != 1 ? "s" : "");
        } else if (duration.toHours() > 0) {
            long hours = duration.toHours();
            return hours + " hour" + (hours != 1 ? "s" : "");
        } else if (duration.toMinutes() > 0) {
            long minutes = duration.toMinutes();
            return minutes + " minute" + (minutes != 1 ? "s" : "");
        } else {
            return seconds + " second" + (seconds != 1 ? "s" : "");
        }
    }

    /**
     * Maps an {@link AlertEventType} to a human-readable label shared across webhook destinations.
     */
    public static String formatEventType(@NonNull AlertEventType eventType) {
        return switch (eventType) {
            case PROMPT_CREATED -> "Prompt Created";
            case PROMPT_DELETED -> "Prompt Deleted";
            case PROMPT_COMMITTED -> "Prompt Committed";
            case TRACE_ERRORS -> "Trace Error Alert";
            case TRACE_FEEDBACK_SCORE -> "Trace Feedback Score";
            case TRACE_THREAD_FEEDBACK_SCORE -> "Thread Feedback Score";
            case TRACE_GUARDRAILS_TRIGGERED -> "Guardrail Triggered";
            case EXPERIMENT_FINISHED -> "Experiment Finished";
            case TRACE_COST -> "Cost Alert";
            case TRACE_LATENCY -> "Latency Alert";
        };
    }
}
