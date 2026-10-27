package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the author answer into topics, as the stand's {@code apply_author} (v4): topics ordered by the total size of
 * their valid group ids (largest first, ties in answer order); catch-all names dropped; the first {@code maxTopics}
 * kept; each takes the groups no earlier topic claimed, and one left with none creates nothing; every unclaimed group is
 * Other. The answer's {@code other} list is ignored. A group listed twice in one topic counts once (the stand kept the
 * duplicate prototype, which never changes an assignment).
 */
@UtilityClass
public class AuthorApplier {

    static final Pattern CATCH_ALL = Pattern.compile("\\b(other|others|misc|miscellaneous|general|various)\\b",
            Pattern.UNICODE_CHARACTER_CLASS);

    public static Applied apply(@NonNull AuthorAnswer answer, @NonNull List<GroupSummary> groups, int maxTopics) {
        int k = groups.size();
        var ordered = new ArrayList<>(answer.topics());
        ordered.sort(Comparator.comparingLong(topic -> -validSize(topic, groups)));
        var claimed = new boolean[k];
        var topics = new ArrayList<NewTopic>();
        int taken = 0;
        for (var topic : ordered) {
            if (isCatchAll(topic.name())) {
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
            topics.add(new NewTopic(TopicText.pythonStrip(topic.name()), TopicText.pythonStrip(topic.definition()),
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

    public static boolean isCatchAll(@NonNull String name) {
        return CATCH_ALL.matcher(name.toLowerCase(Locale.ROOT)).find();
    }

    /** As the stand: every listed valid id counts, a repeated one each time. */
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
    @Builder
    public record Applied(@NonNull List<NewTopic> topics, @NonNull List<Integer> other) {
    }
}
