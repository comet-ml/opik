package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ScoreSource;
import com.comet.opik.domain.evaluators.python.PythonScoreResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;

@Mapper
interface FeedbackScoresMapper {

    FeedbackScoresMapper INSTANCE = org.mapstruct.factory.Mappers.getMapper(FeedbackScoresMapper.class);

    @Mapping(target = "id", expression = "java(item.id())")
    @Mapping(target = "projectId", expression = "java(item.projectId())")
    @Mapping(target = "projectName", expression = "java(item.projectName())")
    @Mapping(target = "name", expression = "java(item.name())")
    @Mapping(target = "value", expression = "java(item.value())")
    @Mapping(target = "categoryName", expression = "java(item.categoryName())")
    @Mapping(target = "reason", expression = "java(item.reason())")
    @Mapping(target = "source", expression = "java(item.source())")
    FeedbackScoreBatchItemThread map(FeedbackScoreBatchItem item, String threadId);

    @Mapping(target = "name", expression = "java(score.name())")
    @Mapping(target = "value", expression = "java(score.value())")
    @Mapping(target = "reason", expression = "java(score.reason())")
    FeedbackScoreBatchItemThread map(PythonScoreResult score,
            UUID id,
            String threadId,
            UUID projectId,
            String projectName,
            ScoreSource source);

}
