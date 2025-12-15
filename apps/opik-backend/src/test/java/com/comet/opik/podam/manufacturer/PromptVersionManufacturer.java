package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.PromptVersion;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.api.PodamUtils;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class PromptVersionManufacturer extends AbstractTypeManufacturer<PromptVersion> {

    public static final PromptVersionManufacturer INSTANCE = new PromptVersionManufacturer();

    private static final String TEMPLATE = """
            Hi {{%s}},

            This is a test prompt. The current time is {{%s}}.

            Regards,
            {{%s}}
            """;

    @Override
    public PromptVersion getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {

        UUID id = strategy.getTypeValue(metadata, context, UUID.class);

        String variable1 = RandomStringUtils.randomAlphanumeric(5);
        String variable2 = RandomStringUtils.randomAlphanumeric(5);
        String variable3 = RandomStringUtils.randomAlphanumeric(5);

        String template = String.format(TEMPLATE, variable1, variable2, variable3);

        var tags = IntStream.range(0, 5)
                .mapToObj(i -> "tag-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .collect(Collectors.toSet());

        return PromptVersion.builder()
                .id(id)
                .commit(id.toString().substring(id.toString().length() - 8))
                .template(template)
                .metadata(strategy.getTypeValue(metadata, context, JsonNode.class))
                .changeDescription(strategy.getTypeValue(metadata, context, String.class))
                .type(randomPromptType())
                .tags(tags)
                .promptId(strategy.getTypeValue(metadata, context, UUID.class))
                .createdBy(strategy.getTypeValue(metadata, context, String.class))
                .createdAt(Instant.now())
                .build();
    }

    public PromptType randomPromptType() {
        return PromptType.values()[PodamUtils.getIntegerInRange(0, PromptType.values().length - 1)];
    }
}
