package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ExperimentItemBulkRecord;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.IdGenerator;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

@UtilityClass
class ExperimentItemBulkMapper {

    static ExperimentItemBulkRecord addIdsIfRequired(@NonNull IdGenerator idGenerator,
            @NonNull ExperimentItemBulkRecord item) {
        var itemBuilder = item.toBuilder();
        Trace trace = item.trace();

        if (item.trace() != null && item.trace().id() == null) {
            trace = item.trace().toBuilder()
                    .id(idGenerator.generateId())
                    .build();

            itemBuilder.trace(trace);
        }

        if (CollectionUtils.isNotEmpty(item.spans())) {
            Trace finalTrace = trace;

            List<Span> spans = item.spans()
                    .stream()
                    .map(span -> {
                        Span.SpanBuilder builder = span.toBuilder();

                        if (span.id() == null) {
                            builder.id(idGenerator.generateId());
                        }

                        if (span.traceId() == null && finalTrace != null) {
                            builder.traceId(finalTrace.id());
                            builder.projectName(finalTrace.projectName());
                        }

                        return builder.build();
                    })
                    .toList();

            itemBuilder.spans(spans);
        }

        return itemBuilder
                .trace(trace)
                .build();
    }
}
