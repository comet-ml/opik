package com.comet.opik.api.resources.v1.events.tools;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TraceToolContextTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final long MAX = TraceToolContext.DEFAULT_MAX_INJECTED_BYTES; // 50 MB

    private TraceToolContext ctx() {
        return TraceToolContext.forThread("ws", "user", PROJECT_ID);
    }

    private static MediaPayload payload(long sizeBytes) {
        return MediaPayload.ofBase64("file.png", "image/png", MediaCategory.IMAGE, sizeBytes, "data");
    }

    // canInjectMedia ---

    @Test
    void canInjectMedia_allowsWhenBudgetIsEmpty() {
        assertThat(ctx().canInjectMedia(1L)).isTrue();
    }

    @Test
    void canInjectMedia_allowsExactlyAtLimit() {
        assertThat(ctx().canInjectMedia(MAX)).isTrue();
    }

    @Test
    void canInjectMedia_blocksWhenOneByteOverLimit() {
        assertThat(ctx().canInjectMedia(MAX + 1)).isFalse();
    }

    @Test
    void canInjectMedia_blocksZeroAfterBudgetFull() {
        var ctx = ctx();
        ctx.stageMedia(payload(MAX));
        // budget exactly exhausted — even zero more bytes must be blocked
        // (injectedBytes + 0 == MAX, which satisfies <=, so actually fine to allow 0)
        // The real guard is the next non-zero file:
        assertThat(ctx.canInjectMedia(1L)).isFalse();
    }

    @Test
    void canInjectMedia_allowsWhenRemainingBudgetSuffices() {
        var ctx = ctx();
        long firstChunk = MAX / 2;
        ctx.stageMedia(payload(firstChunk));
        assertThat(ctx.canInjectMedia(MAX / 2)).isTrue();
    }

    @Test
    void canInjectMedia_blocksWhenAccumulatedTotalExceedsLimit() {
        var ctx = ctx();
        long firstChunk = MAX / 2 + 1;
        ctx.stageMedia(payload(firstChunk));
        // remaining = MAX - firstChunk = MAX/2 - 1; asking for MAX/2 exceeds it
        assertThat(ctx.canInjectMedia(MAX / 2)).isFalse();
    }

    // stageMedia accumulation ---

    @Test
    void stageMedia_accumulatesBytesAcrossMultipleCalls() {
        var ctx = ctx();
        long half = MAX / 2;
        ctx.stageMedia(payload(half));
        // after first half: the remaining half still fits
        assertThat(ctx.canInjectMedia(half)).isTrue();
        ctx.stageMedia(payload(half));
        // after two halves (== MAX): no room left for even one more byte
        assertThat(ctx.canInjectMedia(1L)).isFalse();
    }

    @Test
    void stageMedia_pendingMediaIsCorrectlyTracked() {
        var ctx = ctx();
        var p1 = payload(1024);
        var p2 = payload(2048);
        ctx.stageMedia(p1);
        ctx.stageMedia(p2);
        assertThat(ctx.hasPendingMedia()).isTrue();
        var drained = ctx.drainPendingMedia();
        assertThat(drained).containsExactly(p1, p2);
        assertThat(ctx.hasPendingMedia()).isFalse();
    }

    @Test
    void stageMedia_drainDoesNotResetByteCounter() {
        var ctx = ctx();
        ctx.stageMedia(payload(MAX));
        ctx.drainPendingMedia();
        // bytes already counted; even after drain the budget should still be exhausted
        assertThat(ctx.canInjectMedia(1L)).isFalse();
    }
}
