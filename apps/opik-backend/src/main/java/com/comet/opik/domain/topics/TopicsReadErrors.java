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

    private static final Set<Integer> TOO_LARGE_CODES = Set.of(TOO_MANY_ROWS, TOO_MANY_BYTES, TOO_MANY_ROWS_OR_BYTES);
    private static final Set<Integer> LIMIT_CODES = Set.of(TIMEOUT_EXCEEDED, MEMORY_LIMIT_EXCEEDED);

    /**
     * The read hit the rows or bytes it may read ({@code TOO_MANY_ROWS}, {@code TOO_MANY_BYTES},
     * {@code TOO_MANY_ROWS_OR_BYTES}): the same project fails the same way on a retry, so the caller gets a 400.
     */
    static boolean isTooLarge(@NonNull Throwable error) {
        return hasCode(error, TOO_LARGE_CODES);
    }

    /**
     * The read hit its time or memory limit ({@code TIMEOUT_EXCEEDED}, {@code MEMORY_LIMIT_EXCEEDED}), or the client
     * timeout is anywhere in the causal chain: a retry can pass, so the caller gets a 503 with Retry-After. False for
     * the codes of {@link #isTooLarge}.
     */
    static boolean isLimit(@NonNull Throwable error) {
        return hasCode(error, LIMIT_CODES) || Throwables.getCausalChain(error).stream()
                .anyMatch(TimeoutException.class::isInstance);
    }

    /**
     * The read of a trace query, with a guard trip of {@code topics.filter} ({@link #isLimit}) answered as a 503 with
     * Retry-After when the query is filtered by topic ({@code topicFiltered}). Other errors, the rows and bytes limits
     * included, pass as they are. A query without a topic filter is returned as it is.
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

    /** A 503 with Retry-After: the read can pass on a retry. */
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
