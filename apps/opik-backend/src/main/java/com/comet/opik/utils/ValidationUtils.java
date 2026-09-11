package com.comet.opik.utils;

import com.comet.opik.api.Source;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

public class ValidationUtils {

    /**
     * Regular expression to validate if a string is null or not blank.
     *
     * <p>It matches any string that is not null and contains at least one non-whitespace character.</p>
     * For example:
     * <ul>
     *     <li>"" -> false</li>
     *     <li>" " -> false</li>
     *     <li>"\n" -> false</li>
     *     <li>null -> true</li>
     *     <li>"a" -> true</li>
     *     <li>" a " -> true</li>
     *     <li>"\n a \n" -> true</li>
     * </ul>
     *
     * @see <a href="https://regexper.com/">Visual Explainer</a>
     * @see <a href="https://zzzcode.ai/regex/explain">Ai Explainer</a>
     */
    public static final String NULL_OR_NOT_BLANK = "(?s)^\\s*(\\S.*\\S|\\S)\\s*$";
    public static final String COMMIT_PATTERN = "^[a-zA-Z0-9]{8}$";

    /**
     * Canonical String representation to ensure precision over float or double.
     */
    public static final String MIN_FEEDBACK_SCORE_VALUE = "-999999999.999999999";
    public static final String MAX_FEEDBACK_SCORE_VALUE = "999999999.999999999";
    public static final int SCALE = 9;

    /**
     * We're using FixedString(36) to store UUIDs in Clickhouse. This isn't a nullable field, but it can be null under
     * certain circumstances, mostly during LEFT JOIN statements when there are no matching records from the table on
     * the right.
     * In those cases, ClickHouse returns the null character ('\u0000') as many times as the characters in the field
     * length (36).
     */
    public static final String CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE = StringUtils.repeat('\u0000', 36);

    public static void validateProjectNameAndProjectId(String projectName, UUID projectId) {
        if (StringUtils.isBlank(projectName) && projectId == null) {
            throw new BadRequestException("Either 'project_name' or 'project_id' query params must be provided");
        }
    }

    public static Source parseLogsSource(String source) {
        if (StringUtils.isBlank(source)) {
            return null;
        }
        return Source.fromString(source)
                .orElseThrow(() -> new BadRequestException("Invalid source '%s'".formatted(source)));
    }

    public static void validateTimeRangeParameters(Instant startTime, Instant endTime) {
        // to_time is optional, but if both are provided, from_time must be before to_time
        if (startTime != null && endTime != null && !startTime.isBefore(endTime)) {
            throw new BadRequestException(
                    "Parameter 'from_time' must be before 'to_time'");
        }
    }

    public static void validateDateRangeParameters(LocalDate fromDate, LocalDate toDate) {
        // both are optional, but if both are provided, from_date must not be after to_date (equal is a single-day window)
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new BadRequestException(
                    "Parameter 'from_date' must not be after 'to_date'");
        }
        // when to_date is omitted it defaults to the current day, so a future from_date describes an empty window;
        // reject it explicitly (an explicit future to_date is a valid forward-looking window)
        if (fromDate != null && toDate == null && fromDate.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new BadRequestException(
                    "Parameter 'from_date' must not be in the future");
        }
    }

    /**
     * Validates that a URL is not null/empty and starts with http:// or https://.
     *
     * @param url URL to validate
     * @param urlType Type of URL for error message (e.g., "URL", "Webhook URL", "Base URL")
     * @throws IllegalArgumentException if the URL is invalid
     */
    public static void validateHttpUrl(String url, String urlType) {
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException(urlType + " cannot be null or empty");
        }

        if (!url.trim().startsWith("http://") && !url.trim().startsWith("https://")) {
            throw new IllegalArgumentException(urlType + " must start with http:// or https://");
        }
    }

    /**
     * Checks whether a string is a syntactically valid, absolute URI.
     * @return {@code true} if non-null and parses to an absolute URI, {@code false} otherwise
     */
    public static boolean isAbsoluteUri(String value) {
        if (value == null) {
            return false;
        }

        try {
            return new URI(value).isAbsolute();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Redacts credentials from a URL for safe logging.
     * Replaces user:pass@ with user:***@ to prevent credential leakage.
     *
     * @param url URL that may contain credentials
     * @return URL with credentials redacted, or original URL if no credentials found
     */
    public static String redactCredentialsFromUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return url;
        }

        // Pattern: scheme://user:pass@host or scheme://user@host
        // Replace user:pass@ with user:***@
        String redacted = url.replaceAll("://([^:@/]+):([^@/]+)@", "://$1:***@");
        // Also handle case where only username is present (no password)
        redacted = redacted.replaceAll("://([^:@/]+)@", "://$1:***@");
        return redacted;
    }
}
