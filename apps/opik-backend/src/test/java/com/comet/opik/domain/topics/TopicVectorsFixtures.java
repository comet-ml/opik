package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabel;
import com.comet.opik.domain.ailabels.AiLabelStatus;
import com.comet.opik.infrastructure.inference.InferenceJson;
import lombok.experimental.UtilityClass;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The rows the topic vectors tests write and expect: statuses, outputs and vectors of a trace. */
@UtilityClass
class TopicVectorsFixtures {

    static final String TASK = TopicEmbedding.SUMMARY_TASK;
    // Vectors are compared whole. topic_vectors stores BFloat16, 8 bits of mantissa: a unit vector's components, read
    // back, are within about 2e-4 of what was written, so they are compared to within 1e-3.
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
                .task(TASK)
                .traceId(traceId)
                .traceCreatedAt(traceCreatedAt(traceId))
                .taskVersion(taskVersion)
                .embedModel(TopicEmbedding.MODEL)
                .embedDimensions(TopicEmbedding.DIMENSIONS)
                .text(text)
                .vector(expectedVector(text))
                .build();
    }

    static List<TopicVector> expectedVectors(String workspaceId, UUID projectId, List<UUID> traceIds,
            int taskVersion) {
        return traceIds.stream().map(traceId -> vector(workspaceId, projectId, traceId, taskVersion)).toList();
    }

    /** A deterministic, non-unit vector of {@link TopicEmbedding#DIMENSIONS} numbers per text. */
    static float[] rawVector(String text) {
        int seed = text.hashCode();
        var vector = new float[TopicEmbedding.DIMENSIONS];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (Math.floorMod(seed + i * 31, 1_000) + 1) / 100f;
        }
        return vector;
    }

    /** Whole vectors, in any order; the floats of {@code vector} as {@link #VECTORS} compares them. */
    static void assertVectors(List<TopicVector> actual, List<TopicVector> expected) {
        assertThat(actual).usingRecursiveFieldByFieldElementComparator(VECTORS)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /** 0 when both vectors have the same length and their floats agree to within 1e-3, else 1. */
    private static int compareVectors(Object actual, Object expected) {
        var left = (float[]) actual;
        var right = (float[]) expected;
        if (left.length != right.length) {
            return 1;
        }
        for (int i = 0; i < right.length; i++) {
            if (Math.abs(left[i] - right[i]) > 1e-3f) {
                return 1;
            }
        }
        return 0;
    }

    /** {@link #rawVector} of the text, scaled to length 1. */
    static float[] expectedVector(String text) {
        var raw = rawVector(text);
        double sum = 0;
        for (var value : raw) {
            sum += (double) value * value;
        }
        double norm = Math.sqrt(sum);
        var unit = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            unit[i] = (float) (raw[i] / norm);
        }
        return unit;
    }
}
