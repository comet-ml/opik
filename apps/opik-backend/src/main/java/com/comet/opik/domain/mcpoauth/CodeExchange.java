package com.comet.opik.domain.mcpoauth;

import lombok.Builder;
import lombok.NonNull;

/**
 * Result of an authorization-code exchange: the tokens handed back to the client, plus the facts about
 * the grant that only the exchange itself can observe.
 * <p>
 * {@code userName} is carried separately rather than added to {@link TokenResponse} because that record is
 * the wire body of {@code POST /oauth/token} — the resource owner's identity has no business being echoed
 * back to the client. {@code firstConnection} is evaluated inside the issuing transaction, before the new
 * rows land, so it cannot be recomputed by the caller afterwards.
 */
@Builder(toBuilder = true)
public record CodeExchange(
        @NonNull TokenResponse tokens,
        @NonNull String userName,
        boolean firstConnection) {
}
