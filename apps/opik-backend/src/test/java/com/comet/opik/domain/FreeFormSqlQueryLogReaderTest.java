package com.comet.opik.domain;

import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfigTest;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
    private final AtomicInteger fetches = new AtomicInteger();
    /** The waits the reader asked for, in order; each runs at once, so the tests need no real time. */
    private final List<Long> waits = new ArrayList<>();

    private static FreeFormSqlQueryLogEntry entry(String user) {
        return FreeFormSqlQueryLogEntry.builder().initial(true).user(user).tables(List.of())
                .policyCoveredTables(List.of()).build();
    }

    /** The log answers {@code answers} in order, the last one repeating. */
    private FreeFormSqlQueryLogReader reader(List<List<FreeFormSqlQueryLogEntry>> answers) {
        this.answers.addAll(answers);
        return new FreeFormSqlQueryLogReader((queryId, user) -> {
            assertThat(queryId).isEqualTo(QUERY_ID);
            assertThat(user).isEqualTo(USER);
            fetches.incrementAndGet();
            return CompletableFuture.completedFuture(
                    this.answers.size() > 1 ? this.answers.poll() : this.answers.peek());
        }, 3, 10_000, (delayMillis, task) -> {
            waits.add(delayMillis);
            task.run();
        });
    }

    @Test
    @DisplayName("the log is read once the natural flush interval has passed, never before")
    void readsAfterTheDelay() {
        assertThat(reader(List.of(WRITTEN)).entries(QUERY_ID, USER).join()).isEqualTo(WRITTEN);
        assertThat(fetches).hasValue(1);
        assertThat(waits).containsExactly(10_000L);
    }

    @Test
    @DisplayName("entries not written yet are read again after another delay")
    void missingEntriesAreReadAgain() {
        assertThat(reader(List.of(NOT_YET, WRITTEN)).entries(QUERY_ID, USER).join()).isEqualTo(WRITTEN);
        assertThat(fetches).hasValue(2);
        assertThat(waits).containsExactly(10_000L, 10_000L);
    }

    @Test
    @DisplayName("an entry as another account does not count, so the reader reads again")
    void anotherAccountsEntryDoesNotCount() {
        assertThat(reader(List.of(List.of(entry("default")), WRITTEN)).entries(QUERY_ID, USER).join())
                .isEqualTo(WRITTEN);
        assertThat(fetches).hasValue(2);
    }

    @Test
    @DisplayName("entries that never appear stop after the last attempt, for the check to report")
    void neverWrittenStopsAfterMaxAttempts() {
        assertThat(reader(List.of(NOT_YET)).entries(QUERY_ID, USER).join()).isEqualTo(NOT_YET);
        assertThat(fetches).hasValue(3);
        assertThat(waits).containsExactly(10_000L, 10_000L, 10_000L);
    }

    @Test
    @DisplayName("the configured attempts and delay reach the reader the application builds")
    void configuredSettingsReachTheReader() {
        var dao = mock(FreeFormSqlQueryDAO.class);
        when(dao.fetchQueryLog(QUERY_ID, USER)).thenReturn(CompletableFuture.completedFuture(NOT_YET));
        var config = FreeFormSqlPostRunCheckConfigTest.config();
        config.setMaxLogReadAttempts(2);
        config.setLogReadDelay(Duration.milliseconds(300));

        long start = System.nanoTime();
        assertThat(new FreeFormSqlQueryLogReader(dao, config).entries(QUERY_ID, USER).join()).isEqualTo(NOT_YET);

        verify(dao, times(2)).fetchQueryLog(QUERY_ID, USER);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(600);
    }
}
