package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.PromptVersion;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public class PromptVersionManufacturer extends AbstractTypeManufacturer<PromptVersion> {

    public static final PromptVersionManufacturer INSTANCE = new PromptVersionManufacturer();

    public static final String TEMPLATE = """
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

        return PromptVersion.builder()
                .id(id)
                .commit(id.toString().substring(id.toString().length() - 8))
                .template(TEMPLATE.format(variable1, variable2, variable3))
                .variables(Set.of(variable1, variable2, variable3))
                .promptId(strategy.getTypeValue(metadata, context, UUID.class))
                .createdBy(strategy.getTypeValue(metadata, context, String.class))
                .createdAt(Instant.now())
                .build();
    }
}
