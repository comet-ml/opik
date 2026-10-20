package com.comet.opik.domain.mapping.otel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Applies the provider-resolution chain to a span's raw model/provider pair.
 * <p>
 * Order is load-bearing, and each step depends on the ones before it:
 * <ol>
 *     <li>{@link ElasticInferenceServiceResolver} first, because it is the only step keyed on a
 *     provider Opik already names canonically ({@code elastic}). Running it after an aliasing step
 *     would risk that step rewriting {@code elastic} out from under it.</li>
 *     <li>{@link GenAiProviderAliasResolver} maps the semantic-convention vocabulary onto Opik's
 *     canonical names, so the two Google steps below can match on canonical values only.</li>
 *     <li>{@link GoogleProviderResolver} disambiguates the Google values that name no backend,
 *     which requires the aliased vocabulary to have settled first.</li>
 *     <li>{@link VertexAnthropicResolver} last, because it keys on {@code google_vertexai} —
 *     a value either of the two preceding steps can produce.</li>
 * </ol>
 */
@UtilityClass
public class ProviderResolvers {

    private static final List<ProviderResolver> CHAIN = List.of(
            new ElasticInferenceServiceResolver(),
            new GenAiProviderAliasResolver(),
            new GoogleProviderResolver(),
            new VertexAnthropicResolver());

    public static ProviderResolver.Resolution resolve(String model, String provider, ObjectNode metadata) {
        var resolution = new ProviderResolver.Resolution(model, provider);

        for (ProviderResolver resolver : CHAIN) {
            if (resolver.appliesTo(resolution)) {
                resolution = resolver.apply(resolution, metadata);
            }
        }

        return resolution;
    }
}
