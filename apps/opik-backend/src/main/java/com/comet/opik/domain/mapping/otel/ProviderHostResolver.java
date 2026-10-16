package com.comet.opik.domain.mapping.otel;

import com.comet.opik.domain.cost.CostService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.net.InternetDomainName;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * Maps a provider reported as its API host onto the canonical Opik provider.
 * <p>
 * Instrumentations wrapping an OpenAI-compatible SDK that was pointed at another provider through
 * its base URL sometimes report that URL's host instead of a provider name: {@code api.cerebras.ai},
 * {@code api.deepseek.com}, {@code api.x.ai}. None of those is a price-table key, so the span costs
 * 0 (#7772).
 * <p>
 * Only a registered domain ({@code cerebras.ai}) or its {@code api.} subdomain is claimed, and the
 * domain has to sit under an ICANN registry suffix, so a proxy at {@code openai.internal} or an app
 * at {@code deepseek.vercel.app} is not priced as the provider it is named after. The domain's label
 * is matched against {@link CostService#isKnownProvider} on its own and with the suffix attached,
 * either joined by {@code _} or directly, because some canonical names carry it: {@code fireworks_ai}
 * for {@code api.fireworks.ai}, {@code xai} for {@code api.x.ai}. A host that matches none of those
 * candidates is left as reported.
 */
@Slf4j
public class ProviderHostResolver implements ProviderResolver {

    private static final String API_LABEL = "api";

    /** Claims dotted values only, and only those that are not already a canonical provider. */
    @Override
    public boolean appliesTo(Resolution resolution) {
        String provider = resolution.provider();
        return StringUtils.contains(provider, '.') && !CostService.isKnownProvider(provider);
    }

    /** Returns the canonical provider named by the host, or the pair unchanged when it names none. */
    @Override
    public Resolution apply(Resolution resolution, ObjectNode metadata) {
        String host = StringUtils.removeEnd(resolution.provider().toLowerCase(Locale.ROOT), ".");
        if (!InternetDomainName.isValid(host)) {
            return resolution;
        }

        InternetDomainName domain = InternetDomainName.from(host);
        if (!domain.isUnderRegistrySuffix()) {
            return resolution;
        }

        InternetDomainName registered = domain.topDomainUnderRegistrySuffix();
        boolean apiHost = domain.hasParent() && domain.parent().equals(registered)
                && API_LABEL.equals(domain.parts().getFirst());
        if (!domain.equals(registered) && !apiHost) {
            return resolution;
        }

        String name = registered.parts().getFirst();
        String suffix = registered.registrySuffix().toString();
        return Stream.of(name, name + "_" + suffix, name + suffix)
                .filter(CostService::isKnownProvider)
                .findFirst()
                .map(canonical -> {
                    log.debug("Resolved provider host '{}' to canonical '{}'", resolution.provider(), canonical);
                    return new Resolution(resolution.model(), canonical);
                })
                .orElse(resolution);
    }
}
