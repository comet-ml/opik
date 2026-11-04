package com.comet.opik.domain.cost;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

/**
 * The {@code total_estimated_cost_version} a span gets for a cost Opik computed. A blank version means the client
 * sent the cost, which is why an Opik-computed cost of 0 stays blank unless the version carries something to find.
 * The column is {@code LowCardinality}, so the version names a price list, never a row.
 */
@UtilityClass
public class CostVersion {

    // ESTIMATED COST CHANGE
    // 1.1 - Added cached tokens for OpenAI
    public static final String ESTIMATED = "1.1";

    static final String ORGANIZATION_PREFIX = ";o";
    static final String BUILT_IN_PREFIX = ";b";
    static final String LOAD_FAILED_SUFFIX = ";fb";

    /**
     * {@code 1.1;o<list version>} when the organization's rows set the cost, {@code 1.1;b<price files>} for built-in
     * prices, {@code 1.1;fb} when the organization's prices couldn't be loaded, even at 0 for a span with usage, as
     * those are the spans to find later. A cost from {@code metadata.cost} keeps plain {@code 1.1}.
     */
    public static String of(@NonNull CostService.PricedCost priced, @NonNull PriceLayers layers, boolean hasUsage) {
        if (priced.source() == CostService.CostSource.ORGANIZATION) {
            return ESTIMATED + ORGANIZATION_PREFIX + layers.getListVersion();
        }
        if (layers.getOrigin() == PriceLayers.Origin.LOAD_FAILED && (hasUsage || priced.cost().signum() > 0)) {
            return ESTIMATED + LOAD_FAILED_SUFFIX;
        }
        if (priced.cost().signum() <= 0) {
            return "";
        }
        if (priced.source() == CostService.CostSource.BUILT_IN
                && layers.getOrigin() != PriceLayers.Origin.DISABLED) {
            return ESTIMATED + BUILT_IN_PREFIX + ModelPriceTable.pricesFingerprint();
        }
        return ESTIMATED;
    }
}
