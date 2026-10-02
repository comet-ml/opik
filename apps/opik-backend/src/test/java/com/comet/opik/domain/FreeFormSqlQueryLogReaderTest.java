package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Free-form SQL query log reader")
class FreeFormSqlQueryLogReaderTest {

    private static final String QUERY_ID = "q";
    private static final String USER = "comet_readonly_freeform_sql_user";
    private static final List<FreeFormSqlQueryLogEntry> WRITTEN = List.of(entry(USER));
    private static final List<FreeFormSqlQueryLogEntry> NOT_YET = List.of();

    private final AtomicInteger flushes = new AtomicInteger();
    private final Deque<List<FreeFormSqlQueryLogEntry>> answers = new ArrayDeque<>();
    private final AtomicInteger fetches = new AtomicInteger();

    private static FreeFormSqlQueryLogEntry entry(String user) {
        return FreeFormSqlQueryLogEntry.builder().initial(true).user(user).tables(List.of())
                .policedTables(List.of()).build();
    }

    /** The log answers {@code answers} in order, the last one repeating. */
    private FreeFormSqlQueryLogReader reader(List<List<FreeFormSqlQueryLogEntry>> answers) {
        this.answers.addAll(answers);
        return new FreeFormSqlQueryLogReader((queryId, user) -> {
            assertThat(queryId).isEqualTo(QUERY_ID);
            assertThat(user).isEqualTo(USER);
            fetches.incrementAndGet();
            return CompletableFuture.completedFuture(this.answers.size() > 1
                    ? this.answers.poll()
                    : this.answers.peek());
        }, () -> {
            flushes.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, 3);
    }

    @Test
    @DisplayName("entries already written are read without a flush")
    void writtenEntriesNeedNoFlush() {
        assertThat(reader(List.of(WRITTEN)).entries(QUERY_ID, USER, false).join()).isEqualTo(WRITTEN);
        assertThat(flushes).hasValue(0);
        assertThat(fetches).hasValue(1);
    }

    @Test
    @DisplayName("entries not written yet are read after a flush")
    void missingEntriesAreFlushedOnce() {
        assertThat(reader(List.of(NOT_YET, WRITTEN)).entries(QUERY_ID, USER, false).join()).isEqualTo(WRITTEN);
        assertThat(flushes).hasValue(1);
        assertThat(fetches).hasValue(2);
    }

    @Test
    @DisplayName("an entry as another account does not count, so the reader flushes")
    void anotherAccountsEntryDoesNotCount() {
        reader(List.of(List.of(entry("default")), WRITTEN)).entries(QUERY_ID, USER, false).join();
        assertThat(flushes).hasValue(1);
    }

    @Test
    @DisplayName("a query with remote shard reads always goes through a flush")
    void remoteReadsAlwaysFlush() {
        assertThat(reader(List.of(WRITTEN)).entries(QUERY_ID, USER, true).join()).isEqualTo(WRITTEN);
        assertThat(flushes).hasValue(1);
        assertThat(fetches).as("no read before the flush").hasValue(1);
    }

    @Test
    @DisplayName("entries that never appear stop after the last attempt, for the check to reject")
    void neverWrittenStopsAfterMaxAttempts() {
        assertThat(reader(List.of(NOT_YET)).entries(QUERY_ID, USER, false).join()).isEqualTo(NOT_YET);
        assertThat(flushes).hasValue(3);
        assertThat(fetches).hasValue(4);
    }
}
