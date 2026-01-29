package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class SpanUserDefinedMetricPythonCodeManufacturer
        extends
            AbstractTypeManufacturer<SpanUserDefinedMetricPythonCode> {

    public static final SpanUserDefinedMetricPythonCodeManufacturer INSTANCE = new SpanUserDefinedMetricPythonCodeManufacturer();

    private final Random random = new Random();

    @Override
    public SpanUserDefinedMetricPythonCode getType(
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

        // Generate arguments map
        Map<String, String> arguments = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            arguments.put(RandomStringUtils.insecure().nextAlphanumeric(10),
                    RandomStringUtils.insecure().nextAlphanumeric(10));
        }

        // Generate scoreConfig map with serializable values
        Map<String, Object> scoreConfig = new HashMap<>();
        for (int i = 0; i < 2; i++) {
            scoreConfig.put(RandomStringUtils.insecure().nextAlphanumeric(10),
                    generateSerializableValue());
        }

        return SpanUserDefinedMetricPythonCode.builder()
                .metric(RandomStringUtils.insecure().nextAlphanumeric(20))
                .arguments(arguments)
                .commonMetricId(RandomStringUtils.insecure().nextAlphanumeric(15))
                .initConfig(initConfig)
                .scoreConfig(scoreConfig)
                .build();
    }

    /**
     * Generates a random serializable value (String, Integer, or Boolean)
     * that Jackson can properly serialize without precision issues.
     * Avoids Double to prevent floating-point precision issues during JSON serialization.
     */
    private Object generateSerializableValue() {
        int type = random.nextInt(3);
        return switch (type) {
            case 0 -> RandomStringUtils.insecure().nextAlphanumeric(15);
            case 1 -> random.nextInt(1000);
            case 2 -> random.nextBoolean();
            default -> RandomStringUtils.insecure().nextAlphanumeric(15);
        };
    }
}
