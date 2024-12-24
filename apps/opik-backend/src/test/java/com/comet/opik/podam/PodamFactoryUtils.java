package com.comet.opik.podam;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.podam.manufacturer.BigDecimalTypeManufacturer;
import com.comet.opik.podam.manufacturer.CategoricalFeedbackDetailTypeManufacturer;
import com.comet.opik.podam.manufacturer.DatasetItemTypeManufacturer;
import com.comet.opik.podam.manufacturer.JsonNodeTypeManufacturer;
import com.comet.opik.podam.manufacturer.NumericalFeedbackDetailTypeManufacturer;
import com.comet.opik.podam.manufacturer.PromptVersionManufacturer;
import com.comet.opik.podam.manufacturer.ProviderApiKeyManufacturer;
import com.comet.opik.podam.manufacturer.ProviderApiKeyUpdateManufacturer;
import com.comet.opik.podam.manufacturer.UUIDTypeManufacturer;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;
import uk.co.jemos.podam.api.RandomDataProviderStrategy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.FeedbackDefinition.CategoricalFeedbackDefinition;
import static com.comet.opik.api.FeedbackDefinition.NumericalFeedbackDefinition;

public class PodamFactoryUtils {

    public static PodamFactory newPodamFactory() {
        var podamFactory = new PodamFactoryImpl();
        var strategy = ((RandomDataProviderStrategy) podamFactory.getStrategy());
        strategy.addOrReplaceAttributeStrategy(Pattern.class, PatternStrategy.INSTANCE);
        strategy.addOrReplaceAttributeStrategy(DecimalMax.class, BigDecimalStrategy.INSTANCE);
        strategy.addOrReplaceAttributeStrategy(DecimalMin.class, BigDecimalStrategy.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(BigDecimal.class, BigDecimalTypeManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(UUID.class, UUIDTypeManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(
                NumericalFeedbackDefinition.NumericalFeedbackDetail.class,
                new NumericalFeedbackDetailTypeManufacturer());
        strategy.addOrReplaceTypeManufacturer(
                CategoricalFeedbackDefinition.CategoricalFeedbackDetail.class,
                new CategoricalFeedbackDetailTypeManufacturer());
        strategy.addOrReplaceTypeManufacturer(JsonNode.class, JsonNodeTypeManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(DatasetItem.class, DatasetItemTypeManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(PromptVersion.class, PromptVersionManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(ProviderApiKey.class, ProviderApiKeyManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(ProviderApiKeyUpdate.class, ProviderApiKeyUpdateManufacturer.INSTANCE);
        return podamFactory;
    }

    public static <T> List<T> manufacturePojoList(PodamFactory podamFactory, Class<T> pojoClass) {
        return podamFactory.manufacturePojo(ArrayList.class, pojoClass);
    }
}
