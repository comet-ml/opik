package com.comet.opik.infrastructure.redis;

/**
 * Raised for a stream entry whose payload the codec could not decode.
 * <p>
 * Deliberately <em>retryable</em> — it is absent from {@code BaseRedisSubscriber}'s
 * NON_RETRYABLE_EXCEPTIONS — so the entry goes through {@code maxRetries} deliveries before being
 * acked and removed, rather than being deleted on first sight.
 * <p>
 * That distinction is the whole point. "This payload cannot be decoded" is not the same claim as
 * "this payload cannot be decoded <em>by anyone</em>", and the codec cannot tell them apart:
 * <ul>
 * <li>An oversized string breaches the reader's {@code maxStringLength}, which is
 * <em>configuration</em> — a pod with a higher {@code JACKSON_MAX_STRING_LENGTH}, or one that built
 * its codec after {@code JsonUtils.configure()}, decodes the same bytes fine. That is exactly
 * OPIK-8164.</li>
 * <li>During a rolling upgrade an older pod hits an unknown enum constant or a polymorphic
 * {@code @class} absent from its jar. A newer pod reads it without trouble.</li>
 * </ul>
 * Deleting on first delivery would discard both. Leaving the entry pending gives another consumer a
 * chance to claim and decode it, and still terminates: once {@code maxRetries} is reached
 * {@code handleMaxRetriesReached} removes it, so a genuinely poisonous payload cannot wedge the
 * stream the way it did before this class existed.
 */
public class UndecodablePayloadException extends RuntimeException {

    public UndecodablePayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
