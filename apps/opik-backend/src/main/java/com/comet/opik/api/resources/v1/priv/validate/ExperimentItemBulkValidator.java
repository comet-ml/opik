package com.comet.opik.api.resources.v1.priv.validate;

import com.comet.opik.api.ExperimentItemBulkRecord;
import jakarta.ws.rs.BadRequestException;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;

@UtilityClass
public class ExperimentItemBulkValidator {

    public static void validate(ExperimentItemBulkRecord item) {
        if (item.trace() == null && !CollectionUtils.isEmpty(item.spans())) {
            throw new BadRequestException("Trace is required when spans are provided");
        } else if (item.trace() != null && hasMismatchingTraceId(item)) {
            throw new BadRequestException("Trace ID must match the span's trace ID");
        }
    }

    private static boolean hasMismatchingTraceId(ExperimentItemBulkRecord item) {
        return !CollectionUtils.isEmpty(item.spans())
                && item.spans().stream().anyMatch(span -> !item.trace().id().equals(span.traceId()));
    }
}
