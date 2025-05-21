package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemBulkRecord;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@UtilityClass
class ExperimentItemBulkMapper {

    static ExperimentItemBulkRecord addIdsIfRequired(IdGenerator idGenerator, ExperimentItemBulkRecord item) {
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

    static void splitBatches(IdGenerator idGenerator,
            List<ExperimentItemBulkRecord> items,
            List<Trace> traces, UUID experimentId,
            Set<ExperimentItem> experimentItems,
            List<Span> spans,
            List<FeedbackScoreBatchItem> feedbackScores) {

        for (ExperimentItemBulkRecord item : items) {
            Trace trace;

            // If the trace is null, create a new trace "invisible" to the user
            if (item.trace() == null) {
                Instant now = Instant.now();
                trace = Trace.builder()
                        .id(idGenerator.generateId())
                        .projectName(ProjectService.DEFAULT_PROJECT)
                        .name("")
                        .startTime(now)
                        .endTime(now)
                        .build();
            } else {
                trace = item.trace();
            }

            traces.add(trace);

            ExperimentItem build = ExperimentItem.builder()
                    .id(idGenerator.generateId())
                    .experimentId(experimentId)
                    .datasetItemId(item.datasetItemId())
                    .traceId(trace.id())
                    .build();

            experimentItems.add(build);

            if (CollectionUtils.isNotEmpty(item.spans())) {
                spans.addAll(item.spans());
            }

            if (CollectionUtils.isNotEmpty(item.feedbackScores())) {
                feedbackScores.addAll(item.feedbackScores()
                        .stream()
                        .map(score -> FeedbackScoreBatchItem.builder()
                                .name(score.name())
                                .value(score.value())
                                .id(trace.id())
                                .projectName(trace.projectName())
                                .categoryName(score.categoryName())
                                .reason(score.reason())
                                .source(score.source())
                                .build())
                        .toList());
            }
        }
    }

}
