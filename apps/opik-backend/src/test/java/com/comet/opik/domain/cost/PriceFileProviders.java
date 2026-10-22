package com.comet.opik.domain.cost;

import com.comet.opik.utils.JsonUtils;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The providers Opik prices under, read once for the tests that check other names against them. The price files are
 * replaced by automated syncs, so those checks also guard every sync: a provider renamed or dropped upstream fails
 * them instead of silently losing its prices.
 */
@UtilityClass
public class PriceFileProviders {

    /** Every litellm_provider of LiteLLM's price file and of Opik's OpenRouter and overrides files. */
    public final Set<String> ALL = load();

    private Set<String> load() {
        var providers = new TreeSet<String>();
        var priceFiles = List.of(CostService.MODEL_PRICES_FILE, CostService.MODEL_PRICES_OPENROUTER_FILE,
                CostService.MODEL_PRICES_OVERRIDES_FILE);
        for (var file : priceFiles) {
            try {
                var costsByModel = JsonUtils.readJsonFile(file, CostService.MODEL_COSTS_TYPE_REFERENCE);
                costsByModel.forEach((model, cost) -> {
                    if (!model.equals("sample_spec") && StringUtils.isNotBlank(cost.litellmProvider())) {
                        providers.add(cost.litellmProvider());
                    }
                });
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return Collections.unmodifiableSet(providers);
    }
}
