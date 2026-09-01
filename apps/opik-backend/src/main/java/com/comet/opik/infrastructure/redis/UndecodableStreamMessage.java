package com.comet.opik.infrastructure.redis;

/**
 * Stand-in for a stream entry whose payload the codec could not decode.
 * <p>
 * Without this, a decode failure is thrown inside Redisson's {@code CommandDecoder} — below
 * {@code BaseRedisSubscriber}, before any {@code StreamMessageId} is in hand. With no id there is
 * nothing to ack, retry or remove, so the entry is redelivered forever and, at
 * {@code consumerBatchSize > 1}, strands every healthy entry claimed alongside it. That is the
 * permanent wedge behind OPIK-8164: one oversized trace took two production scoring streams to
 * {@code pending == XLEN} and 19.66 GiB.
 * <p>
 * Returning this instead of throwing keeps the failure inside the normal message flow, where the id
 * is known, so {@code BaseRedisSubscriber} can drop the entry, count it and log it. A payload we
 * cannot decode will never become decodable, so dropping is the only outcome that terminates.
 *
 * @param payloadBytes readable bytes the decoder was handed, for sizing the offending entry
 * @param cause        the decode failure, kept for the log rather than rethrown
 */
public record UndecodableStreamMessage(int payloadBytes, Throwable cause) {
}
