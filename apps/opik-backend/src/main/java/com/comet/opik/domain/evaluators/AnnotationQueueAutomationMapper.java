package com.comet.opik.domain.evaluators;

import com.comet.opik.api.AnnotationQueueAutomation;
import com.comet.opik.api.annotationqueue.Conditions;
import com.comet.opik.utils.JsonUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * Router row to the automation the API returns.
 *
 * <p>Only {@code conditions} needs help: it is stored as JSON text and exposed as a structure, so it gets
 * a custom mapping while the rest is generated.
 */
@Mapper
public interface AnnotationQueueAutomationMapper {

    AnnotationQueueAutomationMapper INSTANCE = Mappers.getMapper(AnnotationQueueAutomationMapper.class);

    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "conditions", expression = "java(toConditions(model.conditions()))")
    @Mapping(target = "maxItemsInQueue", expression = "java(model.maxItemsInQueue())")
    AnnotationQueueAutomation map(AutomationRuleAnnotationQueueRouterModel model);

    default Conditions toConditions(String conditions) {
        return conditions == null
                ? null
                : JsonUtils.readValue(conditions, Conditions.class);
    }
}
