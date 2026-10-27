package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.Builder;
import lombok.NonNull;

import java.time.Duration;
import java.time.Instant;

/**
 * The traces of one night's window, in time order: intent text, unit vector ({@code dimensions} values each) and
 * creation time. {@code night} is the window's end (exclusive), {@code from} its start.
 */
@Builder
public record NightWindow(@NonNull String[] texts, @NonNull float[] vectors, @NonNull Instant[] createdAt,
        @NonNull Instant from, @NonNull Instant night, int dimensions) {

    static final Duration RECENT = Duration.ofDays(7);

    public NightWindow {
        Preconditions.checkArgument(vectors.length == texts.length * dimensions && createdAt.length == texts.length,
                "per-trace arrays must have one entry per text, texts '%s'", texts.length);
    }

    public int size() {
        return texts.length;
    }

    /** Per trace, whether it falls in the 7 calendar days before the night. */
    public boolean[] recent() {
        return since(night.minus(RECENT));
    }

    /** Per trace, whether it was created at or after {@code instant}. */
    public boolean[] since(@NonNull Instant instant) {
        var out = new boolean[size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = !createdAt[i].isBefore(instant);
        }
        return out;
    }

    /** The rows of {@code traces}, in that order. */
    public float[] rows(@NonNull int[] traces) {
        var out = new float[traces.length * dimensions];
        for (int r = 0; r < traces.length; r++) {
            System.arraycopy(vectors, traces[r] * dimensions, out, r * dimensions, dimensions);
        }
        return out;
    }

    public String[] texts(@NonNull int[] traces) {
        var out = new String[traces.length];
        for (int r = 0; r < traces.length; r++) {
            out[r] = texts[traces[r]];
        }
        return out;
    }
}
