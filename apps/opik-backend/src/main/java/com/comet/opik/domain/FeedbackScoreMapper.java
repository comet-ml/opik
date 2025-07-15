package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.ScoreSource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
    @Mapping(target = "source", expression = "java(item.source())")
    FeedbackScore toFeedbackScore(FeedbackScoreItem item);

    List<FeedbackScore> toFeedbackScores(List<? extends FeedbackScoreItem> feedbackScoreBatchItems);

    @Mapping(target = "id", source = "entityId")
    FeedbackScoreBatchItem toFeedbackScoreBatchItem(UUID entityId, String projectName,
            FeedbackScore feedbackScore);

    @Mapping(target = "id", source = "entityId")
    FeedbackScoreBatchItem toFeedbackScore(UUID entityId, UUID projectId, FeedbackScore score);

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
                            .build())
                    .toList();
            return feedbackScores.isEmpty() ? null : feedbackScores;
        }
        return null;
    }
}
