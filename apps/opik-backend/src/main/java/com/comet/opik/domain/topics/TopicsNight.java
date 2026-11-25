package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;
import org.quartz.CronExpression;

import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;

/** A night: its UTC date, whose start ends its window, and the schedule's first fire that day, its start. */
@Builder(toBuilder = true)
record TopicsNight(@NonNull LocalDate date, @NonNull Instant start) {

    Instant windowTo() {
        return windowTo(date);
    }

    /** The latest {@link #current} returns this night: the end of the next UTC day. */
    Instant currentUntil() {
        return windowTo(date.plusDays(2));
    }

    /** The window end of the night on {@code date}: that UTC day's start. */
    static Instant windowTo(@NonNull LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * The night {@code now} falls in: the latest UTC date, today or yesterday, on which the schedule fired at or before
     * {@code now}, with its first fire that day. Empty when the schedule fired on neither: a night older than that is
     * past any budget.
     */
    static Optional<TopicsNight> current(@NonNull String schedule, @NonNull Instant now) {
        var cron = cron(schedule);
        var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        for (var date : List.of(today, today.minusDays(1))) {
            var dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            var first = cron.getNextValidTimeAfter(Date.from(dayStart.minusSeconds(1)));
            if (first != null && LocalDate.ofInstant(first.toInstant(), ZoneOffset.UTC).equals(date)
                    && !first.toInstant().isAfter(now)) {
                return Optional.of(TopicsNight.builder().date(date).start(first.toInstant()).build());
            }
        }
        return Optional.empty();
    }

    private static CronExpression cron(String schedule) {
        try {
            var cron = new CronExpression(schedule);
            cron.setTimeZone(TimeZone.getTimeZone(ZoneOffset.UTC));
            return cron;
        } catch (ParseException exception) {
            throw new IllegalArgumentException("topics.nightly.schedule is not a Quartz cron: '%s'".formatted(schedule),
                    exception);
        }
    }
}
