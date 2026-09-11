package com.comet.opik.domain;

import com.comet.opik.api.FeedbackDefinition;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
interface FeedbackDefinitionMapper {

    FeedbackDefinitionMapper INSTANCE = Mappers.getMapper(FeedbackDefinitionMapper.class);

    @Mapping(target = "details", expression = "java(map(model.details()))")
    FeedbackDefinition.NumericalFeedbackDefinition map(NumericalFeedbackDefinitionDefinitionModel model);

    @Mapping(target = "details", expression = "java(map(model.details()))")
    FeedbackDefinition.CategoricalFeedbackDefinition map(CategoricalFeedbackDefinitionDefinitionModel model);

    @Mapping(target = "details", expression = "java(map(model.details()))")
    FeedbackDefinition.BooleanFeedbackDefinition map(BooleanFeedbackDefinitionDefinitionModel model);

    NumericalFeedbackDefinitionDefinitionModel map(FeedbackDefinition.NumericalFeedbackDefinition numerical);
    CategoricalFeedbackDefinitionDefinitionModel map(FeedbackDefinition.CategoricalFeedbackDefinition categorical);
    BooleanFeedbackDefinitionDefinitionModel map(FeedbackDefinition.BooleanFeedbackDefinition booleanDef);

    FeedbackDefinition.CategoricalFeedbackDefinition.CategoricalFeedbackDetail map(
            CategoricalFeedbackDefinitionDefinitionModel.CategoricalFeedbackDetail detail);
    FeedbackDefinition.NumericalFeedbackDefinition.NumericalFeedbackDetail map(
            NumericalFeedbackDefinitionDefinitionModel.NumericalFeedbackDetail detail);
    FeedbackDefinition.BooleanFeedbackDefinition.BooleanFeedbackDetail map(
            BooleanFeedbackDefinitionDefinitionModel.BooleanFeedbackDetail detail);

}
