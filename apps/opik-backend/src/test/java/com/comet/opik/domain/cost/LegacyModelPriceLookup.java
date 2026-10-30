package com.comet.opik.domain.cost;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * A frozen copy of CostService's built-in price lookup as it was before ModelPriceResolver replaced it. It is the
 * reference ModelPriceResolverEquivalenceTest holds the resolver to, so it must not be edited to follow the resolver.
 */
@UtilityClass
class LegacyModelPriceLookup {

    private static final String DATE_SUFFIX_PATTERN = "-\\d{4}-?(0[1-9]|1[0-2])-?(0[1-9]|[12]\\d|3[01])$";
    private static final String VERSION_SUFFIX_PATTERN = ":\\d+$";

    ModelPrice find(String modelName, String provider) {
        if (StringUtils.isBlank(modelName)) {
            return null;
        }
        var resolvedProvider = ProviderRegistry.resolve(provider, modelName);
        if (resolvedProvider.isEmpty()) {
            return null;
        }
        List<String> lookupOrder = ProviderRegistry.priceLookupOrder(resolvedProvider.get());
        String originalModelName = modelName;
        modelName = ModelPriceTable.parseModelName(modelName);

        for (String candidateProvider : lookupOrder) {
            ModelPrice price = findUnderProvider(modelName, candidateProvider);
            if (price != null) {
                return price;
            }
        }

        int prefixSlash = originalModelName.indexOf('/');
        if (prefixSlash > 0) {
            String providerFromPrefix = ProviderRegistry.toLiteLlmName(originalModelName.substring(0, prefixSlash));
            if (!lookupOrder.contains(providerFromPrefix)) {
                for (String candidateProvider : ProviderRegistry.priceLookupOrder(providerFromPrefix)) {
                    ModelPrice prefixMatch = findUnderProvider(modelName, candidateProvider);
                    if (prefixMatch != null) {
                        return prefixMatch;
                    }
                }
            }
        }
        return null;
    }

    private ModelPrice findUnderProvider(String modelName, String provider) {
        ModelPrice exactMatch = ModelPriceTable.get(modelName, provider);
        if (exactMatch != null) {
            return exactMatch;
        }

        String normalizedModelName = modelName.replace('.', '-').toLowerCase(Locale.ROOT);
        if (!normalizedModelName.equals(modelName)) {
            ModelPrice match = ModelPriceTable.get(normalizedModelName, provider);
            if (match != null) {
                return match;
            }
        }

        String baseOriginalModelName = stripDateSuffix(modelName);
        if (!baseOriginalModelName.equals(modelName)) {
            ModelPrice match = ModelPriceTable.get(baseOriginalModelName, provider);
            if (match != null) {
                return match;
            }
        }

        String baseNormalizedModelName = stripDateSuffix(normalizedModelName);
        if (!baseNormalizedModelName.equals(normalizedModelName)) {
            ModelPrice match = ModelPriceTable.get(baseNormalizedModelName, provider);
            if (match != null) {
                return match;
            }
        }

        String baseOriginalVersionName = stripVersionSuffix(modelName);
        if (!baseOriginalVersionName.equals(modelName)) {
            ModelPrice match = ModelPriceTable.get(baseOriginalVersionName, provider);
            if (match != null) {
                return match;
            }
        }

        String baseNormalizedVersionName = stripVersionSuffix(normalizedModelName);
        if (!baseNormalizedVersionName.equals(normalizedModelName)) {
            ModelPrice match = ModelPriceTable.get(baseNormalizedVersionName, provider);
            if (match != null) {
                return match;
            }
        }

        return null;
    }

    private String stripDateSuffix(String modelName) {
        return modelName.toLowerCase(Locale.ROOT).replaceFirst(DATE_SUFFIX_PATTERN, "");
    }

    private String stripVersionSuffix(String modelName) {
        return modelName.toLowerCase(Locale.ROOT).replaceFirst(VERSION_SUFFIX_PATTERN, "");
    }
}
