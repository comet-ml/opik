package com.comet.opik.domain.retention;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.retention.RetentionPeriod;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.domain.SpanDAO.VelocityEstimate;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.infrastructure.RetentionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.comet.opik.domain.retention.RetentionUtils.extractInstant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the TOO_MANY_ROWS (ClickHouse code 158) velocity estimation fallback path.
 *
 * We mock SpanDAO and TraceDAO here because ClickHouse's max_rows_to_read is a profile-level
 * setting that also blocks normal INSERT and SELECT operations (MergeTreeSelect during inserts).
 * This makes it impossible to trigger TOO_MANY_ROWS only on the estimation query in an
 * integration test with a real ClickHouse container — any profile-level limit low enough to
 * trigger on the estimation query also breaks span/trace inserts and deletes.
 */
@ExtendWith(MockitoExtension.class)
class RetentionEstimationServiceTest {

    private static final String WORKSPACE_ID = "00000001-0000-0000-0000-000000000000";
    private static final Instant NOW = Instant.parse("2026-03-25T12:00:00Z");
    private static final RetentionPeriod PERIOD = RetentionPeriod.SHORT_14D;

    @Mock
    private TransactionTemplate template;
    @Mock
    private SpanDAO spanDAO;
    @Mock
    private TraceDAO traceDAO;

    private final InstantToUUIDMapper uuidMapper = new InstantToUUIDMapper();
    private RetentionConfig config;
    private RetentionEstimationService service;

    @BeforeEach
    void setUp() {
        config = new RetentionConfig();
        config.setEnabled(true);
        var catchUp = new RetentionConfig.CatchUpConfig();
        catchUp.setEnabled(true);
        catchUp.setDefaultVelocity(1_000_000L);
        catchUp.setServiceStartDate(LocalDate.of(2024, 9, 1));
        config.setCatchUp(catchUp);

        service = new RetentionEstimationService(template, spanDAO, traceDAO, uuidMapper, config);
    }

    @Nested
    @DisplayName("estimateVelocity — TOO_MANY_ROWS fallback")
    class TooManyRowsFallback {

        /**
         * Simulates the ClickHouse exception chain as seen in production:
         * ReactiveException → ClickHouseException → IOException with "Code: 158"
         */
        private RuntimeException tooManyRowsException() {
            var ioEx = new java.io.IOException(
                    "Code: 158. DB::Exception: Limit for rows exceeded, max rows: 20.00 million, "
                            + "current rows: 24.00 million. (TOO_MANY_ROWS)");
            var chEx = new RuntimeException("ClickHouseException", ioEx);
            return new RuntimeException("ReactiveException", chEx);
        }

