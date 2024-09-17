package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

@Mapper
public interface FeedbackScoreMapper {

    FeedbackScoreMapper INSTANCE = Mappers.getMapper(FeedbackScoreMapper.class);

    FeedbackScore toFeedbackScore(FeedbackScoreBatchItem feedbackScoreBatchItem);

    @Mapping(target = "id", source = "entityId")
    FeedbackScoreBatchItem toFeedbackScoreBatchItem(UUID entityId, String projectName, FeedbackScore feedbackScore);
}
