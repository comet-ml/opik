package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Assigns traces to the nearest prototype. The prototypes are the topics' (each owned by a topic index) followed by
 * the known-Other ones; a known-Other prototype within cosine {@code otherGate} of any topic prototype is left out,
 * so it cannot pull a topic's traces into Other. A trace takes its nearest prototype's owner when the cosine is at least
 * {@code minCosine}, else Other ({@link TopicAlgorithm#OTHER}). Ties go to the lower prototype index. All vectors are
 * unit length, so the cosine is the dot product.
 *
 * <p>The prototypes are kept widened to double, and each trace row is widened once into a buffer before it meets
 * them: the cosines are {@link VectorMath#dot} of the float rows bit for bit, without converting the same trace value
 * again for every prototype.
 */
public class PrototypeAssigner {

    /** A trace with no prototype at {@code minCosine} or more has no winner. */
    static final int NO_WINNER = -1;

    private final double[] prototypes;
    private final int[] owners;
    private final int dimensions;
    private final double minCosine;
    private final int gatedOther;
    private final List<float[]> keptOther;
    /** Per kept known-Other prototype, its index among the known-Other prototypes given. */
    private final int[] keptOtherIndexes;

    /**
     * @param topicPrototypes per prototype, {@code dimensions} values, in prototype order
     * @param topicOwners     the topic index of each topic prototype
     * @param otherPrototypes the known-Other prototypes, in prototype order
     * @param minCosine       {@code topics.algorithm.assignMinCosine}
     * @param otherGate       {@code topics.algorithm.otherGate}
     */
    public PrototypeAssigner(@NonNull List<float[]> topicPrototypes, @NonNull List<Integer> topicOwners,
            @NonNull List<float[]> otherPrototypes, int dimensions, double minCosine, double otherGate) {
        Preconditions.checkArgument(topicPrototypes.size() == topicOwners.size(),
                "every topic prototype needs an owner");
        Stream.concat(topicPrototypes.stream(), otherPrototypes.stream())
                .forEach(prototype -> Preconditions.checkArgument(prototype.length == dimensions,
                        "a prototype must have '%s' values, got '%s'", dimensions, prototype.length));
        this.keptOtherIndexes = IntStream.range(0, otherPrototypes.size())
                .filter(o -> topicPrototypes.stream().noneMatch(
                        topic -> VectorMath.dot(otherPrototypes.get(o), 0, topic, 0, dimensions) >= otherGate))
                .toArray();
        var kept = Arrays.stream(keptOtherIndexes).mapToObj(otherPrototypes::get).toList();
        this.dimensions = dimensions;
        this.minCosine = minCosine;
        this.gatedOther = otherPrototypes.size() - kept.size();
        this.keptOther = kept;
        var all = Stream.concat(topicPrototypes.stream(), kept.stream()).toList();
        this.prototypes = new double[all.size() * dimensions];
        for (int p = 0; p < all.size(); p++) {
            var prototype = all.get(p);
            for (int t = 0; t < dimensions; t++) {
                prototypes[p * dimensions + t] = prototype[t];
            }
        }
        this.owners = IntStream.concat(topicOwners.stream().mapToInt(Integer::intValue),
                IntStream.generate(() -> TopicAlgorithm.OTHER).limit(kept.size())).toArray();
    }

    /** The owner per trace: a topic index or {@link TopicAlgorithm#OTHER}. */
    public int[] assign(@NonNull float[] vectors) {
        return owners(winners(vectors));
    }

    /**
     * {@link #assign} from cosines already computed with {@link #cosines}: {@code cosines[i * columns + c]} is trace
     * {@code i}'s cosine to row {@code c}, and the prototypes are rows of that same matrix, topic prototype {@code p}
     * row {@code topicColumns[p]} and known-Other prototype {@code o} row {@code otherColumns[o]}, in the order the
     * assigner was made with. The cosines are then the ones {@link #winners} computes, bit for bit, read in the same
     * prototype order, so the owners are {@link #assign}'s.
     */
    public int[] assign(@NonNull double[] cosines, int columns, @NonNull int[] topicColumns,
            @NonNull int[] otherColumns) {
        Preconditions.checkArgument(columns > 0 && cosines.length % columns == 0, "cosines must be whole rows");
        var columnOf = columnsOf(columns, topicColumns, otherColumns);
        int n = cosines.length / columns;
        var out = new int[n];
        for (int i = 0; i < n; i++) {
            int row = i * columns;
            int best = NO_WINNER;
            double bestCosine = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < columnOf.length; p++) {
                double cosine = cosines[row + columnOf[p]];
                if (cosine > bestCosine) {
                    bestCosine = cosine;
                    best = p;
                }
            }
            out[i] = bestCosine < minCosine ? NO_WINNER : best;
        }
        return owners(out);
    }

    /**
     * Each trace's cosine to each of {@code count} rows, {@code n * count} values, trace by trace: computed as
     * {@link #winners} computes a cosine (rows widened once, two rows per pass), so each value is the one
     * {@link #winners} gets for a prototype equal to that row.
     */
    static double[] cosines(@NonNull float[] vectors, @NonNull float[] rows, int count, int dimensions) {
        Preconditions.checkArgument(vectors.length % dimensions == 0, "vectors must be whole rows");
        Preconditions.checkArgument(rows.length == count * dimensions, "rows must hold '%s' rows", count);
        int n = vectors.length / dimensions;
        var wideRows = new double[rows.length];
        for (int v = 0; v < rows.length; v++) {
            wideRows[v] = rows[v];
        }
        var out = new double[Math.multiplyExact(n, count)];
        var pair = new double[2];
        var row = new double[dimensions];
        for (int i = 0; i < n; i++) {
            int offset = i * dimensions;
            for (int t = 0; t < dimensions; t++) {
                row[t] = vectors[offset + t];
            }
            int at = i * count;
            int c = 0;
            for (; c + 1 < count; c += 2) {
                VectorMath.dot2(row, 0, wideRows, c * dimensions, (c + 1) * dimensions, dimensions, pair);
                out[at + c] = pair[0];
                out[at + c + 1] = pair[1];
            }
            if (c < count) {
                out[at + c] = VectorMath.dot(row, 0, wideRows, c * dimensions, dimensions);
            }
        }
        return out;
    }

    /**
     * The winning prototype per trace: its index in prototype order (the topic prototypes in the given order, then
     * {@link #keptOther}), or {@link #NO_WINNER} below {@code minCosine}.
     */
    int[] winners(@NonNull float[] vectors) {
        Preconditions.checkArgument(vectors.length % dimensions == 0, "vectors must be whole rows");
        int n = vectors.length / dimensions;
        var out = new int[n];
        var cosines = new double[2];
        var row = new double[dimensions];
        int count = owners.length;
        for (int i = 0; i < n; i++) {
            int offset = i * dimensions;
            for (int t = 0; t < dimensions; t++) {
                row[t] = vectors[offset + t];
            }
            int best = NO_WINNER;
            double bestCosine = Double.NEGATIVE_INFINITY;
            int p = 0;
            // Two prototypes per pass read each trace value once; dot2's cosines are dot's bit for bit.
            for (; p + 1 < count; p += 2) {
                VectorMath.dot2(row, 0, prototypes, p * dimensions, (p + 1) * dimensions, dimensions, cosines);
                if (cosines[0] > bestCosine) {
                    bestCosine = cosines[0];
                    best = p;
                }
                if (cosines[1] > bestCosine) {
                    bestCosine = cosines[1];
                    best = p + 1;
                }
            }
            if (p < count) {
                double cosine = VectorMath.dot(row, 0, prototypes, p * dimensions, dimensions);
                if (cosine > bestCosine) {
                    bestCosine = cosine;
                    best = p;
                }
            }
            out[i] = bestCosine < minCosine ? NO_WINNER : best;
        }
        return out;
    }

    /**
     * Per prototype, in prototype order, its row of the matrix {@link #assign(double[], int, int[], int[])} reads: topic
     * prototype {@code p} is row {@code topicColumns[p]}, a kept known-Other prototype the row of its index among the
     * known-Other prototypes given. A gated known-Other prototype has no entry.
     */
    int[] columnsOf(int columns, @NonNull int[] topicColumns, @NonNull int[] otherColumns) {
        Preconditions.checkArgument(topicColumns.length + keptOtherIndexes.length == owners.length,
                "every topic prototype needs a column");
        var columnOf = new int[owners.length];
        System.arraycopy(topicColumns, 0, columnOf, 0, topicColumns.length);
        for (int o = 0; o < keptOtherIndexes.length; o++) {
            columnOf[topicColumns.length + o] = otherColumns[keptOtherIndexes[o]];
        }
        Arrays.stream(columnOf).forEach(column -> Preconditions.checkArgument(column >= 0 && column < columns,
                "a prototype column must be in [0, '%s'), got '%s'", columns, column));
        return columnOf;
    }

    /** The owner of prototype {@code prototype}: a topic index, or {@link TopicAlgorithm#OTHER} for a known-Other one. */
    int owner(int prototype) {
        return owners[prototype];
    }

    /** The owner per winner: a topic index, or {@link TopicAlgorithm#OTHER} for a known-Other prototype or no winner. */
    int[] owners(@NonNull int[] winners) {
        var out = new int[winners.length];
        for (int i = 0; i < winners.length; i++) {
            out[i] = winners[i] == NO_WINNER ? TopicAlgorithm.OTHER : owners[winners[i]];
        }
        return out;
    }

    int prototypeCount() {
        return owners.length;
    }

    /** How many known-Other prototypes the gate left out. */
    public int gatedOther() {
        return gatedOther;
    }

    /** The known-Other prototypes the gate kept, in prototype order: the ones an assignment can use. */
    public List<float[]> keptOther() {
        return keptOther;
    }
}
