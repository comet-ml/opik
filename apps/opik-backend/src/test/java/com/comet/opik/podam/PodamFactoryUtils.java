package com.comet.opik.podam;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.podam.manufacturer.BigDecimalTypeManufacturer;
import com.comet.opik.podam.manufacturer.CategoricalFeedbackDetailTypeManufacturer;
import com.comet.opik.podam.manufacturer.DatasetItemTypeManufacturer;
import com.comet.opik.podam.manufacturer.GuardrailCheckTypeManufacturer;
import com.comet.opik.podam.manufacturer.JsonNodeTypeManufacturer;
import com.comet.opik.podam.manufacturer.NumericalFeedbackDetailTypeManufacturer;
import com.comet.opik.podam.manufacturer.PromptVersionManufacturer;
import com.comet.opik.podam.manufacturer.ProviderApiKeyManufacturer;
import com.comet.opik.podam.manufacturer.ProviderApiKeyUpdateManufacturer;
import com.comet.opik.podam.manufacturer.StartMultipartUploadRequestManufacturer;
import com.comet.opik.podam.manufacturer.UUIDTypeManufacturer;
import com.comet.opik.podam.manufacturer.anthropic.AnthropicContentManufacturer;
import com.comet.opik.podam.manufacturer.anthropic.AnthropicCreateMessageResponseManufacturer;
import com.comet.opik.podam.manufacturer.anthropic.AnthropicUsageManufacturer;
import com.comet.opik.podam.manufacturer.anthropic.ChatCompletionRequestManufacturer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.api.AnthropicUsage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;
import uk.co.jemos.podam.api.RandomDataProviderStrategy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        strategy.addOrReplaceTypeManufacturer(AnthropicContent.class, AnthropicContentManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(AnthropicUsage.class, AnthropicUsageManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(AnthropicCreateMessageResponse.class,
                AnthropicCreateMessageResponseManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(ChatCompletionRequest.class, ChatCompletionRequestManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(StartMultipartUploadRequest.class,
                StartMultipartUploadRequestManufacturer.INSTANCE);
        strategy.addOrReplaceTypeManufacturer(Guardrail.class, GuardrailCheckTypeManufacturer.INSTANCE);

        return podamFactory;
    }

    public static <T> List<T> manufacturePojoList(PodamFactory podamFactory, Class<T> pojoClass) {
        return podamFactory.manufacturePojo(ArrayList.class, pojoClass);
    }

    public static <T> Set<T> manufacturePojoSet(PodamFactory podamFactory, Class<T> pojoClass) {
        return podamFactory.manufacturePojo(Set.class, pojoClass);
    }

    public static <K, V> Map<K, V> manufacturePojoMap(
            PodamFactory podamFactory, Class<K> keyClass, Class<V> valueClass) {
        return podamFactory.manufacturePojo(Map.class, keyClass, valueClass);
    }
}
