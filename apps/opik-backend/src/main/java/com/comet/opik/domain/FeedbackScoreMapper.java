package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.ValueEntry;
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

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;

@Mapper
public interface FeedbackScoreMapper {

    FeedbackScoreMapper INSTANCE = Mappers.getMapper(FeedbackScoreMapper.class);

    @Mapping(target = "categoryName", expression = "java(item.categoryName())")
    @Mapping(target = "name", expression = "java(item.name())")
    @Mapping(target = "value", expression = "java(item.value())")
    @Mapping(target = "reason", expression = "java(item.reason())")
    @Mapping(target = "error", expression = "java(item.error() == null ? 0 : item.error())")
    @Mapping(target = "errorReason", expression = "java(item.errorReason())")
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
     *                      [4] error (Number) [optional]
     *                      [5] errorReason (String) [optional]
     *                      [6] source (String)
     *                      [7] valueByAuthor (Map)
     *                      [8] createdAt (OffsetDateTime)
     *                      [9] lastUpdatedAt (OffsetDateTime)
     *                      [10] createdBy (String)
     *                      [11] lastUpdatedBy (String)
     * @return List of FeedbackScore objects, or null if input is null/empty
     */
    static List<FeedbackScore> mapFeedbackScores(List<List<Object>> feedbackScores) {
        return Optional.ofNullable(feedbackScores)
                .orElse(List.of())
                .stream()
                .map(feedbackScore -> {
                    boolean hasErrorColumns = feedbackScore.size() >= 12;
                    int sourceIndex = hasErrorColumns ? 6 : 4;
                    int valueByAuthorIndex = hasErrorColumns ? 7 : 5;
                    int createdAtIndex = hasErrorColumns ? 8 : 6;
                    int lastUpdatedAtIndex = hasErrorColumns ? 9 : 7;
                    int createdByIndex = hasErrorColumns ? 10 : 8;
                    int lastUpdatedByIndex = hasErrorColumns ? 11 : 9;

                    return FeedbackScore.builder()
                            .name((String) feedbackScore.getFirst())
                            .categoryName(getIfNotEmpty(feedbackScore.get(1)))
                            .value((BigDecimal) feedbackScore.get(2))
                            .reason(getIfNotEmpty(feedbackScore.get(3)))
                            .error(hasErrorColumns ? parseError(feedbackScore.get(4)) : 0)
                            .errorReason(hasErrorColumns
                                    ? Optional.ofNullable(feedbackScore.get(5))
                                            .map(Object::toString)
                                            .filter(StringUtils::isNotEmpty)
                                            .orElse(null)
                                    : null)
                            .source(ScoreSource.fromString((String) feedbackScore.get(sourceIndex)))
                            .valueByAuthor(parseValueByAuthor(feedbackScore.get(valueByAuthorIndex)))
                            .createdAt(((OffsetDateTime) feedbackScore.get(createdAtIndex)).toInstant())
                            .lastUpdatedAt(((OffsetDateTime) feedbackScore.get(lastUpdatedAtIndex)).toInstant())
                            .createdBy((String) feedbackScore.get(createdByIndex))
                            .lastUpdatedBy((String) feedbackScore.get(lastUpdatedByIndex))
                            .build();
                })
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
                    .lastUpdatedAt(((OffsetDateTime) tuple.get(4)).toInstant());

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
        if (feedbackScoresRaw instanceof List[] feedbackScoresArray) {
            var feedbackScores = Arrays.stream(feedbackScoresArray)
                    .filter(feedbackScore -> CollectionUtils.isNotEmpty(feedbackScore) &&
                            !CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(feedbackScore.getFirst().toString()))
                    .map(feedbackScore -> {
                        boolean hasErrorColumns = feedbackScore.size() >= 13;
                        int sourceIndex = hasErrorColumns ? 7 : 5;
                        int createdAtIndex = hasErrorColumns ? 8 : 6;
                        int lastUpdatedAtIndex = hasErrorColumns ? 9 : 7;
                        int createdByIndex = hasErrorColumns ? 10 : 8;
                        int lastUpdatedByIndex = hasErrorColumns ? 11 : 9;
                        int valueByAuthorIndex = hasErrorColumns ? 12 : 10;

                        return FeedbackScore.builder()
                                .name(feedbackScore.get(1).toString())
                                .categoryName(Optional.ofNullable(feedbackScore.get(2)).map(Object::toString)
                                        .filter(StringUtils::isNotEmpty).orElse(null))
                                .value(new BigDecimal(feedbackScore.get(3).toString()))
                                .reason(Optional.ofNullable(feedbackScore.get(4)).map(Object::toString)
                                        .filter(StringUtils::isNotEmpty).orElse(null))
                                .error(hasErrorColumns ? parseError(feedbackScore.get(5)) : 0)
                                .errorReason(hasErrorColumns
                                        ? Optional.ofNullable(feedbackScore.get(6))
                                                .map(Object::toString)
                                                .filter(StringUtils::isNotEmpty)
                                                .orElse(null)
                                        : null)
                                .source(ScoreSource.fromString(feedbackScore.get(sourceIndex).toString()))
                                .createdAt(Instant.parse(feedbackScore.get(createdAtIndex).toString()))
                                .lastUpdatedAt(Instant.parse(feedbackScore.get(lastUpdatedAtIndex).toString()))
                                .createdBy(feedbackScore.get(createdByIndex).toString())
                                .lastUpdatedBy(feedbackScore.get(lastUpdatedByIndex).toString())
                                .valueByAuthor(parseValueByAuthor(feedbackScore.get(valueByAuthorIndex)))
                                .build();
                    })
                    .toList();
            return feedbackScores.isEmpty() ? null : feedbackScores;
        }
        return null;
    }

    static int parseError(Object value) {
        return Optional.ofNullable(value)
                .map(v -> v instanceof Number number ? number.intValue() : Integer.parseInt(v.toString()))
                .orElse(0);
    }

}
