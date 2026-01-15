package com.comet.opik.api.resources.utils;

import lombok.experimental.UtilityClass;

import java.util.Comparator;
import java.util.List;

/**
 * Utility class providing comparators for List objects.
 * These comparators perform lexicographic comparison element by element,
 * matching the behavior of ClickHouse array comparisons.
 */
@UtilityClass
public class ListComparators {

    /**
     * Returns a comparator that performs lexicographic comparison of two lists in ascending order.
     * Compares lists element by element using natural ordering of the elements.
     * If all compared elements are equal, the list with fewer elements is considered smaller.
     *
     * @param <T> the type of elements in the lists, must be Comparable
     * @return a comparator for ascending list comparison
     */
    public static <T extends Comparable<T>> Comparator<List<T>> ascending() {
        return (list1, list2) -> {
            int minSize = Math.min(list1.size(), list2.size());
            for (int i = 0; i < minSize; i++) {
                int cmp = list1.get(i).compareTo(list2.get(i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(list1.size(), list2.size());
        };
    }

    /**
     * Returns a comparator that performs lexicographic comparison of two lists in descending order.
     * Compares lists element by element in reverse order using natural ordering of the elements.
     * If all compared elements are equal, the list with more elements is considered smaller.
     *
     * @param <T> the type of elements in the lists, must be Comparable
     * @return a comparator for descending list comparison
     */
    public static <T extends Comparable<T>> Comparator<List<T>> descending() {
        return (list1, list2) -> {
            int minSize = Math.min(list1.size(), list2.size());
            for (int i = 0; i < minSize; i++) {
                int cmp = list2.get(i).compareTo(list1.get(i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(list2.size(), list1.size());
        };
    }
}