        @Test
        @DisplayName("Falls back to default velocity when estimation hits TOO_MANY_ROWS")
        void fallsBackToDefaultVelocity() {
            when(spanDAO.estimateVelocityForRetention(eq(WORKSPACE_ID), any(UUID.class)))
                    .thenReturn(Mono.error(tooManyRowsException()));

            // Scouting: first month empty, second month has data
            Instant serviceStart = LocalDate.of(2024, 9, 1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant month2Start = serviceStart.plus(30, ChronoUnit.DAYS);
            Instant firstDataDay = month2Start.plus(5, ChronoUnit.DAYS);

            when(traceDAO.scoutFirstDayWithData(eq(WORKSPACE_ID), any(UUID.class), any(UUID.class)))
                    .thenReturn(Mono.just(Instant.MAX)) // first month: no data
                    .thenReturn(Mono.just(firstDataDay)); // second month: data found

            var result = service.estimateVelocity(WORKSPACE_ID, PERIOD, NOW);

            assertThat(result.velocity()).isEqualTo(1_000_000L);
            // Cursor should be at firstDataDay, not service start
            Instant cursorTime = extractInstant(result.startCursor());
            assertThat(cursorTime).isEqualTo(
                    firstDataDay.truncatedTo(ChronoUnit.MILLIS));
        }

        @Test
        @DisplayName("Marks catch-up done when scouting finds no data at all")
        void marksDoneWhenScoutingFindsNothing() {
            when(spanDAO.estimateVelocityForRetention(eq(WORKSPACE_ID), any(UUID.class)))
                    .thenReturn(Mono.error(tooManyRowsException()));

            // All months empty
            when(traceDAO.scoutFirstDayWithData(eq(WORKSPACE_ID), any(UUID.class), any(UUID.class)))
                    .thenReturn(Mono.just(Instant.MAX));

            var result = service.estimateVelocity(WORKSPACE_ID, PERIOD, NOW);

            // No data found — velocity 0, null cursor signals catch-up should be marked done
            assertThat(result.velocity()).isZero();
            assertThat(result.startCursor()).isNull();
        }

        @Test
        @DisplayName("Scouting stops at month that also hits TOO_MANY_ROWS")
        void scoutingStopsAtDenseMonth() {
            when(spanDAO.estimateVelocityForRetention(eq(WORKSPACE_ID), any(UUID.class)))
                    .thenReturn(Mono.error(tooManyRowsException()));

            Instant serviceStart = LocalDate.of(2024, 9, 1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant month2Start = serviceStart.plus(30, ChronoUnit.DAYS);

            when(traceDAO.scoutFirstDayWithData(eq(WORKSPACE_ID), any(UUID.class), any(UUID.class)))
                    .thenReturn(Mono.just(Instant.MAX)) // first month: empty
                    .thenReturn(Mono.error(tooManyRowsException())); // second month: too dense

            var result = service.estimateVelocity(WORKSPACE_ID, PERIOD, NOW);

            assertThat(result.velocity()).isEqualTo(1_000_000L);
            // Cursor should be at the start of the dense month
            Instant cursorTime = extractInstant(result.startCursor());
            assertThat(cursorTime).isEqualTo(month2Start);
        }

        @Test
        @DisplayName("Non-TOO_MANY_ROWS exceptions are rethrown")
        void nonTooManyRowsExceptionRethrown() {
            var otherError = new RuntimeException("Connection refused");
            when(spanDAO.estimateVelocityForRetention(eq(WORKSPACE_ID), any(UUID.class)))
                    .thenReturn(Mono.error(otherError));

            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> service.estimateVelocity(WORKSPACE_ID, PERIOD, NOW));

            // Scouting should NOT be attempted
            verify(traceDAO, never()).scoutFirstDayWithData(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("estimateVelocity — normal path")
    class NormalPath {

        @Test
        @DisplayName("Returns velocity and oldest span time from successful estimation")
        void returnsEstimationResult() {
            Instant oldestSpan = NOW.minus(90, ChronoUnit.DAYS);
            when(spanDAO.estimateVelocityForRetention(eq(WORKSPACE_ID), any(UUID.class)))
                    .thenReturn(Mono.just(new VelocityEstimate(5000L, oldestSpan)));

            var result = service.estimateVelocity(WORKSPACE_ID, PERIOD, NOW);

            assertThat(result.velocity()).isEqualTo(5000L);
            Instant cursorTime = extractInstant(result.startCursor());
            assertThat(cursorTime).isEqualTo(oldestSpan.truncatedTo(ChronoUnit.MILLIS));

            // No scouting needed
            verify(traceDAO, never()).scoutFirstDayWithData(any(), any(), any());
        }

        @Test
        @DisplayName("Empty workspace returns velocity 0 and null cursor (catch-up marked done)")
        void emptyWorkspaceReturnsZeroAndNullCursor() {
            when(spanDAO.estimateVelocityForRetention(eq(WORKSPACE_ID), any(UUID.class)))
                    .thenReturn(Mono.just(new VelocityEstimate(0L, null)));

            var result = service.estimateVelocity(WORKSPACE_ID, PERIOD, NOW);

            // No data found — velocity 0, null cursor signals catch-up should be marked done
            assertThat(result.velocity()).isZero();
            assertThat(result.startCursor()).isNull();
        }
    }
}
