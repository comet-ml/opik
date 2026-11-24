package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.EmbedRequest;
import com.comet.opik.infrastructure.inference.EmbedResponse;
import com.comet.opik.infrastructure.inference.EmbedResult;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceException;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("topic embedder")
class TopicEmbedderTest {

    private final Instant now = TopicsTestClock.randomInstant();
    private final TopicsTestClock clock = new TopicsTestClock(now);
    private final InferenceClient client = mock(InferenceClient.class);
    private final AtomicInteger calls = new AtomicInteger();
    private final TopicVectorsMetrics.Workspace workspace = TopicVectorsMetrics.Workspace
            .unnamed(UUID.randomUUID().toString());

    @Test
    @DisplayName("a refused chunk is sent again in halves only while MAX_SPLIT_CALLS lasts: the inputs refused alone get "
            + "a status row, the rest are refused for this tick only")
    void refusedChunkIsBounded() {
        when(client.embed(any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return Mono.error(refusal());
        });

        var outcome = embed(candidates(256, -1), 256, 50);

        assertThat(calls.get()).isEqualTo(1 + TopicEmbedder.MAX_SPLIT_CALLS);
        assertThat(outcome.skipped()).containsEntry(SkipReason.EMBED_REFUSED, 256).hasSize(1);
        assertThat(outcome.statuses()).hasSize(2)
                .allMatch(status -> status.reason() == SkipReason.EMBED_REFUSED);
    }

    @Test
    @DisplayName("one bad input in a refused chunk costs only itself: the others get their vectors")
    void oneBadInput() {
        when(client.embed(any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            EmbedRequest request = invocation.getArgument(0);
            if (request.inputs().contains("bad")) {
                return Mono.error(refusal());
            }
            var embeddings = request.inputs().stream().map(input -> unitVector()).toArray(float[][]::new);
            return Mono.just(new EmbedResponse(new EmbedResult(embeddings, TopicEmbedding.DIMENSIONS)));
        });
        var candidates = candidates(8, 5);

        var outcome = embed(candidates, 8, 50);

        assertThat(outcome.vectors()).hasSize(7);
        assertThat(outcome.statuses()).extracting(TopicVectorStatus::candidate).containsExactly(candidates.get(5));
        assertThat(outcome.skipped()).containsExactly(Map.entry(SkipReason.EMBED_REFUSED, 1));
        // 8, then 4 + 4, then 2 + 2 of the bad half, then 1 + 1 of the bad pair.
        assertThat(calls.get()).isEqualTo(7);
    }

    @Test
    @DisplayName("each call of the halving needs embedTimeout left: the halves the budget doesn't reach are over_budget")
    void halvingChecksTheBudget() {
        when(client.embed(any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            clock.advance(java.time.Duration.ofSeconds(3));
            return Mono.error(refusal());
        });

        // 5s, 1s embed timeout, every call 3s: the chunk's call and its first half's; nothing after.
        var outcome = embed(candidates(4, -1), 4, 5);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(outcome.skipped()).containsExactly(Map.entry(SkipReason.OVER_BUDGET, 4));
        assertThat(outcome.statuses()).isEmpty();
    }

    private ChunkOutcome embed(List<Candidate> candidates, int batchSize, int budgetSeconds) {
        var vectors = new TopicsConfig.VectorsConfig();
        vectors.setEmbedBatchSize(batchSize);
        vectors.setEmbedTimeout(Duration.seconds(1));
        var config = new TopicsConfig();
        config.setVectors(vectors);
        var embedder = new TopicEmbedder(config, client, mock(TopicVectorsMetrics.class));
        return embedder.embed(workspace, candidates, TickBudget.of(now, java.time.Duration.ofSeconds(budgetSeconds),
                clock))
                .reduce(ChunkOutcome.EMPTY, ChunkOutcome::plus)
                .block();
    }

    /** {@code count} candidates; the one at {@code bad} (none when negative) has the summary "bad". */
    private List<Candidate> candidates(int count, int bad) {
        return IntStream.range(0, count)
                .mapToObj(i -> Candidate.builder().workspaceId(workspace.id()).projectId(UUID.randomUUID())
                        .traceId(UUID.randomUUID()).traceCreatedAt(now).taskVersion(1).statusUpdatedAt(now)
                        .summary(i == bad ? "bad" : "summary " + i).build())
                .toList();
    }

    private static InferenceException refusal() {
        var exception = mock(InferenceException.class);
        when(exception.getHttpStatus()).thenReturn(400);
        when(exception.getCode()).thenReturn("invalid_request");
        // The class's constructors are the client's; a mock that logs like a real exception does.
        when(exception.getStackTrace()).thenReturn(new StackTraceElement[0]);
        when(exception.getSuppressed()).thenReturn(new Throwable[0]);
        return exception;
    }

    private static float[] unitVector() {
        var vector = new float[TopicEmbedding.DIMENSIONS];
        Arrays.fill(vector, 1f);
        return vector;
    }
}
