package com.comet.opik.domain.cost;

import com.comet.opik.domain.cost.CostService.CostSource;
import com.comet.opik.domain.cost.CostService.PricedCost;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CostVersionTest {

    private static final PriceLayers ORGANIZATION = PriceLayers.of("org-1", 7, List.of());

    private static PricedCost priced(String cost, CostSource source) {
        return new PricedCost(new BigDecimal(cost), source);
    }

    @Test
    void organizationCostNamesTheListVersion() {
        assertThat(CostVersion.of(priced("2.00", CostSource.ORGANIZATION), ORGANIZATION, true)).isEqualTo("1.1;o7");
    }

    @Test
    void freeOrganizationCostStillNamesTheListVersion() {
        assertThat(CostVersion.of(priced("0", CostSource.ORGANIZATION), ORGANIZATION, true)).isEqualTo("1.1;o7");
    }

    @Test
    void builtInCostNamesThePriceFiles() {
        var version = CostVersion.of(priced("0.45", CostSource.BUILT_IN), PriceLayers.NO_ORGANIZATION, true);

        assertThat(version).isEqualTo("1.1;b" + ModelPriceTable.pricesFingerprint());
        assertThat(version).matches("1\\.1;b[0-9a-f]{8}");
    }

    @Test
    void builtInCostOfAnOrganizationWithoutAMatchingRowNamesThePriceFiles() {
        assertThat(CostVersion.of(priced("0.45", CostSource.BUILT_IN), ORGANIZATION, true))
                .isEqualTo("1.1;b" + ModelPriceTable.pricesFingerprint());
    }

    @Test
    void builtInCostWithCustomPricesSwitchedOffKeepsThePlainVersion() {
        assertThat(CostVersion.of(priced("0.45", CostSource.BUILT_IN), PriceLayers.BUILT_IN_ONLY, true))
                .isEqualTo("1.1");
    }

    @Test
    void metadataCostKeepsThePlainVersion() {
        assertThat(CostVersion.of(priced("0.45", CostSource.METADATA), PriceLayers.NO_ORGANIZATION, false))
                .isEqualTo("1.1");
    }

    @Test
    void failedLoadIsStampedEvenAtZeroForASpanWithUsage() {
        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.LOAD_FAILED, true)).isEqualTo("1.1;fb");
    }

    @Test
    void failedLoadIsStampedOnABuiltInCost() {
        assertThat(CostVersion.of(priced("0.45", CostSource.BUILT_IN), PriceLayers.LOAD_FAILED, true))
                .isEqualTo("1.1;fb");
    }

    @Test
    void failedLoadKeepsThePlainVersionOfAMetadataCostWithoutUsage() {
        assertThat(CostVersion.of(priced("0.45", CostSource.METADATA), PriceLayers.LOAD_FAILED, false))
                .isEqualTo("1.1");
    }

    @Test
    void failedLoadIsStampedOnAMetadataCostWithUsage() {
        assertThat(CostVersion.of(priced("0.45", CostSource.METADATA), PriceLayers.LOAD_FAILED, true))
                .isEqualTo("1.1;fb");
    }

    @Test
    void failedLoadLeavesASpanWithoutUsageOrCostBlank() {
        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.LOAD_FAILED, false)).isEmpty();
    }

    @Test
    void zeroCostIsBlank() {
        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.NO_ORGANIZATION, true)).isEmpty();
        assertThat(CostVersion.of(priced("0", CostSource.NONE), PriceLayers.BUILT_IN_ONLY, true)).isEmpty();
    }

    @Test
    void priceFilesFingerprintIsStable() {
        assertThat(ModelPriceTable.pricesFingerprint()).matches("[0-9a-f]{8}")
                .isEqualTo(ModelPriceTable.pricesFingerprint());
    }
}
