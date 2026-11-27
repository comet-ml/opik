package com.comet.opik.domain.cost;

import com.comet.opik.utils.JsonUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceListTest {

    @Test
    void aListSurvivesTheCachesJsonRoundTrip() {
        var list = ModelPriceList.builder()
                .organizationId(RandomStringUtils.secure().nextAlphanumeric(10))
                .version(7)
                .rows(List.of(
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
                                .build()))
                .build();

        var cached = JsonUtils.readValue(JsonUtils.writeValueAsString(list), ModelPriceList.class);

        assertThat(cached).usingRecursiveComparison()
                .withComparatorForType(Comparator.naturalOrder(), BigDecimal.class)
                .isEqualTo(list);
        assertThat(cached.toLayers().find("team-mini", CustomModelPrice.ANY_PROVIDER)).isNotNull();
    }
}
