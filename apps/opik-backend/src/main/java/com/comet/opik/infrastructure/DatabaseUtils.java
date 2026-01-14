package com.comet.opik.infrastructure;

import com.comet.opik.api.DatasetVersionCreate;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.utils.template.TemplateUtils;
import io.dropwizard.db.DataSourceFactory;
import io.r2dbc.spi.Statement;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@UtilityClass
@Slf4j
public class DatabaseUtils {

    public static final int ANALYTICS_DELETE_BATCH_SIZE = 10000;
    public static final int UUID_POOL_MULTIPLIER = 2;
    private static final String LOG_COMMENT = "<query_name>:<workspace_id>:<details>";

    /**
     * Generates a pool of UUIDv7 identifiers for batch operations.
     * The pool is sized at 2x the expected count for safety margin.
     *
     * @param idGenerator the ID generator to use for UUIDv7 generation
     * @param expectedCount the expected number of items
     * @return a list of UUIDv7 identifiers
     */
    public static List<UUID> generateUuidPool(IdGenerator idGenerator, int expectedCount) {
        int poolSize = Math.max(1, expectedCount * UUID_POOL_MULTIPLIER);
        return IntStream.range(0, poolSize)
                .mapToObj(i -> idGenerator.generateId())
                .toList();
    }

    public static DataSourceFactory filterProperties(DataSourceFactory dataSourceFactory) {
        var filteredProperties = dataSourceFactory.getProperties()
                .entrySet()
                .stream()
                .filter(entry -> StringUtils.isNotBlank(entry.getValue()))
                .collect(Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        java.util.Map.Entry::getValue));
        dataSourceFactory.setProperties(filteredProperties);

        return dataSourceFactory;
    }

    /**
     * Calculate placeholder hash for version identification.
     * TODO OPIK-3015: Replace with actual content-based hash from dataset items.
     *
     * @param datasetId the dataset identifier
     * @param request the version creation request containing metadata
     * @param itemsTotal total number of items in the version
     * @param itemsAdded number of items added
     * @param itemsModified number of items modified
     * @param itemsDeleted number of items deleted
     * @return a hex string hash (first 16 characters of SHA-256)
     */
    public static String calculatePlaceholderVersionHash(UUID datasetId, DatasetVersionCreate request,
            int itemsTotal, int itemsAdded, int itemsModified, int itemsDeleted) {
        try {
            // Use dataset ID + request payload + counters + timestamp for unique hash per commit
            // This prevents unexpected collisions until we have actual content-based hashing
            String input = datasetId.toString() + ":" +
                    (request.tags() != null ? request.tags().toString() : "") + ":" +
                    (request.changeDescription() != null ? request.changeDescription() : "") + ":" +
                    (request.metadata() != null ? request.metadata().toString() : "") + ":" +
                    itemsTotal + ":" + itemsAdded + ":" + itemsModified + ":" + itemsDeleted + ":" +
                    System.currentTimeMillis();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // Convert to hex string (first 16 chars for display)
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 8 && i < hashBytes.length; i++) {
                String hex = Integer.toHexString(0xff & hashBytes[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public static ST newTraceThreadFindTemplate(String query, TraceSearchCriteria traceSearchCriteria) {
        var template = TemplateUtils.newST(query);
        Optional.ofNullable(traceSearchCriteria.filters())
                .ifPresent(filters -> {
                    FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE)
                            .ifPresent(traceFilters -> template.add("filters", traceFilters));
                    FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE_AGGREGATION)
                            .ifPresent(traceAggregationFilters -> template.add("trace_aggregation_filters",
                                    traceAggregationFilters));
                    FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(scoresFilters -> template.add("feedback_scores_filters", scoresFilters));
                    FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES)
                            .ifPresent(spanScoresFilters -> template.add("span_feedback_scores_filters",
                                    spanScoresFilters));
                    FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.ANNOTATION_AGGREGATION)
                            .ifPresent(traceAnnotationFilters -> template.add("annotation_queue_filters",
                                    traceAnnotationFilters));
                    FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.EXPERIMENT_AGGREGATION)
                            .ifPresent(traceExperimentFilters -> template.add("experiment_filters",
                                    traceExperimentFilters));
                    FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE_THREAD)
                            .ifPresent(threadFilters -> template.add("trace_thread_filters", threadFilters));
                    FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoreIsEmptyFilters -> template.add("feedback_scores_empty_filters",
                                    feedbackScoreIsEmptyFilters));
                    FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoreIsEmptyFilters -> template.add("span_feedback_scores_empty_filters",
                                    feedbackScoreIsEmptyFilters));
                    FilterQueryBuilder.hasGuardrailsFilter(filters)
                            .ifPresent(hasGuardrailsFilter -> template.add("guardrails_filters", true));
                });
        Optional.ofNullable(traceSearchCriteria.lastReceivedId())
                .ifPresent(lastReceivedTraceId -> template.add("last_received_id", lastReceivedTraceId));

        // Add UUID bounds for time-based filtering (presence of uuid_from_time triggers the conditional)
        Optional.ofNullable(traceSearchCriteria.uuidFromTime())
                .ifPresent(uuid_from_time -> template.add("uuid_from_time", uuid_from_time));
        Optional.ofNullable(traceSearchCriteria.uuidToTime())
                .ifPresent(uuid_to_time -> template.add("uuid_to_time", uuid_to_time));
        return template;
    }

    public static void bindTraceThreadSearchCriteria(TraceSearchCriteria traceSearchCriteria, Statement statement) {
        Optional.ofNullable(traceSearchCriteria.filters())
                .ifPresent(filters -> {
                    FilterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE);
                    FilterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_AGGREGATION);
                    FilterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                    FilterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES);
                    FilterQueryBuilder.bind(statement, filters, FilterStrategy.ANNOTATION_AGGREGATION);
                    FilterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT_AGGREGATION);
                    FilterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_THREAD);
                    FilterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                    FilterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES_IS_EMPTY);
                });
        Optional.ofNullable(traceSearchCriteria.lastReceivedId())
                .ifPresent(lastReceivedTraceId -> statement.bind("last_received_id", lastReceivedTraceId));
        // Bind UUID BETWEEN bounds for time-based filtering
        Optional.ofNullable(traceSearchCriteria.uuidFromTime())
                .ifPresent(uuid_from_time -> statement.bind("uuid_from_time", uuid_from_time));
        Optional.ofNullable(traceSearchCriteria.uuidToTime())
                .ifPresent(uuid_to_time -> statement.bind("uuid_to_time", uuid_to_time));
    }

    public static ST getSTWithLogComment(String query, String queryName, String workspaceId, Object details) {
        var logComment = getLogComment(queryName, workspaceId, details);
        return TemplateUtils.newST(query)
                .add("log_comment", logComment);
    }

    public static String getLogComment(String queryName, String workspaceId, Object details) {
        return TemplateUtils.newST(LOG_COMMENT)
                .add("query_name", queryName != null ? queryName.replace("'", "''") : null)
                .add("workspace_id", workspaceId != null ? workspaceId.replace("'", "''") : null)
                .add("details", details != null ? details.toString().replace("'", "''") : null)
                .render();
    }
}
