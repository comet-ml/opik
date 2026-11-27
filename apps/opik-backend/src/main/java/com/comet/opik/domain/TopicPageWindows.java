package com.comet.opik.domain;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The spans a trace list page filtered by topic is read in before its whole range, newest first. Without them the topic
 * subquery reads every {@code trace_topics} row of the project's range for any page.
 *
 * <p>Exact: a span is the list's range with a later lower bound, as a later {@code from_time} would give. In the
 * default order, newest trace id first, a span's traces are the newest of the range, so a span read that fills the
 * page has the page of the whole range. A read that does not fill it is dropped and the next, 8 times longer span is
 * read; after {@link #MAX_SPANS} spans, or once a span reaches the range's own lower bound, the whole range is read,
 * as without spans.
 *
 * <p>Only for the default order, with a topic filter and its {@code topics.filter.firstPageWindow} above zero, and
 * when the list's count holds the page: a page past the count is the list's tail, which only the whole range reads.
 * The first span is {@code firstPageWindow}, or, with a {@code from_time}, twice the share of the range that holds the
 * page at the count's even rate, if longer.
 */
@UtilityClass
class TopicPageWindows {

    static final int GROWTH = 8;
    static final int MAX_SPANS = 4;

    /** The spans' lower bounds, newest first; empty when the page reads its whole range at once. */
    static List<Instant> lowerBounds(@NonNull TraceSearchCriteria criteria, int size, int page, long total,
            @NonNull Instant now) {
        var scope = criteria.topicScope();
        if (scope == null || !scope.firstPageWindow().isPositive()
                || CollectionUtils.isNotEmpty(criteria.sortingFields()) || size < 1 || page < 1) {
            return List.of();
        }
        long needed = (long) page * size;
        if (total < needed) {
            return List.of();
        }
        var upper = Stream.of(time(criteria.uuidToTime()), time(criteria.lastReceivedId()), now)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElseThrow();
        var floor = time(criteria.uuidFromTime());
        var span = scope.firstPageWindow();
        if (floor != null && upper.isAfter(floor)) {
            double share = 2.0 * needed / total;
            var even = Duration.ofSeconds((long) Math.ceil(Duration.between(floor, upper).toSeconds() * share));
            span = even.compareTo(span) > 0 ? even : span;
        }
        var bounds = new ArrayList<Instant>(MAX_SPANS);
        while (bounds.size() < MAX_SPANS) {
            var lower = upper.minus(span);
            if (floor != null && !lower.isAfter(floor)) {
                break;
            }
            bounds.add(lower);
            span = span.multipliedBy(GROWTH);
        }
        return bounds;
    }

    /** A UUID v7's time, to the millisecond; null for null. */
    private static Instant time(UUID id) {
        return id == null ? null : Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }
}
