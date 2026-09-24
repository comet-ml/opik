package com.comet.opik.domain.experiments.aggregations;

/**
 * Wraps a failure writing {@code experiment_item_aggregates}, so the aggregation message stays
 * redeliverable.
 *
 * <p>This exists because two layers classify retryability differently and this insert sits between
 * them. {@code BaseRedisSubscriber#isRetryableException} treats anything <em>unrecognised</em> as
 * retryable and its {@code NON_RETRYABLE_EXCEPTIONS} set — {@code IllegalStateException},
 * {@code IllegalArgumentException}, {@code NullPointerException} and friends — as "the code has a bug,
 * do not redeliver". {@code RetryUtils#handleConnectionError}, meanwhile, explicitly treats an
 * {@code IllegalStateException} carrying "Connection pool shut down" as retryable, because from the
 * ClickHouse client that is a transport failure rather than a bug.
 *
 * <p>So an exception the client raises can be transient and still land in the subscriber's
 * non-retryable set. While this path built its own insert, {@code Future.get()} wrapped every failure
 * in an {@code ExecutionException}, which is unrecognised and therefore retryable; moving onto
 * {@code JsonEachRowBulkInsert} unwraps to the cause, which would silently retire those messages on
 * first delivery. Wrapping here restores that, and says why, rather than leaving it to a wrapper that
 * happened to be there.
 */
public class ExperimentAggregatesInsertException extends RuntimeException {

    public ExperimentAggregatesInsertException(Throwable cause) {
        super("Failed to insert experiment item aggregates", cause);
    }
}
