package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabel;
import com.comet.opik.domain.ailabels.AiLabelStatus;
import com.comet.opik.infrastructure.inference.InferenceJson;
import lombok.experimental.UtilityClass;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The rows the topic vectors tests write and expect: statuses, outputs and vectors of a trace. */
@UtilityClass
class TopicVectorsFixtures {

    /** The ai-labels task whose summaries are embedded. */
    static final String TASK = "trace_summary";
    // Vectors are compared whole. ClickHouse parses the JSON text of a Float32 to within one ulp, so a read-back vector
    // may differ in the last bit: its floats are compared to within 1e-6.
    static final RecursiveComparisonConfiguration VECTORS = RecursiveComparisonConfiguration.builder()
            .withComparatorForFields(TopicVectorsFixtures::compareVectors, "vector")
            .build();

    static AiLabelStatus doneStatus(String workspaceId, UUID projectId, UUID traceId, int taskVersion) {
        return AiLabelStatus.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .task(TASK)
                .traceId(traceId)
                .traceCreatedAt(traceCreatedAt(traceId))
                .taskVersion(taskVersion)
                .rendererVersion(1)
                .status(AiLabelStatus.Status.DONE)
                .retryable(false)
                .attempt(1)
                .errorCode("")
                .jobId("job")
                .compactionLevel("none")
                .build();
    }

    static AiLabel outputLabel(String workspaceId, UUID projectId, UUID traceId, int taskVersion) {
        return outputLabel(workspaceId, projectId, traceId, taskVersion, summaryOf(traceId, taskVersion));
    }

    static AiLabel outputLabel(String workspaceId, UUID projectId, UUID traceId, int taskVersion, String summary) {
        var output = InferenceJson.MAPPER.createObjectNode().put("summary", summary);
        return AiLabel.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .task(TASK)
                .traceId(traceId)
                .key(AiLabel.OUTPUT_KEY)
                .taskVersion(taskVersion)
                .answerType(AiLabel.AnswerType.OUTPUT)
                .probabilities(Map.of())
                .legend(Map.of())
                .output(output.toString())
                .build();
    }

    static String summaryOf(UUID traceId, int taskVersion) {
        return "Intent of trace %s, version %d".formatted(traceId, taskVersion);
    }

    /**
     * The trace's created_at in its statuses: an hour before its id's time, so it is neither the id's time nor the
     * status's write time, and the same on every row of the trace.
     */
    static Instant traceCreatedAt(UUID traceId) {
        return Instant.ofEpochMilli(traceId.getMostSignificantBits() >>> 16).minus(1, ChronoUnit.HOURS);
    }

    /** The row the tick writes for a trace with {@link #outputLabel}: its summary, embedded and scaled to length 1. */
    static TopicVector vector(String workspaceId, UUID projectId, UUID traceId, int taskVersion) {
        var text = summaryOf(traceId, taskVersion);
        return TopicVector.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(traceCreatedAt(traceId))
                .taskVersion(taskVersion)
                .embedModel("bge-small-en-v1.5")
                .embedDimensions(384)
                .text(text)
                .vector(expectedVector(text))
                .build();
    }

    static List<TopicVector> expectedVectors(String workspaceId, UUID projectId, List<UUID> traceIds,
            int taskVersion) {
        return traceIds.stream().map(traceId -> vector(workspaceId, projectId, traceId, taskVersion)).toList();
    }

    /** A deterministic, non-unit vector of 384 numbers per text. */
    static List<Float> rawVector(String text) {
        int seed = text.hashCode();
        return IntStream.range(0, 384)
                .mapToObj(i -> (float) (Math.floorMod(seed + i * 31, 1_000) + 1) / 100f)
                .toList();
    }

    /** Whole vectors, in any order; the floats of {@code vector} as {@link #VECTORS} compares them. */
    static void assertVectors(List<TopicVector> actual, List<TopicVector> expected) {
        assertThat(actual).usingRecursiveFieldByFieldElementComparator(VECTORS)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /** 0 when both vectors have the same length and their floats agree to within 1e-6, else 1. */
    @SuppressWarnings("unchecked")
    private static int compareVectors(Object actual, Object expected) {
        var left = (List<Float>) actual;
        var right = (List<Float>) expected;
        if (left.size() != right.size()) {
            return 1;
        }
        for (int i = 0; i < right.size(); i++) {
            if (Math.abs(left.get(i) - right.get(i)) > 1e-6f) {
                return 1;
            }
        }
        return 0;
    }

    /** {@link #rawVector} of the text, scaled to length 1. */
    private static List<Float> expectedVector(String text) {
        var raw = rawVector(text);
        double sum = 0;
        for (var value : raw) {
            sum += (double) value * value;
        }
        double norm = Math.sqrt(sum);
        var unit = new ArrayList<Float>(raw.size());
        for (var value : raw) {
            unit.add((float) (value / norm));
        }
        return unit;
    }
}
