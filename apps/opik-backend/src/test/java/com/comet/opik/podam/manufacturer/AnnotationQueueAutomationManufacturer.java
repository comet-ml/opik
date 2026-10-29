package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.AnnotationQueueAutomation;
import com.comet.opik.api.annotationqueue.ConditionGroup;
import com.comet.opik.api.annotationqueue.Conditions;
import com.comet.opik.api.annotationqueue.ScoreCondition;
import com.comet.opik.api.annotationqueue.ScoreConditionOperator;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.api.PodamUtils;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.List;

/**
 * Builds an automation a request can actually carry.
 *
 * <p>Left to itself Podam fills every component, and {@code clear_max_items_in_queue} next to a ceiling is
 * the one combination the API rejects, so every generated queue payload would be a 422. It also makes the
 * automation enabled with a condition, since an enabled one without conditions is rejected too.
 */
public class AnnotationQueueAutomationManufacturer extends AbstractTypeManufacturer<AnnotationQueueAutomation> {

    public static final AnnotationQueueAutomationManufacturer INSTANCE = new AnnotationQueueAutomationManufacturer();

    @Override
    public AnnotationQueueAutomation getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {

        var condition = ScoreCondition.builder()
                .scoreName(RandomStringUtils.secure().nextAlphanumeric(10))
                .operator(ScoreConditionOperator.GREATER_THAN)
                .value(PodamUtils.getDoubleInRange(0, 1))
                .build();

        return AnnotationQueueAutomation.builder()
                .enabled(true)
                .conditions(Conditions.builder()
                        .groups(List.of(ConditionGroup.builder().conditions(List.of(condition)).build()))
                        .build())
                .maxItemsInQueue(PodamUtils.getIntegerInRange(1, 1000))
                .build();
    }
}
