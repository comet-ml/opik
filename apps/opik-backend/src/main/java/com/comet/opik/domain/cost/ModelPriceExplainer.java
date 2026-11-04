package com.comet.opik.domain.cost;

import com.comet.opik.api.modelprice.CustomModelPriceResolution;
import com.comet.opik.api.modelprice.CustomModelPriceResolveRequest;
import com.comet.opik.api.modelprice.CustomModelPriceRow;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@UtilityClass
class ModelPriceExplainer {

    private static final String ORGANIZATION = "organization";
    private static final String BUILT_IN = "built_in";
    private static final String NONE = "none";

    CustomModelPriceResolution explain(@NonNull CustomModelPriceResolveRequest request, @NonNull PriceLayers layers) {
        Map<String, Integer> usage = Optional.ofNullable(request.usage()).orElse(Map.of());
        CostService.PricedCost priced = CostService.price(request.model(), request.provider(), usage, null, layers);
        Optional<ModelPriceResolver.PriceMatch> match = ModelPriceResolver.resolve(request.model(),
                request.provider(), layers);
        return CustomModelPriceResolution.builder()
                .model(request.model())
                .provider(request.provider())
                .cost(priced.cost().stripTrailingZeros().toPlainString())
                .costSource(priced.source().name().toLowerCase(Locale.ROOT))
                .listVersion(layers.getListVersion())
                .match(match.map(ModelPriceExplainer::matchOf).orElse(null))
                .lines(match.map(ModelPriceExplainer::linesOf).orElse(List.of()))
                .build();
    }

    private CustomModelPriceResolution.Match matchOf(ModelPriceResolver.PriceMatch match) {
        return new CustomModelPriceResolution.Match(
                match.provider(),
                match.nameForm().name().toLowerCase(Locale.ROOT),
                Optional.ofNullable(match.customRow()).map(CustomModelPriceRow::from).orElse(null),
                Optional.ofNullable(match.targetRow()).map(CustomModelPriceRow::from).orElse(null));
    }

    private List<CustomModelPriceResolution.Line> linesOf(ModelPriceResolver.PriceMatch match) {
        CustomModelPrice decidingRow = fixedRowOf(match);
        ModelPrice price = match.price();
        return List.of(
                line("input", price.inputPrice(), decidingRow, CustomModelPrice::inputPerMillion),
                line("output", price.outputPrice(), decidingRow, CustomModelPrice::outputPerMillion),
                line("cache_read", price.cacheReadInputTokenPrice(), decidingRow,
                        CustomModelPrice::cacheReadPerMillion),
                line("cache_write", price.cacheCreationInputTokenPrice(), decidingRow,
                        CustomModelPrice::cacheWritePerMillion));
    }

    private @Nullable CustomModelPrice fixedRowOf(ModelPriceResolver.PriceMatch match) {
        CustomModelPrice row = match.customRow();
        return row != null && row.kind() == CustomModelPrice.Kind.FIXED ? row : match.targetRow();
    }

    private CustomModelPriceResolution.Line line(String name, BigDecimal perToken, @Nullable CustomModelPrice row,
            Function<CustomModelPrice, BigDecimal> field) {
        boolean fromOrganization = row != null && field.apply(row) != null;
        String source = fromOrganization ? ORGANIZATION : perToken.signum() > 0 ? BUILT_IN : NONE;
        return new CustomModelPriceResolution.Line(name, perToken.movePointRight(6).stripTrailingZeros()
                .toPlainString(), source);
    }
}
