package com.comet.opik.domain.topics;

import com.comet.opik.api.error.ErrorMessage;
import com.google.common.base.Throwables;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * How a failed ClickHouse read of the Topics DAOs shows after {@code block()}. The DAOs read through the r2dbc http
 * driver, which fails with the v1 client's checked {@link com.clickhouse.client.ClickHouseException} (Reactor wraps it
 * in a runtime exception), not with the v2 client's {@link com.clickhouse.client.api.ServerException}. Both are
 * recognised.
 */
@Slf4j
@UtilityClass
public class TopicsReadErrors {

    static final String RETRY_AFTER_SECONDS = "5";

    private static final int TOO_MANY_ROWS = 158;
    private static final int TIMEOUT_EXCEEDED = 159;
    private static final int MEMORY_LIMIT_EXCEEDED = 241;
    private static final int TOO_MANY_BYTES = 307;
    private static final int TOO_MANY_ROWS_OR_BYTES = 396;

    private static final Set<Integer> GUARD_CODES = Set.of(TOO_MANY_ROWS, TIMEOUT_EXCEEDED, MEMORY_LIMIT_EXCEEDED,
            TOO_MANY_BYTES, TOO_MANY_ROWS_OR_BYTES);
    private static final Set<Integer> LIMIT_CODES = Set.of(TIMEOUT_EXCEEDED, MEMORY_LIMIT_EXCEEDED);

    /**
     * Any ClickHouse guard of a read API query (rows, bytes, time or memory): the project is too large for the read,
     * and a retry fails the same way, so the read API answers {@code status: too_large}, not an error.
     */
    static boolean isGuardTrip(@NonNull Throwable error) {
        return hasCode(error, GUARD_CODES);
    }

    /**
     * A time or memory limit, or the client timeout anywhere in the causal chain, of a query that is not the read
     * API's: the traces list filtered by topic, where load can pass and a 503 with Retry-After fits. False for the rows
     * and bytes limits.
     */
    static boolean isLimit(@NonNull Throwable error) {
        return hasCode(error, LIMIT_CODES) || Throwables.getCausalChain(error).stream()
                .anyMatch(TimeoutException.class::isInstance);
    }

    /**
     * Answers a guard trip of {@code topics.filter} ({@link #isLimit}) as a 503 with Retry-After when the query is
     * filtered by topic. Other errors, the rows and bytes limits included, pass as they are.
     */
    public static <T> Mono<T> busyOnFilterGuard(boolean topicFiltered, @NonNull Mono<T> read) {
        if (!topicFiltered) {
            return read;
        }
        return read.onErrorMap(TopicsReadErrors::isLimit, error -> {
            log.warn("Trace query filtered by topic hit a guard", error);
            return busy();
        });
    }

    static ServiceUnavailableException busy() {
        return new ServiceUnavailableException(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .entity(new ErrorMessage(List.of("Topics are busy; retry shortly")))
                .build());
    }

    private static boolean hasCode(Throwable error, Set<Integer> codes) {
        return Throwables.getCausalChain(error).stream()
                .anyMatch(cause -> (cause instanceof com.clickhouse.client.ClickHouseException v1
                        && codes.contains(v1.getErrorCode()))
                        || (cause instanceof com.clickhouse.client.api.ServerException v2
                                && codes.contains(v2.getCode())));
    }
}
