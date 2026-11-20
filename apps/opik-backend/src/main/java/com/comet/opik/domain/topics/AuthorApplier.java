package com.comet.opik.domain.topics;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Turns the author answer into topics: topics with the same name merged first (see {@link #mergeSameNames}); topics
 * ordered by the total size of their valid group ids (largest first, ties in answer order); names that are a catch-all
 * as a whole, or blank, dropped ({@link #isCatchAllName}); each topic takes the groups no earlier topic claimed, one
 * left with none creates nothing, and creation stops at {@code maxTopics} topics; every unclaimed group is Other. The
 * answer lists each group once, so the order matters only for the same-name merge and the cap.
 */
@UtilityClass
public class AuthorApplier {

    /**
     * A whole name that is a catch-all: a catch-all word, with at most a generic noun after it. The name must match
     * as a whole: a catch-all word inside a name is a real word there ("Mixed Martial Arts", "General Job Search").
     * English only: the author names topics after English summaries, and its prompt forbids such names; this catches
     * the answers that ignore it.
     */
    private static final Pattern CATCH_ALL_NAME = Pattern.compile(
            "(other|others|misc|miscellaneous|general|various|mixed)( (requests|queries|questions|inquiries|tasks|topics|items|things|stuff))?",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final Comparator<Sized> LARGEST_FIRST = Comparator.comparingLong(Sized::size).reversed();

    public static AppliedAnswer apply(@NonNull AuthorAnswer answer, @NonNull List<GroupSummary> groups,
            int maxTopics) {
        int k = groups.size();
        // A blank name is no name: its groups go to Other, as a catch-all's do.
        var candidates = mergeSameNames(answer.topics(), groups).stream()
                .map(topic -> new Sized(topic, validSize(topic, groups)))
                .sorted(LARGEST_FIRST)
                .map(Sized::topic)
                .filter(topic -> !isCatchAllName(topic.name()) && !TopicText.pythonStrip(topic.name()).isEmpty())
                .toList();
        // A plain loop: an earlier topic's groups are gone for the later ones.
        var claimed = new boolean[k];
        var topics = new ArrayList<AppliedTopic>();
        for (var topic : candidates) {
            if (topics.size() >= maxTopics) {
                break;
            }
            var mine = topic.clusters().stream().filter(j -> j >= 0 && j < k && !claimed[j]).distinct().toList();
            if (mine.isEmpty()) {
                continue;
            }
            mine.forEach(j -> claimed[j] = true);
            topics.add(AppliedTopic.builder()
                    .name(TopicText.name(topic.name()))
                    .definition(TopicText.pythonStrip(topic.definition()))
                    .groups(mine)
                    .build());
        }
        var other = IntStream.range(0, k).filter(j -> !claimed[j]).boxed().toList();
        return AppliedAnswer.builder().topics(List.copyOf(topics)).other(other).build();
    }

    /**
     * Topics whose names are equal after {@link #nameKey} become one, at the place of the first of them in the answer.
     * It keeps the name and definition of its largest member (by {@link #validSize}, ties in answer order) and lists
     * the members' group ids, largest member first, each id once.
     */
    private static List<AuthorTopic> mergeSameNames(List<AuthorTopic> topics, List<GroupSummary> groups) {
        return topics.stream()
                .collect(Collectors.groupingBy(topic -> nameKey(topic.name()), LinkedHashMap::new,
                        Collectors.toList()))
                .values().stream()
                .map(members -> members.size() == 1 ? members.getFirst() : merge(members, groups))
                .toList();
    }

    private static AuthorTopic merge(List<AuthorTopic> members, List<GroupSummary> groups) {
        var bySize = members.stream()
                .map(topic -> new Sized(topic, validSize(topic, groups)))
                .sorted(LARGEST_FIRST)
                .map(Sized::topic)
                .toList();
        var clusters = bySize.stream().flatMap(topic -> topic.clusters().stream()).distinct().toList();
        return bySize.getFirst().toBuilder().clusters(clusters).build();
    }

    /** Python's {@code " ".join(name.split()).lower()}: no outer whitespace, inner runs as one space, lower case. */
    static String nameKey(String name) {
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

    /** {@link #CATCH_ALL_NAME} on the name normalised by {@link #nameKey}. */
    static boolean isCatchAllName(String name) {
        return CATCH_ALL_NAME.matcher(nameKey(name)).matches();
    }

    /** Every listed valid id counts, a repeated one each time. */
    private static long validSize(AuthorTopic topic, List<GroupSummary> groups) {
        return topic.clusters().stream()
                .filter(j -> j >= 0 && j < groups.size())
                .mapToLong(j -> groups.get(j).size())
                .sum();
    }

    /** A topic with its {@link #validSize}, computed once for the sort. */
    private record Sized(AuthorTopic topic, long size) {
    }
}
