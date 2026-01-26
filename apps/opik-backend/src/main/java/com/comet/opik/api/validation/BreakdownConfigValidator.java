package com.comet.opik.api.validation;

import com.comet.opik.api.metrics.BreakdownQueryBuilder;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

import java.util.EnumSet;
import java.util.Set;

public class BreakdownConfigValidator
        implements
            ConstraintValidator<BreakdownConfigValidation, ProjectMetricRequest> {

    private static final Set<MetricType> DURATION_METRICS = EnumSet.of(
            MetricType.DURATION,
            MetricType.SPAN_DURATION,
            MetricType.THREAD_DURATION);

    private static final Set<MetricType> FEEDBACK_SCORES_METRICS = EnumSet.of(
            MetricType.FEEDBACK_SCORES,
            MetricType.SPAN_FEEDBACK_SCORES,
            MetricType.THREAD_FEEDBACK_SCORES);

    private static final Set<MetricType> TOKEN_USAGE_METRICS = EnumSet.of(
            MetricType.TOKEN_USAGE,
            MetricType.SPAN_TOKEN_USAGE);

    private static final Set<String> VALID_DURATION_SUB_METRICS = Set.of("p50", "p90", "p99");

    @Override
    public boolean isValid(ProjectMetricRequest request, ConstraintValidatorContext context) {
        if (!request.hasBreakdown()) {
            return true;
        }

        context.disableDefaultConstraintViolation();

        try {
            BreakdownQueryBuilder.validate(request.breakdown(), request.metricType());

            String subMetric = request.breakdown().subMetric();

            // For duration metrics with breakdown, subMetric is required (p50, p90, p99)
            if (DURATION_METRICS.contains(request.metricType())) {
                if (StringUtils.isBlank(subMetric)) {
                    context.buildConstraintViolationWithTemplate(
                            "sub_metric is required for duration metrics with breakdown. Valid values: p50, p90, p99")
                            .addPropertyNode("breakdown")
                            .addPropertyNode("subMetric")
                            .addConstraintViolation();
                    return false;
                }
                if (!VALID_DURATION_SUB_METRICS.contains(subMetric.toLowerCase())) {
                    context.buildConstraintViolationWithTemplate(
                            "Invalid sub_metric '%s'. Valid values: p50, p90, p99".formatted(subMetric))
                            .addPropertyNode("breakdown")
                            .addPropertyNode("subMetric")
                            .addConstraintViolation();
                    return false;
                }
            }

            // For feedback scores metrics with breakdown, subMetric (feedback score name) is required
            if (FEEDBACK_SCORES_METRICS.contains(request.metricType())) {
                if (StringUtils.isBlank(subMetric)) {
                    context.buildConstraintViolationWithTemplate(
                            "sub_metric is required for feedback scores metrics with breakdown. It should be the feedback score name.")
                            .addPropertyNode("breakdown")
                            .addPropertyNode("subMetric")
                            .addConstraintViolation();
                    return false;
                }
            }

            // For token usage metrics with breakdown, subMetric (usage key name) is required
            if (TOKEN_USAGE_METRICS.contains(request.metricType())) {
                if (StringUtils.isBlank(subMetric)) {
                    context.buildConstraintViolationWithTemplate(
                            "sub_metric is required for token usage metrics with breakdown. It should be the usage key name (e.g., completion_tokens, prompt_tokens).")
                            .addPropertyNode("breakdown")
                            .addPropertyNode("subMetric")
                            .addConstraintViolation();
                    return false;
                }
            }

            return true;

        } catch (IllegalArgumentException e) {
            context.buildConstraintViolationWithTemplate(e.getMessage())
                    .addPropertyNode("breakdown")
                    .addConstraintViolation();
            return false;
        }
    }
}
