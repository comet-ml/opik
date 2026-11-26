package com.comet.opik.domain.evaluators;

import com.comet.opik.api.AnnotationQueueAutomation;
import com.comet.opik.domain.AnnotationQueueAutomationFilters;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * Router row to the automation the API returns.
 *
 * <p>Only {@code filterGroups} needs help: it is stored as JSON text in the rule's shared {@code filters} column
 * and exposed as a structure, so it gets a custom mapping while the rest is generated.
 */
@Mapper(imports = AnnotationQueueAutomationFilters.class)
public interface AnnotationQueueAutomationMapper {

    AnnotationQueueAutomationMapper INSTANCE = Mappers.getMapper(AnnotationQueueAutomationMapper.class);

    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "filterGroups", expression = "java(AnnotationQueueAutomationFilters.readFilterGroups(model.filters()))")
    @Mapping(target = "maxItemsInQueue", expression = "java(model.maxItemsInQueue())")
    @Mapping(target = "clearMaxItemsInQueue", ignore = true)
    @Mapping(target = "conditions", ignore = true)
    AnnotationQueueAutomation map(AutomationRuleAnnotationQueueRouterModel model);
}
