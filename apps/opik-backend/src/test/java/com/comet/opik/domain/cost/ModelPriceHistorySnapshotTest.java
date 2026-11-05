package com.comet.opik.domain.cost;

import com.comet.opik.api.modelprice.CustomModelPriceRow;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceHistorySnapshotTest {

    private static final String MODEL = RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase(Locale.ROOT);
    private static final String TARGET = RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase(Locale.ROOT);
    private static final String NOTE = RandomStringUtils.secure().nextAlphanumeric(20);

    private static ModelPriceRow.ModelPriceRowBuilder stored(String provider, CustomModelPrice.Kind kind) {
        return ModelPriceRow.builder().id(UUID.randomUUID()).modelName(MODEL).provider(provider).kind(kind);
    }

    private static CustomModelPriceRow.CustomModelPriceRowBuilder listed(String provider, String kind) {
        return CustomModelPriceRow.builder().model(MODEL).provider(provider).kind(kind);
    }

    static Stream<Arguments> storedRows() {
        return Stream.of(
                Arguments.of(Named.of("fixed with every price, the tiny one kept in full",
                        stored("openai", CustomModelPrice.Kind.FIXED)
                                .inputPerMillion(new BigDecimal("0.0000000001"))
                                .outputPerMillion(new BigDecimal("8.000"))
                                .cacheReadPerMillion(new BigDecimal("1.25"))
                                .cacheWritePerMillion(new BigDecimal("100000"))
                                .note(NOTE)
                                .build()),
                        listed("openai", "fixed")
                                .inputPrice("0.0000000001")
                                .outputPrice("8")
                                .cacheReadPrice("1.25")
                                .cacheWritePrice("100000")
                                .note(NOTE)
                                .build()),
                Arguments.of(Named.of("prices left out stay absent",
                        stored("openai", CustomModelPrice.Kind.FIXED).outputPerMillion(new BigDecimal("0.6")).build()),
                        listed("openai", "fixed").outputPrice("0.6").build()),
                Arguments.of(Named.of("any provider",
                        stored(CustomModelPrice.ANY_PROVIDER, CustomModelPrice.Kind.FIXED)
                                .inputPerMillion(BigDecimal.ZERO)
                                .build()),
                        listed("", "fixed").inputPrice("0").build()),
                Arguments.of(Named.of("alias with its target's provider",
                        stored("bifrost", CustomModelPrice.Kind.ALIAS)
                                .aliasModelName(TARGET)
                                .aliasProvider("openai")
                                .build()),
                        listed("bifrost", "alias").aliasOf(new CustomModelPriceRow.AliasOf(TARGET, "openai")).build()),
                Arguments.of(Named.of("alias on the span's own provider",
                        stored(CustomModelPrice.ANY_PROVIDER, CustomModelPrice.Kind.ALIAS)
                                .aliasModelName(TARGET)
                                .aliasProvider(CustomModelPrice.SPAN_PROVIDER)
                                .build()),
                        listed("", "alias").aliasOf(new CustomModelPriceRow.AliasOf(TARGET, "")).build()));
    }

    @ParameterizedTest
    @MethodSource("storedRows")
    void rowOfReturnsTheStoredRowInTheListedShape(ModelPriceRow stored, CustomModelPriceRow expected) {
        var snapshot = ModelPriceStore.HistorySnapshot.jsonOf(stored);

        assertThat(ModelPriceStore.HistorySnapshot.rowOf(snapshot)).isEqualTo(expected);
    }

    @Test
    void rowOfReadsPricesStoredAsNumbersOrStringsAndIgnoresUnknownFields() {
        var snapshot = """
                {"model": "%s", "provider": "openai", "kind": "fixed", "input_price": 0.6, "output_price": "8.50",
                 "dropped_field": true}
                """.formatted(MODEL);

        assertThat(ModelPriceStore.HistorySnapshot.rowOf(snapshot))
                .isEqualTo(listed("openai", "fixed").inputPrice("0.6").outputPrice("8.5").build());
    }

    @Test
    void rowOfIsNullWithoutSnapshot() {
        assertThat(ModelPriceStore.HistorySnapshot.rowOf(null)).isNull();
    }
}
