package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.Builder;
import lombok.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * The traces of one night's window, in time order: intent text, unit vector ({@code dimensions} values each) and
 * creation time. {@code night} is the window's end (exclusive), {@code from} its start. The arrays are not copied (the
 * vectors of a large window run to a hundred megabytes and go whole to the assignment): callers must not change them.
 */
@Builder(toBuilder = true)
record NightWindow(@NonNull String[] texts, @NonNull float[] vectors, @NonNull Instant[] createdAt,
        @NonNull Instant from, @NonNull Instant night, int dimensions) {

    NightWindow {
        Preconditions.checkArgument(vectors.length == texts.length * dimensions && createdAt.length == texts.length,
                "per-trace arrays must have one entry per text, texts '%s'", texts.length);
    }

    int size() {
        return texts.length;
    }

    /** Per trace, whether it falls in the {@code days} days (24 hours each) before the night. */
    boolean[] recent(int days) {
        return since(night.minus(Duration.ofDays(days)));
    }

    /** Per trace, whether it was created at or after {@code instant}. */
    boolean[] since(@NonNull Instant instant) {
        var out = new boolean[size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = !createdAt[i].isBefore(instant);
        }
        return out;
    }

    /** The rows of {@code traces}, in that order. */
    float[] rows(@NonNull int[] traces) {
        var out = new float[traces.length * dimensions];
        for (int r = 0; r < traces.length; r++) {
            System.arraycopy(vectors, traces[r] * dimensions, out, r * dimensions, dimensions);
        }
        return out;
    }

    String[] texts(@NonNull int[] traces) {
        var out = new String[traces.length];
        for (int r = 0; r < traces.length; r++) {
            out[r] = texts[traces[r]];
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NightWindow other
                && dimensions == other.dimensions
                && from.equals(other.from)
                && night.equals(other.night)
                && Arrays.equals(texts, other.texts)
                && Arrays.equals(vectors, other.vectors)
                && Arrays.equals(createdAt, other.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(texts), Arrays.hashCode(vectors), Arrays.hashCode(createdAt), from, night,
                dimensions);
    }

    @Override
    public String toString() {
        return "NightWindow[traces=%s, dimensions=%s, from=%s, night=%s]".formatted(size(), dimensions, from, night);
    }
}
