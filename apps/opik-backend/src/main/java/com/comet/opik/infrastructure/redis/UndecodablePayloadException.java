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
 * Either way this normally terminates: once {@code maxRetries} is reached
 * {@code handleMaxRetriesReached} removes the entry, so a genuinely poisonous payload cannot wedge the
 * stream the way it did before this class existed.
 * <p>
 * Two caveats, stated rather than glossed.
 * <p>
 * Retirement depends on {@code getDeliveryCount}, which maps a failed {@code listPending} to
 * {@code 0}. While a PEL lookup is failing persistently the count never reaches {@code maxRetries} and
 * the entry keeps being redelivered, so "always terminates" is really "terminates while the PEL is
 * readable". Pre-existing behaviour for every retryable failure, not specific to this one.
 * <p>
 * And {@code maxRetries} is a budget shared across the fleet, not per pod: an older pod can consume
 * all of it before a newer one ever claims the entry, in which case the retry bought nothing and the
 * entry is removed anyway. So this is a <em>chance</em> for a newer decoder, not a guarantee — three
 * chances instead of the single chance that deleting on first delivery would give. At the shipped defaults
 * ({@code maxRetries: 3}, {@code pendingMessageDuration: 10m}) the window is roughly 30 minutes, which
 * normally outlasts a rolling upgrade; a slower deploy can still lose the entry. Scoping retirement by
 * decoder version would close that properly, and is out of scope here.
 */
public class UndecodablePayloadException extends RuntimeException {

    public UndecodablePayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
