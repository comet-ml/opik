package com.comet.opik.infrastructure.redis;

import lombok.Builder;
import lombok.NonNull;

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
 * is known, so {@code BaseRedisSubscriber} can count it, log it, and retire it through
 * {@code maxRetries}.
 * <p>
 * Note what this does <em>not</em> assert: that the payload is undecodable by anyone. It is not.
 * The reader's {@code maxStringLength} is configuration, so a pod with a higher limit decodes the
 * same bytes; and during a rolling upgrade an older pod fails on a payload a newer one reads. That
 * is why the failure is raised as the retryable {@link UndecodablePayloadException} rather than
 * deleted on sight — see that class for the reasoning.
 *
 * @param encodedBytes size of the slice Redisson handed the decoder, i.e. the RESP-encoded wire size
 *                      of this one field's value as read off the stream. On {@link RedisStreamCodec#JAVA}
 *                      that is the payload's raw JSON size for the map-VALUE (trace) path -- Redisson's
 *                      {@code CompositeCodec} routes values through {@code JsonJacksonCodec}, not
 *                      LZ4, on this codec's wiring -- and LZ4-compressed for the map-KEY (field-name)
 *                      path. Not the decoded size of whatever the payload represents either way: there
 *                      is no decoded size to report, since the decode is exactly what failed.
 * @param cause         the decode failure, kept for the log rather than rethrown
 */
@Builder(toBuilder = true)
public record UndecodableStreamMessage(int encodedBytes, @NonNull Throwable cause) {
}
