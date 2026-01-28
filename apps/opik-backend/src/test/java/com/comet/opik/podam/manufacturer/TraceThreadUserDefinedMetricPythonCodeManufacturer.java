package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class TraceThreadUserDefinedMetricPythonCodeManufacturer
        extends
            AbstractTypeManufacturer<TraceThreadUserDefinedMetricPythonCode> {

    public static final TraceThreadUserDefinedMetricPythonCodeManufacturer INSTANCE = new TraceThreadUserDefinedMetricPythonCodeManufacturer();

    private final Random random = new Random();

    @Override
    public TraceThreadUserDefinedMetricPythonCode getType(
            DataProviderStrategy dataProviderStrategy,
            AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {

        // Generate serializable initConfig with String, Number, and Boolean values
        Map<String, Object> initConfig = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            String key = RandomStringUtils.insecure().nextAlphanumeric(10);
            Object value = generateSerializableValue();
            initConfig.put(key, value);
        }

        return TraceThreadUserDefinedMetricPythonCode.builder()
                .metric(RandomStringUtils.insecure().nextAlphanumeric(20))
                .commonMetricId(RandomStringUtils.insecure().nextAlphanumeric(15))
                .initConfig(initConfig)
                .build();
    }

    /**
     * Generates a random serializable value (String, Integer, Double, or Boolean)
     * that Jackson can properly serialize.
     */
    private Object generateSerializableValue() {
        int type = random.nextInt(4);
        return switch (type) {
            case 0 -> RandomStringUtils.insecure().nextAlphanumeric(15);
            case 1 -> random.nextInt(1000);
            case 2 -> random.nextDouble() * 100;
            case 3 -> random.nextBoolean();
            default -> RandomStringUtils.insecure().nextAlphanumeric(15);
        };
    }
}
