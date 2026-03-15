package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.ValueEntry;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;

@Mapper
public interface FeedbackScoreMapper {

    FeedbackScoreMapper INSTANCE = Mappers.getMapper(FeedbackScoreMapper.class);

    @Mapping(target = "categoryName", expression = "java(item.categoryName())")
    @Mapping(target = "name", expression = "java(item.name())")
    @Mapping(target = "value", expression = "java(item.value())")
    @Mapping(target = "reason", expression = "java(item.reason())")
    @Mapping(target = "source", expression = "java(item.source())")
    FeedbackScore toFeedbackScore(FeedbackScoreItem item);

    List<FeedbackScore> toFeedbackScores(List<? extends FeedbackScoreItem> feedbackScoreBatchItems);

    @Mapping(target = "id", source = "entityId")
    @Mapping(target = "author", ignore = true)
    FeedbackScoreBatchItem toFeedbackScoreBatchItem(UUID entityId, String projectName,
            FeedbackScore feedbackScore);

    @Mapping(target = "id", source = "entityId")
    @Mapping(target = "author", ignore = true)
    FeedbackScoreBatchItem toFeedbackScore(UUID entityId, UUID projectId, FeedbackScore score);

    /**
     * Maps a ClickHouse feedback scores result (List<List<Object>>) to a list of FeedbackScore objects.
     *
     * This method consolidates the mapping logic that was previously duplicated across
     * TraceDAO, SpanDAO, and FeedbackScoreDAO.
     *
     * @param feedbackScores ClickHouse result where each inner list contains:
     *                      [0] name (String)
     *                      [1] categoryName (String)
     *                      [2] value (BigDecimal)
     *                      [3] reason (String)
     *                      [4] source (String)
     *                      [5] valueByAuthor (Map)
     *                      [6] createdAt (OffsetDateTime)
     *                      [7] lastUpdatedAt (OffsetDateTime)
     *                      [8] createdBy (String)
     *                      [9] lastUpdatedBy (String)
     * @return List of FeedbackScore objects, or null if input is null/empty
     */
    static List<FeedbackScore> mapFeedbackScores(List<List<Object>> feedbackScores) {
        return Optional.ofNullable(feedbackScores)
                .orElse(List.of())
                .stream()
                .map(feedbackScore -> FeedbackScore.builder()
                        .name((String) feedbackScore.get(0))
                        .categoryName(getIfNotEmpty(feedbackScore.get(1)))
                        .value((BigDecimal) feedbackScore.get(2))
                        .reason(getIfNotEmpty(feedbackScore.get(3)))
                        .source(ScoreSource.fromString((String) feedbackScore.get(4)))
                        .valueByAuthor(parseValueByAuthor(feedbackScore.get(5)))
                        .createdAt(((OffsetDateTime) feedbackScore.get(6)).toInstant())
                        .lastUpdatedAt(((OffsetDateTime) feedbackScore.get(7)).toInstant())
                        .createdBy((String) feedbackScore.get(8))
                        .lastUpdatedBy((String) feedbackScore.get(9))
                        .build())
                .toList();
    }

    /**
     * Converts a nullable Object to a String, returning null if the value is null or empty.
     * This method ensures consistent handling of empty/null string values across the codebase.
     *
     * @param value The object to convert (expected to be a String or null)
     * @return The string value if not empty, null otherwise
     */
    static String getIfNotEmpty(Object value) {
        return Optional.ofNullable((String) value)
                .filter(StringUtils::isNotEmpty)
                .orElse(null);
    }

    /**
     * Parses ClickHouse valueByAuthor data structure into a Map of ValueEntry objects.
     *
     * ClickHouse returns maps as LinkedHashMap<String, List<Object>> where List<Object>
     * represents a tuple containing: (value, reason, category_name, source, last_updated_at)
     *
     * @param valueByAuthorObj The ClickHouse map object
     * @return Map of author names to ValueEntry objects
     */
    static Map<String, ValueEntry> parseValueByAuthor(Object valueByAuthorObj) {
        if (valueByAuthorObj == null) {
            return Map.of();
        }

        // ClickHouse returns maps as LinkedHashMap<String, List<Object>> where List<Object> represents a tuple
        @SuppressWarnings("unchecked")
        Map<String, List<Object>> valueByAuthorMap = (Map<String, List<Object>>) valueByAuthorObj;

        Map<String, ValueEntry> result = new HashMap<>();
        for (Map.Entry<String, List<Object>> entry : valueByAuthorMap.entrySet()) {
            String author = entry.getKey();
            List<Object> tuple = entry.getValue();

            // tuple contains: (value, reason, category_name, source, last_updated_at, span_type, span_id)
            // span_type and span_id are optional and only present for span feedback scores
            ValueEntry.ValueEntryBuilder builder = ValueEntry.builder()
                    .value((BigDecimal) tuple.get(0))
                    .reason(getIfNotEmpty(tuple.get(1)))
                    .categoryName(getIfNotEmpty(tuple.get(2)))
                    .source(ScoreSource.fromString((String) tuple.get(3)))
                    .lastUpdatedAt(parseInstant(tuple.get(4).toString()));

            // span_type is the 6th element (index 5), span_id is the 7th element (index 6)
            // only present for span feedback scores
            if (tuple.size() > 5 && tuple.get(5) != null) {
                builder.spanType((String) tuple.get(5));
            }
            // span_id is the 7th element (index 6)
            if (tuple.size() > 6 && tuple.get(6) != null) {
                builder.spanId((String) tuple.get(6));
            }

            ValueEntry valueEntry = builder.build();

            result.put(author, valueEntry);
        }

        return result;
    }

