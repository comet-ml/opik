package com.comet.opik.domain.cost;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceListTest {

    @Test
    void aListSurvivesTheCachesJsonRoundTrip() {
        var list = new ModelPriceList("org-1", 7, List.of(
                CustomModelPrice.builder()
                        .modelName("gpt-5-mini")
                        .provider("azure")
                        .kind(CustomModelPrice.Kind.FIXED)
                        .inputPerMillion(new BigDecimal("0.00000025"))
                        .outputPerMillion(new BigDecimal("10.00"))
                        .build(),
                CustomModelPrice.builder()
                        .modelName("team-mini")
                        .provider(CustomModelPrice.ANY_PROVIDER)
                        .kind(CustomModelPrice.Kind.ALIAS)
                        .aliasModelName("gpt-5-mini")
                        .aliasProvider(CustomModelPrice.SPAN_PROVIDER)
                        .build()));

        var cached = JsonUtils.readValue(JsonUtils.writeValueAsString(list), ModelPriceList.class);

        assertThat(cached).usingRecursiveComparison()
                .withComparatorForType(Comparator.naturalOrder(), BigDecimal.class)
                .isEqualTo(list);
        assertThat(cached.toLayers().find("team-mini", CustomModelPrice.ANY_PROVIDER)).isNotNull();
    }
}
