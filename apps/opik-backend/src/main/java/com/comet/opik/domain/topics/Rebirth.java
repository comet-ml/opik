package com.comet.opik.domain.topics;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Step j2 of the nightly update, after the decisions become prototypes: a topic born tonight whose traces belong to a
 * dormant topic takes that topic's identity, so a theme that comes back keeps its id and name instead of getting a
 * second topic. The night's decisions do not change: the newborn's prototypes and traces move to the dormant topic.
 * <p>
 * The newborn's traces are its members in tonight's final assignment (as step k). Each goes to its nearest dormant
 * prototype at cosine 0.75. When one dormant topic takes at least half of them, and at least {@value #MIN_TRACES}, it is
 * live again with the newborn's prototypes, and the newborn leaves the list. The largest newborn goes first, and a
 * dormant topic comes back at most once.
 */
@UtilityClass
public class Rebirth {

    static final int MIN_TRACES = 5;

    /**
     * @return the moves made, each {child, parent}: the newborn's index before it left the list and the index of the
     *         dormant topic that took its place, in descending child order
     */
    public static List<int[]> apply(@NonNull NightState state, @NonNull NightWindow window) {
        var dormant = state.dormant();
        var newborn = new ArrayList<Integer>();
        for (int t : state.live()) {
            if (state.topic(t).getBorn().equals(state.getNight())) {
                newborn.add(t);
            }
        }
        if (dormant.isEmpty() || newborn.isEmpty()) {
            return List.of();
        }
        var vectors = new ArrayList<float[]>();
        var owners = new ArrayList<Integer>();
        for (int t : dormant) {
            for (var prototype : state.topic(t).getPrototypes()) {
                vectors.add(prototype);
                owners.add(t);
            }
        }
        var assigner = new PrototypeAssigner(vectors, owners, List.of(), window.dimensions());
        var labels = NightlyUpdate.assignFinal(state, window);
        var sizes = new int[state.getTopics().size()];
        for (int label : labels) {
            if (label >= 0) {
                sizes[label]++;
            }
        }
        newborn.sort(Comparator.comparingInt((Integer t) -> -sizes[t]).thenComparingInt(t -> t));
        var taken = new HashSet<Integer>();
        var moves = new ArrayList<int[]>();
        for (int child : newborn) {
            var members = NightlyUpdate.members(labels, child);
            if (members.length < MIN_TRACES) {
                continue;
            }
            var counts = new int[state.getTopics().size()];
            for (int owner : assigner.assign(window.rows(members))) {
                if (owner >= 0) {
                    counts[owner]++;
                }
            }
            int parent = -1;
            for (int t : dormant) {
                if (!taken.contains(t) && (parent < 0 || counts[t] > counts[parent])) {
                    parent = t;
                }
            }
            if (parent >= 0 && counts[parent] >= MIN_TRACES && 2 * counts[parent] >= members.length) {
                taken.add(parent);
                moves.add(new int[]{child, parent});
            }
        }
        moves.sort(Comparator.comparingInt((int[] move) -> -move[0]));
        moves.forEach(move -> state.inherit(move[0], move[1]));
        return moves;
    }

    /** An index of the list before the moves, as it is after them; a child's is its parent's. */
    public static int remap(int index, @NonNull List<int[]> moves) {
        int out = index;
        for (var move : moves) {
            if (out == move[0]) {
                out = move[1];
            } else if (out > move[0]) {
                out--;
            }
        }
        return out;
    }
}
