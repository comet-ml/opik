package com.comet.opik.infrastructure.redis;

/**
 * Raised for a stream entry whose payload the codec could not decode.
 * <p>
 * Deliberately <em>retryable</em> — it is absent from {@code BaseRedisSubscriber}'s
 * NON_RETRYABLE_EXCEPTIONS — so the entry goes through {@code maxRetries} deliveries before being
 * acked and removed, rather than being deleted on first sight.
 * <p>
 * That distinction is the whole point. "This payload cannot be decoded" is not the same claim as
 * "this payload cannot be decoded <em>by anyone</em>", and the codec cannot tell them apart. The case
 * that justifies retrying is <strong>jar skew during a rolling upgrade</strong>: an older pod hits an
 * unknown enum constant or a polymorphic {@code @class} absent from its jar, where a newer pod reads
 * the same bytes without trouble. This is the family {@code FAIL_ON_UNKNOWN_PROPERTIES=false} and
 * {@code LenientUUIDDeserializer} already exist to survive, which is the team's standing answer to it:
 * make such a payload decodable, not deleted.
 * <p>
 * Note what does <em>not</em> justify it, to keep this Javadoc honest. An oversized string breaching
 * {@code maxStringLength} looks configuration-dependent, but since #8060 every pod of a deployment
 * builds its memoized codec after {@code JsonUtils.configure()} from the same {@code config.yml} key,
 * so in steady state a peer will not decode it either — {@code RedisStreamCodecTest}'s
 * ordering test pins that. Retrying a size breach is therefore usually wasted work; it is tolerated
 * because {@code maxRetries} bounds it and because the codec cannot distinguish the two families at
 * the point of failure. A deployment mid config change is the narrow exception.
 * <p>
 * Either way this terminates: once {@code maxRetries} is reached {@code handleMaxRetriesReached}
 * removes the entry, so a genuinely poisonous payload cannot wedge the stream the way it did before
 * this class existed.
 */
public class UndecodablePayloadException extends RuntimeException {

    public UndecodablePayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
