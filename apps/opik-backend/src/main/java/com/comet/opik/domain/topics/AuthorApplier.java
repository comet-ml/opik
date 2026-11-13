package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the author answer into topics: topics with the same name merged first (see {@link #mergeSameNames}); topics ordered by the total size of their valid group ids (largest first, ties
 * in answer order); names that are a catch-all as a whole dropped ({@link #isCatchAllName}); the first
 * {@code maxTopics} kept; each takes the groups no earlier topic claimed, and one left with none creates nothing; every
 * unclaimed group is Other. The per-cluster answer lists each group once, so the order matters only for the same-name
 * merge and the cap.
 */
@UtilityClass
public class AuthorApplier {

    /**
     * A whole name that is a catch-all: a catch-all word, with at most a generic noun after it. The name must match
     * as a whole: a catch-all word inside a name is a real word there ("Mixed Martial Arts", "General Job Search").
     */
    static final Pattern CATCH_ALL_NAME = Pattern.compile(
            "(other|others|misc|miscellaneous|general|various|mixed)( (requests|queries|questions|inquiries|tasks|topics|items|things|stuff))?",
            Pattern.UNICODE_CHARACTER_CLASS);

    public static Applied apply(@NonNull AuthorAnswer answer, @NonNull List<GroupSummary> groups, int maxTopics) {
        int k = groups.size();
        var ordered = new ArrayList<>(mergeSameNames(answer.topics(), groups));
        ordered.sort(Comparator.comparingLong(topic -> -validSize(topic, groups)));
        var claimed = new boolean[k];
        var topics = new ArrayList<NewTopic>();
        int taken = 0;
        for (var topic : ordered) {
            // A blank name is no name: its groups go to Other, as a catch-all's do.
            if (isCatchAllName(topic.name()) || TopicText.pythonStrip(topic.name()).isEmpty()) {
                continue;
            }
            if (taken++ >= maxTopics) {
                break;
            }
            var mine = new LinkedHashSet<Integer>();
            for (int j : topic.clusters()) {
                if (j >= 0 && j < k && !claimed[j]) {
                    mine.add(j);
                }
            }
            if (mine.isEmpty()) {
                continue;
            }
            mine.forEach(j -> claimed[j] = true);
            topics.add(new NewTopic(TopicText.name(topic.name()), TopicText.pythonStrip(topic.definition()),
                    List.copyOf(mine)));
        }
        var other = new ArrayList<Integer>();
        for (int j = 0; j < k; j++) {
            if (!claimed[j]) {
                other.add(j);
            }
        }
        return new Applied(List.copyOf(topics), List.copyOf(other));
    }

    /**
     * Topics whose names are equal after {@link #nameKey} become one, at the place of the first of them in the answer.
     * It keeps the name and definition of its largest member (by {@link #validSize}, ties in answer order) and lists
     * the members' group ids, largest member first, each id once.
     */
    static List<AuthorAnswer.Topic> mergeSameNames(List<AuthorAnswer.Topic> topics, List<GroupSummary> groups) {
        var byName = new LinkedHashMap<String, List<AuthorAnswer.Topic>>();
        for (var topic : topics) {
            byName.computeIfAbsent(nameKey(topic.name()), key -> new ArrayList<>()).add(topic);
        }
        var out = new ArrayList<AuthorAnswer.Topic>(byName.size());
        for (var members : byName.values()) {
            if (members.size() == 1) {
                out.add(members.getFirst());
                continue;
            }
            var bySize = new ArrayList<>(members);
            bySize.sort(Comparator.comparingLong(topic -> -validSize(topic, groups)));
            var clusters = new LinkedHashSet<Integer>();
            bySize.forEach(topic -> clusters.addAll(topic.clusters()));
            var head = bySize.getFirst();
            out.add(new AuthorAnswer.Topic(head.name(), head.definition(), List.copyOf(clusters)));
        }
        return out;
    }

    /** Python's {@code " ".join(name.split()).lower()}: no outer whitespace, inner runs as one space, lower case. */
    static String nameKey(@NonNull String name) {
        var out = new StringBuilder(name.length());
        boolean space = false;
        for (int i = 0; i < name.length();) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (TopicText.isPythonSpace(cp)) {
                space = !out.isEmpty();
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.appendCodePoint(cp);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The name is a catch-all as a whole ({@link #CATCH_ALL_NAME}), normalised as {@link #nameKey}: "Other Requests"
     * is, with any run of spaces, tabs or no-break spaces inside; "General Job Search" is not.
     */
    public static boolean isCatchAllName(@NonNull String name) {
        return CATCH_ALL_NAME.matcher(nameKey(name)).matches();
    }

    /** Every listed valid id counts, a repeated one each time. */
    private static long validSize(AuthorAnswer.Topic topic, List<GroupSummary> groups) {
        long size = 0;
        for (int j : topic.clusters()) {
            if (j >= 0 && j < groups.size()) {
                size += groups.get(j).size();
            }
        }
        return size;
    }

    /** A topic to create, with its groups in the order the answer listed them. */
    public record NewTopic(@NonNull String name, @NonNull String definition, @NonNull List<Integer> groups) {
    }

    /** The new topics in creation order, and the groups that went to Other, ascending. */
    public record Applied(@NonNull List<NewTopic> topics, @NonNull List<Integer> other) {
    }
}
