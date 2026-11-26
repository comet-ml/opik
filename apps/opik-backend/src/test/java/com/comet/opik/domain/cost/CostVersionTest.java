package com.comet.opik.domain.cost;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CostVersionTest {

    private static final long LIST_VERSION = RandomUtils.secure().randomLong(1, 1_000);
    private static final PriceLayers ORGANIZATION = PriceLayers.of(RandomStringUtils.secure().nextAlphanumeric(10),
            LIST_VERSION, List.of());
    private static final Map<String, Integer> USAGE = Map.of("prompt_tokens", 1_000, "completion_tokens", 500);

    private static PricedCost priced(String cost, CostSource source) {
        return new PricedCost(new BigDecimal(cost), source);
    }

    @Test
    void organizationCostNamesTheListVersion() {
        assertThat(CostVersion.of(priced("2.00", CostSource.ORGANIZATION), ORGANIZATION, USAGE))
                .isEqualTo("1.1;o" + LIST_VERSION);
    }

    @Test
    void freeOrganizationCostStillNamesTheListVersion() {
        assertThat(CostVersion.of(priced("0", CostSource.ORGANIZATION), ORGANIZATION, USAGE))
                .isEqualTo("1.1;o" + LIST_VERSION);
    }

    @Test
    void builtInCostNamesThePriceFiles() {
        var version = CostVersion.of(priced("0.45", CostSource.BUILT_IN), PriceLayers.NO_ORGANIZATION, USAGE);

        assertThat(version).isEqualTo("1.1;b" + ModelPriceTable.pricesFingerprint());
        assertThat(version).matches("1\\.1;b[0-9a-f]{8}");
    }

    @Test
    void builtInCostOfAnOrganizationWithoutAMatchingRowNamesThePriceFiles() {
        assertThat(CostVersion.of(priced("0.45", CostSource.BUILT_IN), ORGANIZATION, USAGE))
                .isEqualTo("1.1;b" + ModelPriceTable.pricesFingerprint());
    }

    @Test
    void builtInCostWithCustomPricesSwitchedOffKeepsThePlainVersion() {
        assertThat(CostVersion.of(priced("0.45", CostSource.BUILT_IN), PriceLayers.BUILT_IN_ONLY, USAGE))
                .isEqualTo("1.1");
    }

    @Test
    void metadataCostKeepsThePlainVersion() {
        assertThat(CostVersion.of(priced("0.45", CostSource.METADATA), PriceLayers.NO_ORGANIZATION, null))
                .isEqualTo("1.1");
    }

    @Test
    void failedLoadIsStampedEvenAtZeroForASpanWithUsage() {
        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.LOAD_FAILED, USAGE)).isEqualTo("1.1;fb");
    }

    @Test
    void failedLoadIsStampedOnABuiltInCost() {
        assertThat(CostVersion.of(priced("0.45", CostSource.BUILT_IN), PriceLayers.LOAD_FAILED, USAGE))
                .isEqualTo("1.1;fb");
    }

    @Test
    void failedLoadKeepsThePlainVersionOfAMetadataCostWithoutUsage() {
        assertThat(CostVersion.of(priced("0.45", CostSource.METADATA), PriceLayers.LOAD_FAILED, null))
                .isEqualTo("1.1");
    }

    @Test
    void failedLoadIsStampedOnAMetadataCostWithUsage() {
        assertThat(CostVersion.of(priced("0.45", CostSource.METADATA), PriceLayers.LOAD_FAILED, USAGE))
                .isEqualTo("1.1;fb");
    }

    @Test
    void failedLoadLeavesASpanWithoutUsageOrCostBlank() {
        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.LOAD_FAILED, null)).isEmpty();
    }

    @Test
    void failedLoadLeavesUsageWithoutTokensUnmarked() {
        var nullCounts = new HashMap<String, Integer>();
        nullCounts.put("prompt_tokens", null);

        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.LOAD_FAILED,
                Map.of("prompt_tokens", 0, "completion_tokens", 0))).isEmpty();
        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.LOAD_FAILED, nullCounts)).isEmpty();
        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.LOAD_FAILED, Map.of())).isEmpty();
    }

    @Test
    void zeroCostIsBlank() {
        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.NO_ORGANIZATION, USAGE)).isEmpty();
        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.BUILT_IN_ONLY, USAGE)).isEmpty();
    }

    @Test
    void priceFilesFingerprintIsStable() {
        assertThat(ModelPriceTable.pricesFingerprint()).matches("[0-9a-f]{8}")
                .isEqualTo(ModelPriceTable.pricesFingerprint());
    }
}
