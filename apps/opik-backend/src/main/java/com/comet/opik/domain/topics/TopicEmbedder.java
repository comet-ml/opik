package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.EmbedRequest;
import com.comet.opik.infrastructure.inference.EmbedResponse;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.google.common.collect.Lists;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Embeds one workspace's summaries with {@code /v1/embed}, in chunks of {@code embedBatchSize}, one call at a time and in
 * order, and turns the answers into unit-length vectors. A chunk starts only with at least {@code embedTimeout} left in
 * the tick, so a call never ends on the tick's deadline. A 429, a 5xx, a timeout, a retryable error, a 401 or a 403
 * ends the workspace's calls for the tick: the failed chunk and every chunk after it are {@code embed_failed}.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicEmbedder {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull InferenceClient client;
    private final @NonNull TopicVectorsMetrics metrics;

    /** Each chunk's outcome, in order. Every candidate is in exactly one outcome. */
    Flux<ChunkOutcome> embed(@NonNull TopicVectorsMetrics.Workspace workspace, @NonNull List<Candidate> candidates,
            @NonNull TickBudget budget) {
        int size = config.getVectors().getEmbedBatchSize();
        var chunks = Lists.partition(candidates, size);
        return Flux.range(0, chunks.size())
                .concatMap(index -> embedChunk(workspace, index, chunks.get(index), budget))
                .onErrorResume(BackedOff.class, backedOff -> Mono.just(ChunkOutcome.skipped(SkipReason.EMBED_FAILED,
                        candidates.size() - backedOff.index * size)));
    }

    private Mono<ChunkOutcome> embedChunk(TopicVectorsMetrics.Workspace workspace, int index, List<Candidate> chunk,
            TickBudget budget) {
        return Mono.defer(() -> {
            var workspaceId = workspace.id();
            var timeout = config.getVectors().getEmbedTimeout().toJavaDuration();
            if (!budget.hasTimeFor(timeout)) {
                return Mono.just(ChunkOutcome.skipped(SkipReason.OVER_BUDGET, chunk.size()));
            }
            var request = EmbedRequest.builder()
                    .orgId(workspaceId)
                    // opik-inference's own default for embed: vectors are large and a summary is embedded once, so a
                    // cached answer would only take cache space.
                    .cache(false)
                    .inputs(chunk.stream().map(Candidate::summary).toList())
                    .build();
            long started = System.nanoTime();
            return client.embed(request, budget.clock().instant().plus(timeout))
                    .map(response -> {
                        metrics.embedCall(workspace, Duration.ofNanos(System.nanoTime() - started), null);
                        return vectors(workspaceId, chunk, response);
                    })
                    .onErrorResume(InferenceException.class, exception -> {
                        metrics.embedCall(workspace, Duration.ofNanos(System.nanoTime() - started), exception);
                        if (backsOff(exception)) {
                            log.warn("Topic embed failed, workspace '{}', code '{}', status '{}', traces '{}'; no more "
                                    + "calls for it this tick", workspaceId, exception.getCode(),
                                    exception.getHttpStatus(), chunk.size(), exception);
                            return Mono.error(new BackedOff(index));
                        }
                        // What is left is invalid_request or input_too_long, a refusal of the inputs. The window read
                        // sends no blank summary, trace_summary caps one at 160 characters, and /v1/embed has no
                        // per-input length limit, so this is a service-side change that needs a look. Sending the same
                        // chunk every tick would get the same answer, so the watermark passes it; the daily pass tries
                        // it again.
                        log.error("Topic embed rejected a chunk, workspace '{}', code '{}', status '{}', traces '{}'",
                                workspaceId, exception.getCode(), exception.getHttpStatus(), chunk.size(), exception);
                        return Mono.just(ChunkOutcome.refused(chunk));
                    });
        });
    }

    /**
     * Whether to stop calling for this workspace for the rest of the tick: the service is busy or failing, or it
     * refuses the workspace itself, so the next chunk would get the same answer.
     */
    static boolean backsOff(@NonNull InferenceException exception) {
        int status = exception.getHttpStatus();
        return exception.isRetryable() || exception.isOutOfTime() || status == 401 || status == 403 || status == 429
                || status >= 500;
    }

    /** Ends a workspace's chunk stream; {@code index} is the index of the chunk whose call backed off. */
    private static final class BackedOff extends RuntimeException {

        private final int index;

        private BackedOff(int index) {
            super(null, null, false, false);
            this.index = index;
        }
    }

    /**
     * The client has checked one vector per input, each {@code dimensions} long; a model of another size or a zero or
     * non-finite vector costs only its own traces, never an exception out of the tick.
     */
    private static ChunkOutcome vectors(String workspaceId, List<Candidate> chunk, EmbedResponse response) {
        var result = response.result();
        if (result.dimensions() != TopicEmbedding.DIMENSIONS) {
            log.error("Topic vectors embed answered dimensions '{}', expected '{}', workspace '{}'",
                    result.dimensions(), TopicEmbedding.DIMENSIONS, workspaceId);
            // The model, not the inputs: a later tick, once the service is fixed, embeds them.
            return ChunkOutcome.skipped(SkipReason.INVALID_VECTOR, chunk.size());
        }
        var vectors = new ArrayList<TopicVector>(chunk.size());
        var invalidCandidates = new ArrayList<Candidate>();
        for (int i = 0; i < chunk.size(); i++) {
            var candidate = chunk.get(i);
            var normalized = normalize(result.embeddings()[i]);
            if (normalized.isEmpty()) {
                invalidCandidates.add(candidate);
            }
            normalized.ifPresent(vector -> vectors.add(
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
        int invalid = invalidCandidates.size();
        if (invalid > 0) {
            log.error("Topic vectors embed answered '{}' zero or non-finite vectors, workspace '{}'", invalid,
                    workspaceId);
        }
        // The same summary gets the same vector, so these keys are settled.
        return new ChunkOutcome(vectors, invalid > 0 ? Map.of(SkipReason.INVALID_VECTOR, invalid) : Map.of(),
                ChunkOutcome.keys(invalidCandidates));
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
