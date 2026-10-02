package com.comet.opik.domain;

import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfigTest;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Free-form SQL query log reader")
class FreeFormSqlQueryLogReaderTest {

    private static final String QUERY_ID = "q";
    private static final String USER = "comet_readonly_freeform_sql_user";
    private static final List<FreeFormSqlQueryLogEntry> WRITTEN = List.of(entry(USER));
    private static final List<FreeFormSqlQueryLogEntry> NOT_YET = List.of();

    private final Deque<List<FreeFormSqlQueryLogEntry>> answers = new ArrayDeque<>();
    /** The cluster-wide permit's answers in order, the last one repeating. */
    private final Deque<Boolean> permits = new ArrayDeque<>(List.of(true));
    /** Set when the permit cannot be checked at all, e.g. Redis down. */
    private boolean permitUnavailable;
    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicInteger permitRequests = new AtomicInteger();
    private final AtomicInteger flushes = new AtomicInteger();
    /** The waits the reader asked for, in order; each runs at once, so the tests need no real time. */
    private final List<Long> waits = new ArrayList<>();
    /** The log reads and flushes, in the order they ran. */
    private final List<String> operations = new ArrayList<>();

    private static FreeFormSqlQueryLogEntry entry(String user) {
        return FreeFormSqlQueryLogEntry.builder().initial(true).user(user).tables(List.of())
                .policedTables(List.of()).build();
    }

    private static <T> T next(Deque<T> answers) {
        return answers.size() > 1 ? answers.poll() : answers.peek();
    }

    /** The log answers {@code answers} in order, the last one repeating. */
    private FreeFormSqlQueryLogReader reader(List<List<FreeFormSqlQueryLogEntry>> answers) {
        this.answers.addAll(answers);
        return new FreeFormSqlQueryLogReader((queryId, user) -> {
            assertThat(queryId).isEqualTo(QUERY_ID);
            assertThat(user).isEqualTo(USER);
            fetches.incrementAndGet();
            operations.add("fetch");
            return CompletableFuture.completedFuture(next(this.answers));
        }, () -> {
            flushes.incrementAndGet();
            operations.add("flush");
            return CompletableFuture.completedFuture(null);
        }, () -> {
            permitRequests.incrementAndGet();
            return permitUnavailable
                    ? CompletableFuture.failedFuture(new IllegalStateException("Redis unavailable"))
                    : CompletableFuture.completedFuture(next(permits));
        }, 3, 500, (delayMillis, task) -> {
            waits.add(delayMillis);
            task.run();
        });
    }

    private void givenPermits(Boolean... answers) {
        permits.clear();
        permits.addAll(List.of(answers));
    }

    @Test
    @DisplayName("entries already written are read without a flush")
    void writtenEntriesNeedNoFlush() {
        assertThat(reader(List.of(WRITTEN)).entries(QUERY_ID, USER, false).join()).isEqualTo(WRITTEN);
        assertThat(fetches).hasValue(1);
        assertThat(permitRequests).hasValue(0);
        assertThat(waits).isEmpty();
    }

    @Test
    @DisplayName("entries written during the wait after a miss are read without a flush")
    void entriesWrittenDuringTheWaitNeedNoFlush() {
        assertThat(reader(List.of(NOT_YET, WRITTEN)).entries(QUERY_ID, USER, false).join()).isEqualTo(WRITTEN);
        assertThat(fetches).hasValue(2);
        assertThat(permitRequests).hasValue(0);
        assertThat(waits).as("500 ms before reading again").containsExactly(500L);
    }

    @Test
    @DisplayName("entries still missing after the wait are read after a flush")
    void missingEntriesAreFlushedOnce() {
        assertThat(reader(List.of(NOT_YET, NOT_YET, WRITTEN)).entries(QUERY_ID, USER, false).join())
                .isEqualTo(WRITTEN);
        assertThat(flushes).hasValue(1);
        assertThat(fetches).hasValue(3);
        assertThat(waits).as("500 ms before reading again, then the flush at once").containsExactly(500L);
    }

