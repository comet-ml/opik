package com.comet.opik.infrastructure;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.SpanWeek;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.utils.AsyncUtils;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.awaitility.Awaitility;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures for the span_weeks read tests, used by composition: a trace whose spans sit in this week and in a far-future
 * week, the experiment over it, and the wait for the index to hold every span's week. The index is read through
 * {@link SpanService}, the expected weeks are computed from each span id's own timestamp.
 */
@RequiredArgsConstructor
class SpanWeeksReadFixtures {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    /** Where ids past 2300 are stored. */
    private static final long PAST_CEILING_WEEK = 22991225L;

    private final @NonNull String apiKey;
    private final @NonNull String workspaceName;
    private final @NonNull String workspaceId;
    private final @NonNull String user;
    private final @NonNull PodamFactory factory;
    private final @NonNull ProjectResourceClient projectResourceClient;
    private final @NonNull TraceResourceClient traceResourceClient;
    private final @NonNull SpanResourceClient spanResourceClient;
    private final @NonNull DatasetResourceClient datasetResourceClient;
    private final @NonNull ExperimentResourceClient experimentResourceClient;
    private final @NonNull SpanService spanService;

    /** A trace with one span this week and one far in the future, both registered in span_weeks. */
    @Builder(toBuilder = true)
    record FarFutureTrace(String projectName, UUID projectId, Trace trace, List<Span> spans) {

        BigDecimal totalCost() {
            return spans.stream().map(Span::totalEstimatedCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        Map<String, Long> totalUsage() {
            return spans.stream()
                    .flatMap(span -> span.usage().entrySet().stream())
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().longValue(),
                            Long::sum));
        }
    }

    /** An experiment over one trace, with the dataset and item it was created on. */
    @Builder(toBuilder = true)
    record TraceExperiment(Experiment experiment, UUID datasetId, DatasetItem datasetItem) {
    }

    FarFutureTrace createFarFutureTrace() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
        // ClickHouse keeps microseconds, and the CI JVM clock has nanoseconds.
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(now.toEpochMilli()))
                .projectName(projectName)
                .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(16))
                .startTime(now)
                .endTime(now.plusMillis(RandomUtils.secure().randomInt(10, 1_000)))
                .feedbackScores(null)
                .spanFeedbackScores(null)
                .guardrailsValidations(null)
                .comments(null)
                .usage(null)
                .build();
        traceResourceClient.createTrace(trace, apiKey, workspaceName);
        var spans = Stream.of(now, farFutureInstant())
                .map(idAt -> factory.manufacturePojo(Span.class).toBuilder()
                        .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                        .projectName(projectName)
                        .traceId(trace.id())
                        .parentSpanId(null)
                        .startTime(now)
                        .endTime(null)
                        .feedbackScores(null)
                        .totalEstimatedCost(BigDecimal.valueOf(RandomUtils.secure().randomInt(1, 100_000), 2))
                        .build())
                .toList();
        spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);
        awaitIndexed(projectId, trace.id(), spans);
        return FarFutureTrace.builder().projectName(projectName).projectId(projectId).trace(trace).spans(spans)
                .build();
    }

    /** Creates an experiment over the trace, optionally under an optimization. */
    TraceExperiment createExperiment(UUID traceId, UUID optimizationId) {
        var datasetName = "dataset-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var datasetItem = factory.manufacturePojo(DatasetItem.class).toBuilder()
                .source(DatasetItemSource.SDK)
                .traceId(null)
                .spanId(null)
                .experimentItems(null)
                .build();
        datasetResourceClient.createDatasetItems(DatasetItemBatch.builder()
                .datasetName(datasetName)
                .items(List.of(datasetItem))
                .build(), workspaceName, apiKey);
        var experiment = experimentResourceClient.createPartialExperiment()
                .datasetName(datasetName)
                .optimizationId(optimizationId)
                .build();
        var experimentId = experimentResourceClient.create(experiment, apiKey, workspaceName);
        experimentResourceClient.createExperimentItem(Set.of(ExperimentItem.builder()
                .experimentId(experimentId)
                .datasetItemId(datasetItem.id())
                .traceId(traceId)
                .build()), apiKey, workspaceName);
        var created = experimentResourceClient.getExperiment(experimentId, apiKey, workspaceName);
        return TraceExperiment.builder()
                // The dataset version is set by the server when the items are created; it is not span-derived.
                .experiment(experiment.toBuilder()
                        .id(experimentId)
                        .datasetVersionId(created.datasetVersionId())
                        .datasetVersionSummary(created.datasetVersionSummary())
                        .build())
                .datasetId(created.datasetId())
                .datasetItem(datasetItem)
                .build();
    }

    /** Registration is asynchronous by design; waits until the index holds every span's week. */
    private void awaitIndexed(UUID projectId, UUID traceId, List<Span> spans) {
        var expected = spans.stream()
                .map(span -> SpanWeek.builder().projectId(projectId).traceId(traceId).idWeek(week(span.id())).build())
                .distinct()
                .toList();
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(spanService.getWeeksByTraceIds(Set.of(traceId))
                        .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, user, workspaceId))
                        .block())
                        .containsExactlyInAnyOrderElementsOf(expected));
    }

    /** A span's week from its own v7 timestamp, independently of the production week calculation. */
    private static long week(UUID spanId) {
        var monday = LocalDate.ofInstant(Instant.ofEpochMilli(spanId.getMostSignificantBits() >>> 16), ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return Math.min(Long.parseLong(monday.format(DateTimeFormatter.BASIC_ISO_DATE)), PAST_CEILING_WEEK);
    }

    /** Past every real clock, short of where {@code id_at} saturates. */
    private static Instant farFutureInstant() {
        return LocalDate.of(RandomUtils.secure().randomInt(2150, 2290), 1, 1)
                .plusDays(RandomUtils.secure().randomInt(0, 365))
                .atTime(12, 0)
                .toInstant(ZoneOffset.UTC);
    }
}
