package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreGroup;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.ScoreSource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    @Mapping(target = "scoreId", expression = "java(item.scoreId() != null ? item.scoreId() : java.util.UUID.randomUUID())")
    FeedbackScore toFeedbackScore(FeedbackScoreItem item);

    List<FeedbackScore> toFeedbackScores(List<? extends FeedbackScoreItem> feedbackScoreBatchItems);

    @Mapping(target = "id", source = "entityId")
    @Mapping(target = "scoreId", expression = "java(score.scoreId() != null ? score.scoreId() : java.util.UUID.randomUUID())")
    FeedbackScoreBatchItem toFeedbackScoreBatchItem(UUID entityId, String projectName,
            FeedbackScore feedbackScore);

    @Mapping(target = "id", source = "entityId")
    @Mapping(target = "scoreId", expression = "java(score.scoreId() != null ? score.scoreId() : java.util.UUID.randomUUID())")
    FeedbackScoreBatchItem toFeedbackScore(UUID entityId, UUID projectId, FeedbackScore score);

    /**
     * Groups feedback scores by name and creates FeedbackScoreGroup objects with aggregated statistics
     */
    default List<FeedbackScoreGroup> toFeedbackScoreGroups(List<FeedbackScore> scores) {
        if (CollectionUtils.isEmpty(scores)) {
            return List.of();
        }

        Map<String, List<FeedbackScore>> scoresByName = scores.stream()
                .collect(Collectors.groupingBy(FeedbackScore::name));

        return scoresByName.entrySet().stream()
                .map(entry -> {
                    String name = entry.getKey();
                    List<FeedbackScore> scoreList = entry.getValue();
                    
                    // Calculate statistics
                    BigDecimal sum = scoreList.stream()
                            .map(FeedbackScore::value)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal average = sum.divide(BigDecimal.valueOf(scoreList.size()), 4, RoundingMode.HALF_UP);
                    
                    BigDecimal minValue = scoreList.stream()
                            .map(FeedbackScore::value)
                            .min(BigDecimal::compareTo)
                            .orElse(BigDecimal.ZERO);
                    
                    BigDecimal maxValue = scoreList.stream()
                            .map(FeedbackScore::value)
                            .max(BigDecimal::compareTo)
                            .orElse(BigDecimal.ZERO);

                    // Use category name from the first score (they should be the same for the same name)
                    String categoryName = scoreList.getFirst().categoryName();

                    return FeedbackScoreGroup.builder()
                            .name(name)
                            .categoryName(categoryName)
                            .averageValue(average)
                            .minValue(minValue)
                            .maxValue(maxValue)
                            .scoreCount(scoreList.size())
                            .scores(scoreList)
                            .build();
                })
                .toList();
    }

    static List<FeedbackScore> getFeedbackScores(Object feedbackScoresRaw) {
        if (feedbackScoresRaw instanceof List[] feedbackScoresArray) {
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
                            .createdAt(Instant.parse(feedbackScore.get(6).toString()))
                            .lastUpdatedAt(Instant.parse(feedbackScore.get(7).toString()))
                            .createdBy(feedbackScore.get(8).toString())
                            .lastUpdatedBy(feedbackScore.get(9).toString())
                            .scoreId(Optional.ofNullable(feedbackScore.get(10))
                                    .map(Object::toString)
                                    .filter(id -> !CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(id))
                                    .map(UUID::fromString)
                                    .orElse(null))
                            .build())
                    .toList();
            return feedbackScores.isEmpty() ? null : feedbackScores;
        }
        return null;
    }
}
