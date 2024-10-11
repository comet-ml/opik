package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemInputValue;
import com.comet.opik.api.DatasetItemSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DatasetItemTypeManufacturer extends AbstractTypeManufacturer<DatasetItem> {

    public static final Random RANDOM = new Random();

    public static final DatasetItemTypeManufacturer INSTANCE = new DatasetItemTypeManufacturer();

    @Override
    public DatasetItem getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {

        var source = DatasetItemSource.values()[RANDOM.nextInt(DatasetItemSource.values().length)];

        var traceId = Set.of(DatasetItemSource.TRACE, DatasetItemSource.SPAN).contains(source)
                ? strategy.getTypeValue(metadata, context, UUID.class)
                : null;

        var spanId = source == DatasetItemSource.SPAN
                ? strategy.getTypeValue(metadata, context, UUID.class)
                : null;

        Map<String, DatasetItemInputValue<?>> inputData = IntStream.range(0, 5)
                .mapToObj(i -> Map.entry(RandomStringUtils.randomAlphanumeric(10),
                        (DatasetItemInputValue<?>) strategy.getTypeValue(metadata, context,
                                DatasetItemInputValue.class)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return DatasetItem.builder()
                .source(source)
                .traceId(traceId)
                .spanId(spanId)
                .id(strategy.getTypeValue(metadata, context, UUID.class))
                .input(strategy.getTypeValue(metadata, context, JsonNode.class))
                .expectedOutput(strategy.getTypeValue(metadata, context, JsonNode.class))
                .metadata(strategy.getTypeValue(metadata, context, JsonNode.class))
                .inputData(inputData)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .build();
    }
}
