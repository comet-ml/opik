package com.comet.opik.domain.mapping.otel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * Rewrites Elastic Inference Service (EIS) model/provider attributes into their underlying
 * provider so that downstream cost lookup, filtering and grouping use the real provider.
 * <p>
 * EIS clients emit {@code gen_ai.system = "elastic"} and a routed model ID that encodes the
 * underlying provider as a hyphenated prefix (e.g. {@code anthropic-claude-4.6-sonnet},
 * {@code jina-embeddings-v3}). Different prefixes need different handling because some
 * underlying providers (Anthropic) key their pricing rows without a vendor prefix while
 * others (Jina) keep it as part of the canonical model name. See {@code EisRule}.
 * <p>
 * The original {@code gen_ai.system} and {@code gen_ai.request.model} values are preserved
 * in the span metadata for traceability.
 */
@Slf4j
public class ElasticInferenceServiceResolver implements ProviderResolver {

    public static final String ELASTIC_SYSTEM = "elastic";
    public static final String METADATA_ORIGINAL_SYSTEM = "eis_original_system";
    public static final String METADATA_ORIGINAL_MODEL = "eis_original_model";

    private record EisRule(String opikProvider, boolean stripPrefix) {
    }

    // EIS model IDs follow the convention "<provider>-<model>" (except Elastic's own models like
    // "elser_model_2", which have no dash and are skipped). Default behavior: use the prefix as
    // the Opik provider name and strip it from the model.
    //
    // Overrides are only needed when:
    //   - the prefix differs from the Opik canonical provider name (e.g. "jina" -> "jina_ai"), or
    //   - the underlying provider's pricing JSON keys include the prefix in the model name and we
    //     must NOT strip it (e.g. Jina: "jina-embeddings-v3").
    private static final Map<String, EisRule> EIS_PREFIX_OVERRIDES = Map.of(
            "jina", new EisRule("jina_ai", false),
            "google", new EisRule("google_ai", true),
            "microsoft", new EisRule("azure", true));

    /**
     * Claims spans an EIS client emitted, which is only those reporting {@code elastic} alongside a
     * model whose ID carries a routed provider prefix. Elastic's own models ({@code elser_model_2})
     * have no prefix to read and are left alone.
     */
    @Override
    public boolean appliesTo(Resolution resolution) {
        return StringUtils.isNotBlank(resolution.model())
                && ELASTIC_SYSTEM.equalsIgnoreCase(resolution.provider())
                && resolution.model().indexOf('-') > 0;
    }

    /**
     * Returns the rewritten (underlying provider, underlying model) pair and records the originals
     * in the given metadata.
     */
    @Override
    public Resolution apply(Resolution resolution, ObjectNode metadata) {
        String model = resolution.model();
        String provider = resolution.provider();

        int dash = model.indexOf('-');
        String prefix = model.substring(0, dash).toLowerCase(Locale.ROOT);
        // Default: prefix IS the Opik provider, strip it from the model. Override per-prefix
        // when the underlying provider needs different handling.
        EisRule rule = EIS_PREFIX_OVERRIDES.getOrDefault(prefix, new EisRule(prefix, true));

        metadata.put(METADATA_ORIGINAL_SYSTEM, provider);
        metadata.put(METADATA_ORIGINAL_MODEL, model);

        String rewrittenModel = rule.stripPrefix() ? model.substring(dash + 1) : model;
        log.debug("Rewrote EIS attributes: provider '{}' -> '{}', model '{}' -> '{}'",
                provider, rule.opikProvider(), model, rewrittenModel);
        return new Resolution(rewrittenModel, rule.opikProvider());
    }
}
