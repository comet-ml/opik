package com.comet.opik.utils;

import jakarta.ws.rs.core.Response;
import lombok.experimental.UtilityClass;

import java.util.Set;

import static jakarta.ws.rs.core.Response.Status.Family.familyOf;

/**
 * One definition of which HTTP statuses are worth retrying, shared by the two layers that need it:
 * {@code ChatCompletionService} (whether to spend the in-process provider retry budget) and
 * {@code BaseRedisSubscriber} (whether to redeliver a stream entry or ack and drop it).
 *
 * <p>It lives here because those two disagreeing is what caused the defect this replaces. The subscriber
 * treated every {@code ClientErrorException} as permanent, so {@code scoreTrace} could not report a
 * truthful 429 without the evaluation being dropped, and answered every provider failure with a blanket
 * 500 instead. With one predicate the status can be reported honestly and still retried.
 *
 * <p>Named to avoid colliding with {@code dev.langchain4j.internal.RetryUtils}, which
 * {@code ChatCompletionService} already imports.
 */
@UtilityClass
public class HttpStatusRetryability {

    /** 425 Too Early (RFC 8470) has no {@link Response.Status} constant; 408 and 429 do. */
    private static final int TOO_EARLY_STATUS = 425;

    /**
     * 4xx statuses that say "not now", not "not ever", so the family alone cannot decide retryability.
     * langchain4j agrees on 408 and 429 but maps 425 to a non-retriable {@code InvalidRequestException};
     * we diverge deliberately per RFC 8470, which is safe because retryability is read from the status
     * rather than the mapped exception type.
     */
    private static final Set<Integer> TRANSIENT_CLIENT_ERRORS = Set.of(
            Response.Status.REQUEST_TIMEOUT.getStatusCode(),
            TOO_EARLY_STATUS,
            Response.Status.TOO_MANY_REQUESTS.getStatusCode());

    /**
     * Whether a request answered with this status can never succeed on retry. True only for the
     * client-error family minus {@link #TRANSIENT_CLIENT_ERRORS}: a 5xx is the textbook retry case, and
     * anything outside both families is unrecognised and therefore retryable — the same "unknown defaults
     * to retryable for safety" stance the subscriber takes on exception types it does not know.
     */
    public static boolean isPermanent(int status) {
        return familyOf(status) == Response.Status.Family.CLIENT_ERROR
                && !TRANSIENT_CLIENT_ERRORS.contains(status);
    }
}
