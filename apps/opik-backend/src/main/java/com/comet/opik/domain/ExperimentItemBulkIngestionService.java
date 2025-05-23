package com.comet.opik.domain;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemBulkRecord;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.AsyncUtils;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.jetbrains.annotations.NotNull;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(ExperimentItemBulkIngestionServiceImpl.class)
public interface ExperimentItemBulkIngestionService {

    /**
     * Ingests a batch of experiment items.
     *
     * @param items the list of experiment items to ingest
     * @return a list of feedback score batch items
     */
    Mono<Void> ingest(Experiment experiment, List<ExperimentItemBulkRecord> items);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ExperimentItemBulkIngestionServiceImpl implements ExperimentItemBulkIngestionService {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ExperimentService experimentService;
    private final @NonNull ExperimentItemService experimentItemService;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull IdGenerator idGenerator;

    /**
     * Ingests a batch of experiment items.
     *
     * @param items the list of experiment items to ingest
     * @return a list of feedback score batch items
     */
    @Override
    public Mono<Void> ingest(Experiment experiment, List<ExperimentItemBulkRecord> items) {

        return Mono.deferContextual(ctx -> {

            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return experimentService.create(experiment)
                    .retryWhen(AsyncUtils.handleConnectionError())
                    .flatMap(experimentId -> {

                        log.info("Created experiment with id '{}', name '{}', datasetName '{}', workspaceId '{}'",
                                experimentId, experiment.name(), experiment.datasetName(), workspaceId);

                        Set<ExperimentItem> experimentItems = new HashSet<>();
                        List<Trace> traces = new ArrayList<>();
                        List<Span> spans = new ArrayList<>();
                        List<FeedbackScoreBatchItem> feedbackScores = new ArrayList<>();

                        this.splitBatches(
                                items,
                                traces,
                                experimentId,
                                experimentItems,
                                spans,
                                feedbackScores);

                        return experimentItemService.create(experimentItems)
                                .then(saveAll(traces, spans, feedbackScores))
                                .flatMap(tuple -> {

                                    log.info(
                                            "Recorded experiment items in bulk, experiment items count '{}', traces count '{}', spans count '{}' and feedback scores count '{}'",
                                            experimentItems.size(), traces.size(), spans.size(), feedbackScores.size());

                                    return Mono.just(tuple);
                                })
                                .then();
                    });
        });
    }

    private @NotNull Mono<? extends Tuple3<Long, ? extends Number, Integer>> saveAll(List<Trace> traces,
            List<Span> spans, List<FeedbackScoreBatchItem> feedbackScores) {
        return Mono.defer(() -> Mono.zip(
                saveTraces(traces),
                saveSpans(spans),
                saveFeedBackScores(feedbackScores)));
    }

    private @NotNull Mono<Long> saveTraces(List<Trace> traces) {
        return traceService.create(new TraceBatch(traces))
                .retryWhen(AsyncUtils.handleConnectionError());
    }

    private @NotNull Mono<? extends Number> saveSpans(List<Span> spans) {
        return spans.isEmpty()
                ? Mono.just(0)
                : spanService.create(new SpanBatch(spans))
                        .retryWhen(AsyncUtils.handleConnectionError());
    }

    private @NotNull Mono<Integer> saveFeedBackScores(List<FeedbackScoreBatchItem> feedbackScores) {
        return feedbackScores.isEmpty()
                ? Mono.just(0)
                : feedbackScoreService.scoreBatchOfTraces(feedbackScores)
                        .retryWhen(AsyncUtils.handleConnectionError())
                        .then(Mono.just(feedbackScores.size()));
    }

    private void splitBatches(List<ExperimentItemBulkRecord> items,
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
                        .map(score -> FeedbackScoreMapper.INSTANCE.toFeedbackScoreBatchItem(trace.id(),
                                trace.projectName(), score))
                        .toList());
            }
        }
    }

}
