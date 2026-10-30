package com.comet.opik.domain.cost;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.comet.opik.api.ModelCostData;
import com.comet.opik.utils.JsonUtils;
import lombok.Builder;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceResolverEquivalenceTest {

    private static final List<String> OTHER_PROVIDERS = Arrays.asList(null, "", "api.openai.com",
            "api.together.xyz:443", "llm-gateway.example.com");

    private static final Set<BundledName> BUNDLED_NAMES = bundledNames();

    @Builder(toBuilder = true)
    private record BundledName(@NonNull String name, @NonNull String provider) {
    }

    private static Stream<Arguments> nameVariants() {
        return Stream.of(
                Arguments.of("as bundled", (BinaryOperator<String>) (name, provider) -> name),
                Arguments.of("upper case", (BinaryOperator<String>) (name, provider) -> name.toUpperCase(Locale.ROOT)),
                Arguments.of("dots between digits",
                        (BinaryOperator<String>) (name, provider) -> name.replaceAll("(\\d)-(\\d)", "$1.$2")),
                Arguments.of("hyphenated date", (BinaryOperator<String>) (name, provider) -> name + "-2025-08-07"),
                Arguments.of("compact date", (BinaryOperator<String>) (name, provider) -> name + "-20250807"),
                Arguments.of("upper case with a date",
                        (BinaryOperator<String>) (name, provider) -> (name + "-2025-08-07").toUpperCase(Locale.ROOT)),
                Arguments.of("version pin", (BinaryOperator<String>) (name, provider) -> name + ":0"),
                Arguments.of("own provider prefix", (BinaryOperator<String>) (name, provider) -> provider + "/" + name),
                Arguments.of("openai prefix", (BinaryOperator<String>) (name, provider) -> "openai/" + name));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nameVariants")
    void resolveMatchesLegacyLookup(String variant, BinaryOperator<String> toModelName) {
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        int priced = 0;

        // Most of these lookups miss, and each miss logs at debug: left on, they add about 29 MB to the test report.
        var resolverLogger = ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(ModelPriceResolver.class.getName());
        var previousLevel = resolverLogger.getLevel();
        resolverLogger.setLevel(Level.INFO);
        try {
            for (BundledName bundled : BUNDLED_NAMES) {
                String modelName = toModelName.apply(bundled.name(), bundled.provider());
                for (String provider : providersFor(bundled.provider())) {
                    ModelPrice expected = LegacyModelPriceLookup.find(modelName, provider);
                    ModelPrice actual = ModelPriceResolver.resolve(modelName, provider)
                            .map(ModelPriceResolver.PriceMatch::price)
                            .orElse(null);
                    // The same object, not an equal one: the resolver must hand back the very row the table holds.
                    if (actual != expected) {
                        mismatches.add("model '%s', provider '%s'".formatted(modelName, provider));
                    }
                    compared++;
                    if (expected != null) {
                        priced++;
                    }
                }
            }
        } finally {
            resolverLogger.setLevel(previousLevel);
        }

        assertThat(compared).isGreaterThan(BUNDLED_NAMES.size());
        assertThat(priced).isPositive();
        assertThat(mismatches).isEmpty();
    }

    private static List<String> providersFor(String bundledProvider) {
        var providers = new LinkedHashSet<String>();
        providers.addAll(ProviderRegistry.priceLookupOrder(bundledProvider));
        providers.add(bundledProvider.toUpperCase(Locale.ROOT));
        var all = new ArrayList<>(providers);
        all.addAll(OTHER_PROVIDERS);
        return all;
    }

    private static Set<BundledName> bundledNames() {
        Map<String, ModelCostData> upstream = ModelPriceTable.readModelCosts();
        Map<String, ModelCostData> overrides;
        try {
            overrides = JsonUtils.readJsonFile(ModelPriceTable.MODEL_PRICES_OVERRIDES_FILE,
                    ModelPriceTable.MODEL_COSTS_TYPE_REFERENCE);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        var names = new LinkedHashSet<BundledName>();
        upstream.forEach((key, cost) -> add(names, key, cost.litellmProvider()));
        overrides.forEach((key, cost) -> {
            String provider = StringUtils.isNotBlank(cost.aliasOf()) && upstream.containsKey(cost.aliasOf())
                    ? upstream.get(cost.aliasOf()).litellmProvider()
                    : cost.litellmProvider();
            add(names, key, provider);
        });
        return names;
    }

    private static void add(Set<BundledName> names, String key, String provider) {
        if (StringUtils.isNotBlank(provider)) {
            names.add(BundledName.builder()
                    .name(ModelPriceTable.parseModelName(key))
                    .provider(provider)
                    .build());
        }
    }
}
