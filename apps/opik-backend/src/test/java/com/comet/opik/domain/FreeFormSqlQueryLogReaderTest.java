package com.comet.opik.domain;

import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfig;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    private static final long DELAY_MILLIS = 50;

    private final FreeFormSqlQueryDAO dao = mock(FreeFormSqlQueryDAO.class);

    private static FreeFormSqlQueryLogEntry entry(String user) {
        return FreeFormSqlQueryLogEntry.builder().initial(true).user(user).tables(List.of())
                .policyCoveredTables(List.of()).build();
    }

    private FreeFormSqlQueryLogReader reader(int maxAttempts) {
        var config = new FreeFormSqlPostRunCheckConfig();
        config.setMode(FreeFormSqlPostRunCheckConfig.Mode.AUDIT);
        config.setLogReadDelay(Duration.milliseconds(DELAY_MILLIS));
        config.setMaxLogReadAttempts(maxAttempts);
        return new FreeFormSqlQueryLogReader(dao, config);
    }

    @SafeVarargs
    private void givenLog(List<FreeFormSqlQueryLogEntry> first, List<FreeFormSqlQueryLogEntry>... then) {
        var stub = when(dao.fetchQueryLog(QUERY_ID, USER)).thenReturn(CompletableFuture.completedFuture(first));
        for (var answer : then) {
            stub = stub.thenReturn(CompletableFuture.completedFuture(answer));
        }
    }

    /** Reads the entries, returning them with the elapsed milliseconds. */
    private record Read(List<FreeFormSqlQueryLogEntry> entries, long elapsedMillis) {
    }

    private Read read(FreeFormSqlQueryLogReader reader) {
        long start = System.nanoTime();
        var entries = reader.entries(QUERY_ID, USER).join();
        return new Read(entries, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    @Test
    @DisplayName("the log is read only once the delay has passed")
    void readsAfterTheDelay() {
        givenLog(WRITTEN);
        var read = read(reader(3));
        assertThat(read.entries()).isEqualTo(WRITTEN);
        assertThat(read.elapsedMillis()).isGreaterThanOrEqualTo(DELAY_MILLIS);
        verify(dao, times(1)).fetchQueryLog(QUERY_ID, USER);
    }

    @Test
    @DisplayName("entries not written yet are read again after another delay")
    void missingEntriesAreReadAgain() {
        givenLog(NOT_YET, WRITTEN);
        var read = read(reader(3));
        assertThat(read.entries()).isEqualTo(WRITTEN);
        assertThat(read.elapsedMillis()).isGreaterThanOrEqualTo(2 * DELAY_MILLIS);
        verify(dao, times(2)).fetchQueryLog(QUERY_ID, USER);
    }

    @Test
    @DisplayName("an entry as another account does not count, so the reader reads again")
    void anotherAccountsEntryDoesNotCount() {
        givenLog(List.of(entry("default")), WRITTEN);
        assertThat(read(reader(3)).entries()).isEqualTo(WRITTEN);
        verify(dao, times(2)).fetchQueryLog(QUERY_ID, USER);
    }

    @Test
    @DisplayName("entries that never appear stop after the configured attempts, for the check to report")
    void neverWrittenStopsAfterMaxAttempts() {
        givenLog(NOT_YET);
        var read = read(reader(2));
        assertThat(read.entries()).isEqualTo(NOT_YET);
        assertThat(read.elapsedMillis()).isGreaterThanOrEqualTo(2 * DELAY_MILLIS);
        verify(dao, times(2)).fetchQueryLog(QUERY_ID, USER);
    }
}
