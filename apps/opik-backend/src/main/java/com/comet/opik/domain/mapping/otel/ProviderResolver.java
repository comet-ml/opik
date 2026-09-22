package com.comet.opik.domain.mapping.otel;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One normalization step over the (model, provider) pair extracted from a span's GenAI
 * attributes, applied before cost lookup and provider filtering ever see it.
 * <p>
 * Instrumentations report the provider in vocabularies that differ from Opik's price-table keys,
 * and some of those values name a gateway rather than the backend that actually served the call.
 * Each implementation owns exactly one such rewrite and declares, via {@link #appliesTo}, whether
 * a given pair is its business — so {@link ProviderResolvers} can select the applicable steps
 * instead of every resolver re-deriving that from its own guard clauses.
 */
public interface ProviderResolver {

    /**
     * The model/provider pair as it stands between resolution steps. Resolvers that only rewrite
     * the provider return the model unchanged.
     */
    record Resolution(String model, String provider) {
    }

    /**
     * Whether this resolver claims the given pair. Called before {@link #apply}, so implementations
     * need no defensive re-check.
     */
    boolean appliesTo(Resolution resolution);

    /**
     * Rewrites the pair. May record provenance in {@code metadata} — the span metadata as built so
     * far, which is also where resolvers read the attributes they disambiguate on.
     */
    Resolution apply(Resolution resolution, ObjectNode metadata);
}
