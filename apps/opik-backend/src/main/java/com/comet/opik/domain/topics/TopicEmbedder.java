package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.EmbedRequest;
import com.comet.opik.infrastructure.inference.EmbedResponse;
import com.comet.opik.infrastructure.inference.EmbedResult;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.google.common.collect.Lists;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embeds one workspace's summaries with {@code /v1/embed}, in chunks of {@code embedBatchSize}, and turns the answers
 * into unit-length vectors. A chunk starts only with at least {@code embedTimeout} left in the tick, so a call never
 * ends on the tick's deadline. After a 429, a 5xx, a timeout or a retryable error, no further chunk of the workspace is
 * sent in the tick.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicEmbedder {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull InferenceClient client;
    private final @NonNull TopicVectorsMetrics metrics;

    /**
     * Each chunk's outcome, in order. A chunk is embedded on the caller's thread when the caller asks for it, so the
     * caller writes one chunk before the next call starts.
     */
    Iterable<ChunkOutcome> embed(@NonNull String workspaceId, @NonNull List<Candidate> candidates,
            @NonNull TickBudget budget) {
        var chunks = Lists.partition(candidates, config.getVectors().getEmbedBatchSize());
        var backedOff = new AtomicBoolean();
        return () -> chunks.stream().map(chunk -> embedChunk(workspaceId, chunk, budget, backedOff)).iterator();
    }

    private ChunkOutcome embedChunk(String workspaceId, List<Candidate> chunk, TickBudget budget,
            AtomicBoolean backedOff) {
        var timeout = config.getVectors().getEmbedTimeout().toJavaDuration();
        if (backedOff.get()) {
            return ChunkOutcome.skipped(SkipReason.EMBED_FAILED, chunk.size());
        }
        if (!budget.hasTimeFor(timeout)) {
            return ChunkOutcome.skipped(SkipReason.OVER_BUDGET, chunk.size());
        }
        var request = EmbedRequest.builder()
                .orgId(workspaceId)
                // opik-inference's own default for embed: vectors are large and a summary is embedded once, so a cached
                // answer would only take cache space.
                .cache(false)
                .inputs(chunk.stream().map(Candidate::summary).toList())
                .build();
        long started = System.nanoTime();
        EmbedResponse response;
        try {
            response = client.embed(request, budget.clock().instant().plus(timeout)).block();
        } catch (InferenceException exception) {
            metrics.embedCall(workspaceId, Duration.ofNanos(System.nanoTime() - started), exception);
            if (backsOff(exception)) {
                backedOff.set(true);
                log.warn("Topic embed failed, workspace '{}', code '{}', status '{}', traces '{}'; no more calls for "
                        + "it this tick", workspaceId, exception.getCode(), exception.getHttpStatus(), chunk.size(),
                        exception);
            } else {
                // The service refused what was sent: the next tick sends the same chunk, so this needs a look.
                log.error("Topic embed rejected a chunk, workspace '{}', code '{}', status '{}', traces '{}'",
                        workspaceId, exception.getCode(), exception.getHttpStatus(), chunk.size(), exception);
            }
            return ChunkOutcome.skipped(SkipReason.EMBED_FAILED, chunk.size());
        }
        metrics.embedCall(workspaceId, Duration.ofNanos(System.nanoTime() - started), null);
        return vectors(workspaceId, chunk, response);
    }

    /** Whether to stop calling for this workspace for the rest of the tick. */
    static boolean backsOff(@NonNull InferenceException exception) {
        int status = exception.getHttpStatus();
        return exception.isRetryable() || exception.isOutOfTime() || status == 429 || status >= 500;
    }

    /** A malformed answer costs only its own traces, never an exception out of the tick. */
    private static ChunkOutcome vectors(String workspaceId, List<Candidate> chunk, EmbedResponse response) {
        var embeddings = Optional.ofNullable(response).map(EmbedResponse::result).map(EmbedResult::embeddings)
                .orElse(null);
        if (embeddings == null || embeddings.size() != chunk.size()) {
            log.error("Topic vectors embed answered '{}' vectors for '{}' inputs, workspace '{}'",
                    embeddings == null ? 0 : embeddings.size(), chunk.size(), workspaceId);
            return ChunkOutcome.skipped(SkipReason.INVALID_VECTOR, chunk.size());
        }
        var vectors = new ArrayList<TopicVector>(chunk.size());
        for (int i = 0; i < chunk.size(); i++) {
            var candidate = chunk.get(i);
            toArray(embeddings.get(i)).flatMap(TopicEmbedder::normalize).ifPresent(vector -> vectors.add(
                    TopicVector.builder()
                            .workspaceId(candidate.workspaceId())
                            .projectId(candidate.projectId())
                            .task(TopicEmbedding.SUMMARY_TASK)
                            .traceId(candidate.traceId())
                            .traceCreatedAt(candidate.traceCreatedAt())
                            .taskVersion(candidate.taskVersion())
                            .embedModel(TopicEmbedding.MODEL)
                            .embedDimensions(TopicEmbedding.DIMENSIONS)
                            .text(candidate.summary())
                            .vector(vector)
                            .build()));
        }
        int invalid = chunk.size() - vectors.size();
        if (invalid > 0) {
            log.error("Topic vectors embed answered '{}' vectors that are not '{}' finite non-zero numbers, "
                    + "workspace '{}'", invalid, TopicEmbedding.DIMENSIONS, workspaceId);
        }
        return new ChunkOutcome(vectors, invalid > 0 ? Map.of(SkipReason.INVALID_VECTOR, invalid) : Map.of());
    }

    private static Optional<float[]> toArray(List<Float> embedding) {
        if (embedding == null || embedding.size() != TopicEmbedding.DIMENSIONS
                || !embedding.stream().allMatch(Objects::nonNull)) {
            return Optional.empty();
        }
        var array = new float[embedding.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = embedding.get(i);
        }
        return Optional.of(array);
    }

    /** Scaled to length 1, so the nightly job's dot product is the cosine; empty for a zero or non-finite vector. */
    static Optional<float[]> normalize(float @NonNull [] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0 || !Double.isFinite(norm)) {
            return Optional.empty();
        }
        var normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return Optional.of(normalized);
    }
}