    static List<FeedbackScore> getFeedbackScores(Object feedbackScoresRaw) {
        if (feedbackScoresRaw instanceof String json) {
            return parseFeedbackScoresFromJson(json);
        }
        if (feedbackScoresRaw instanceof List[] feedbackScoresArray) {
            return parseFeedbackScoresFromArray(feedbackScoresArray);
        }
        return null;
    }

    private static List<FeedbackScore> parseFeedbackScoresFromJson(String json) {
        if (StringUtils.isBlank(json) || "[]".equals(json)) {
            return null;
        }

        JsonNode root = JsonUtils.getJsonNodeFromString(json);
        if (!root.isArray() || root.isEmpty()) {
            return null;
        }

        var feedbackScores = StreamSupport.stream(root.spliterator(), false)
                .filter(JsonNode::isObject)
                .filter(node -> !node.isEmpty())
                .map(FeedbackScoreMapper::parseFeedbackScoreFromJsonNode)
                .toList();
        return feedbackScores.isEmpty() ? null : feedbackScores;
    }

    private static FeedbackScore parseFeedbackScoreFromJsonNode(JsonNode node) {
        return FeedbackScore.builder()
                .name(node.get("name").asText())
                .categoryName(getJsonTextOrNull(node.get("category_name")))
                .value(node.get("value").decimalValue())
                .reason(getJsonTextOrNull(node.get("reason")))
                .source(ScoreSource.fromString(node.get("source").asText()))
                .createdAt(parseInstant(node.get("created_at").asText()))
                .lastUpdatedAt(parseInstant(node.get("last_updated_at").asText()))
                .createdBy(node.get("created_by").asText())
                .lastUpdatedBy(node.get("last_updated_by").asText())
                .valueByAuthor(parseValueByAuthorFromJson(node.get("value_by_author")))
                .build();
    }

    private static String getJsonTextOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return StringUtils.isNotEmpty(text) ? text : null;
    }

    private static Map<String, ValueEntry> parseValueByAuthorFromJson(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject() || node.isEmpty()) {
            return Map.of();
        }

        Map<String, ValueEntry> result = new HashMap<>();
        node.fields().forEachRemaining(entry -> {
            String author = entry.getKey();
            JsonNode tuple = entry.getValue();

            if (tuple.isArray() && !tuple.isEmpty()) {
                ValueEntry.ValueEntryBuilder builder = ValueEntry.builder()
                        .value(tuple.get(0).decimalValue())
                        .reason(getJsonTextOrNull(tuple.get(1)))
                        .categoryName(getJsonTextOrNull(tuple.get(2)))
                        .source(ScoreSource.fromString(tuple.get(3).asText()))
                        .lastUpdatedAt(parseInstant(tuple.get(4).asText()));

                if (tuple.size() > 5 && tuple.get(5) != null && !tuple.get(5).isNull()) {
                    builder.spanType(tuple.get(5).asText());
                }
                if (tuple.size() > 6 && tuple.get(6) != null && !tuple.get(6).isNull()) {
                    builder.spanId(tuple.get(6).asText());
                }

                result.put(author, builder.build());
            } else if (tuple.isObject() && !tuple.isEmpty()) {
                ValueEntry.ValueEntryBuilder builder = ValueEntry.builder()
                        .value(tuple.get("value").decimalValue())
                        .reason(getJsonTextOrNull(tuple.get("reason")))
                        .categoryName(getJsonTextOrNull(tuple.get("category_name")))
                        .source(ScoreSource.fromString(tuple.get("source").asText()))
                        .lastUpdatedAt(parseInstant(tuple.get("last_updated_at").asText()));

                if (tuple.has("span_type") && !tuple.get("span_type").isNull()) {
                    builder.spanType(tuple.get("span_type").asText());
                }
                if (tuple.has("span_id") && !tuple.get("span_id").isNull()) {
                    builder.spanId(tuple.get("span_id").asText());
                }

                result.put(author, builder.build());
            }
        });

        return result;
    }

    private static List<FeedbackScore> parseFeedbackScoresFromArray(List[] feedbackScoresArray) {
        var feedbackScores = Arrays.stream(feedbackScoresArray)
                .filter(feedbackScore -> CollectionUtils.isNotEmpty(feedbackScore) &&
                        !CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(feedbackScore.getFirst().toString()))
                .map(feedbackScore -> FeedbackScore.builder()
                        .name(feedbackScore.get(1).toString())
                        .categoryName(Optional.ofNullable(feedbackScore.get(2)).map(Object::toString)
                                .filter(StringUtils::isNotEmpty).orElse(null))
                        .value(new BigDecimal(feedbackScore.get(3).toString()))
                        .reason(Optional.ofNullable(feedbackScore.get(4)).map(Object::toString)
                                .filter(StringUtils::isNotEmpty).orElse(null))
                        .source(ScoreSource.fromString(feedbackScore.get(5).toString()))
                        .createdAt(parseInstant(feedbackScore.get(6).toString()))
                        .lastUpdatedAt(parseInstant(feedbackScore.get(7).toString()))
                        .createdBy(feedbackScore.get(8).toString())
                        .lastUpdatedBy(feedbackScore.get(9).toString())
                        .valueByAuthor(parseValueByAuthor(feedbackScore.get(10)))
                        .build())
                .toList();
        return feedbackScores.isEmpty() ? null : feedbackScores;
    }

    private static Instant parseInstant(String date) {
        return Instant.parse(date);
    }
}