    @Test
    @DisplayName("an entry as another account does not count, so the reader flushes")
    void anotherAccountsEntryDoesNotCount() {
        var other = List.of(entry("default"));
        assertThat(reader(List.of(other, other, WRITTEN)).entries(QUERY_ID, USER, false).join()).isEqualTo(WRITTEN);
        assertThat(flushes).hasValue(1);
        assertThat(fetches).hasValue(3);
        assertThat(waits).containsExactly(500L);
    }

    @Test
    @DisplayName("a denied permit means another instance just flushed: the reader only reads the log again")
    void deniedPermitOnlyRereads() {
        givenPermits(false);
        assertThat(reader(List.of(NOT_YET, NOT_YET, WRITTEN)).entries(QUERY_ID, USER, false).join())
                .isEqualTo(WRITTEN);
        assertThat(flushes).hasValue(0);
        assertThat(permitRequests).hasValue(1);
        assertThat(fetches).hasValue(3);
    }

    @Test
    @DisplayName("entries that never appear stop after the last attempt, for the check to reject")
    void neverWrittenStopsAfterMaxAttempts() {
        assertThat(reader(List.of(NOT_YET)).entries(QUERY_ID, USER, false).join()).isEqualTo(NOT_YET);
        assertThat(flushes).hasValue(3);
        assertThat(fetches).hasValue(5);
        assertThat(waits).as("500 ms between every attempt").containsExactly(500L, 500L, 500L);
    }

    @Test
    @DisplayName("a query with remote shard reads goes straight to a flush attempt")
    void remoteReadsFlushFirst() {
        assertThat(reader(List.of(WRITTEN)).entries(QUERY_ID, USER, true).join()).isEqualTo(WRITTEN);
        assertThat(operations).as("the flush before the only read").containsExactly("flush", "fetch");
        assertThat(waits).isEmpty();
    }

    @Test
    @DisplayName("a query with remote shard reads denied the permit reads the log another instance just flushed")
    void remoteReadsDeniedOnlyRereads() {
        givenPermits(false);
        assertThat(reader(List.of(WRITTEN)).entries(QUERY_ID, USER, true).join()).isEqualTo(WRITTEN);
        assertThat(flushes).hasValue(0);
        assertThat(fetches).hasValue(1);
    }

    @Test
    @DisplayName("the configured attempts and retry delay reach the reader the application builds")
    void configuredTimingsReachTheReader() {
        var dao = mock(FreeFormSqlQueryDAO.class);
        when(dao.fetchQueryLog(QUERY_ID, USER)).thenReturn(CompletableFuture.completedFuture(NOT_YET));
        when(dao.flushQueryLog()).thenReturn(CompletableFuture.completedFuture(null));
        var rateLimit = mock(RateLimitService.class);
        when(rateLimit.isLimitExceeded(anyLong(), anyString(), any())).thenReturn(Mono.just(false));
        var config = FreeFormSqlPostRunCheckConfigTest.config();
        config.setMaxFlushAttempts(2);
        config.setLogRetryDelay(Duration.milliseconds(300));

        long start = System.nanoTime();
        assertThat(new FreeFormSqlQueryLogReader(dao, rateLimit, config).entries(QUERY_ID, USER, false).join())
                .isEqualTo(NOT_YET);

        verify(dao, times(2)).flushQueryLog();
        verify(dao, times(4)).fetchQueryLog(QUERY_ID, USER);
        // Two waits: after the first miss, and between the two flush attempts.
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(600);
    }

    @Test
    @DisplayName("a permit that cannot be checked fails the check instead of flushing past the cluster-wide limit")
    void uncheckablePermitFails() {
        permitUnavailable = true;
        assertThatThrownBy(() -> reader(List.of(NOT_YET)).entries(QUERY_ID, USER, false).join())
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis unavailable");
        assertThat(flushes).hasValue(0);
    }
}
