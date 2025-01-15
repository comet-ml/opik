package com.comet.opik.domain.cost;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

@RequiredArgsConstructor
@Getter
public enum ModelPrice {
    gpt_4o("gpt-4o", new BigDecimal("0.0000025"), new BigDecimal("0.000010"), SpanCostCalculator::textGenerationCost),
    //     TODO: "gpt-4o-2024-11-20"
    gpt_4o_2024_08_06("gpt-4o-2024-08-06", new BigDecimal("0.0000025"), new BigDecimal("0.000010"),
            SpanCostCalculator::textGenerationCost),
    gpt_4o_audio_preview("gpt-4o-audio-preview", new BigDecimal("0.0000025"), new BigDecimal("0.000010"),
            SpanCostCalculator::textGenerationCost),
    gpt_4o_audio_preview_2024_10_01("gpt-4o-audio-preview-2024-10-01", new BigDecimal("0.0000025"),
            new BigDecimal("0.000010"), SpanCostCalculator::textGenerationCost),
    gpt_4o_2024_05_13("gpt-4o-2024-05-13", new BigDecimal("0.000005"), new BigDecimal("0.000015"),
            SpanCostCalculator::textGenerationCost),
    gpt_4o_mini("gpt-4o-mini", new BigDecimal("0.00000015"), new BigDecimal("0.00000060"),
            SpanCostCalculator::textGenerationCost),
    gpt_4o_mini_2024_07_18("gpt-4o-mini-2024-07-18", new BigDecimal("0.00000015"), new BigDecimal("0.00000060"),
            SpanCostCalculator::textGenerationCost),
    o1_preview("o1-preview", new BigDecimal("0.000015"), new BigDecimal("0.000060"),
            SpanCostCalculator::textGenerationCost),
    o1_preview_2024_09_12("o1-preview-2024-09-12", new BigDecimal("0.000015"), new BigDecimal("0.000060"),
            SpanCostCalculator::textGenerationCost),
    o1_mini("o1-mini", new BigDecimal("0.000003"), new BigDecimal("0.000012"), SpanCostCalculator::textGenerationCost),
    o1_mini_2024_09_12("o1-mini-2024-09-12", new BigDecimal("0.000003"), new BigDecimal("0.000012"),
            SpanCostCalculator::textGenerationCost),
    gpt_4o_realtime_preview("gpt-4o-realtime-preview", new BigDecimal("0.000005"), new BigDecimal("0.000020"),
            SpanCostCalculator::textGenerationCost),
    gpt_4o_realtime_preview_2024_10_01("gpt-4o-realtime-preview-2024-10-01", new BigDecimal("0.000005"),
            new BigDecimal("0.000020"),
            SpanCostCalculator::textGenerationCost),
    chatgpt_4o_latest("chatgpt-4o-latest", new BigDecimal("0.000005"), new BigDecimal("0.000015"),
            SpanCostCalculator::textGenerationCost),
    gpt_4_turbo("gpt-4-turbo", new BigDecimal("0.000010"), new BigDecimal("0.000030"),
            SpanCostCalculator::textGenerationCost),
    gpt_4_turbo_2024_04_09("gpt-4-turbo-2024-04-09", new BigDecimal("0.000010"), new BigDecimal("0.000030"),
            SpanCostCalculator::textGenerationCost),
    gpt_4("gpt-4", new BigDecimal("0.000030"), new BigDecimal("0.000060"), SpanCostCalculator::textGenerationCost),
    //    TODO: "gpt-4-0613",
    //    TODO: "gpt-4-0314",
    gpt_4_32k("gpt-4-32k", new BigDecimal("0.000060"), new BigDecimal("0.000120"),
            SpanCostCalculator::textGenerationCost),
    //    TODO: "gpt-4-turbo-preview"
    gpt_4_0125_preview("gpt-4-0125-preview", new BigDecimal("0.000010"), new BigDecimal("0.000030"),
            SpanCostCalculator::textGenerationCost),
    gpt_4_1106_preview("gpt-4-1106-preview", new BigDecimal("0.000010"), new BigDecimal("0.000030"),
            SpanCostCalculator::textGenerationCost),
    gpt_4_vision_preview("gpt-4-vision-preview", new BigDecimal("0.000010"), new BigDecimal("0.000030"),
            SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo("gpt-3.5-turbo", new BigDecimal("0.0000015"), new BigDecimal("0.000002"),
            SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_0125("gpt-3.5-turbo-0125", new BigDecimal("0.00000050"), new BigDecimal("0.0000015"),
            SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_instruct("gpt-3.5-turbo-instruct", new BigDecimal("0.0000015"), new BigDecimal("0.000002"),
            SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_1106("gpt-3.5-turbo-1106", new BigDecimal("0.000001"), new BigDecimal("0.000002"),
            SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_0613("gpt-3.5-turbo-0613", new BigDecimal("0.0000015"), new BigDecimal("0.000002"),
            SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_16k_0613("gpt-3.5-turbo-16k-0613", new BigDecimal("0.000003"), new BigDecimal("0.000004"),
            SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_0301("gpt-3.5-turbo-0301", new BigDecimal("0.0000015"), new BigDecimal("0.000002"),
            SpanCostCalculator::textGenerationCost),
    davinci_002("davinci-002", new BigDecimal("0.000005"), new BigDecimal("0.000002"),
            SpanCostCalculator::textGenerationCost),
    babbage_002("babbage-002", new BigDecimal("0.0000004"), new BigDecimal("0.0000004"),
            SpanCostCalculator::textGenerationCost),

    // Update for 2024-12-18
    gpt_4o_mini_audio_preview_2024_12_17("gpt-4o-mini-audio-preview-2024-12-17", new BigDecimal("0.00000015"),
            new BigDecimal("0.0000006"),
            SpanCostCalculator::textGenerationCost),
    o1("o1", new BigDecimal("0.000015"), new BigDecimal("0.00006"),
            SpanCostCalculator::textGenerationCost),
    o1_2024_12_17("o1-2024-12-17", new BigDecimal("0.000015"), new BigDecimal("0.000060"),
            SpanCostCalculator::textGenerationCost),
    gpt_4o_realtime_preview_2024_12_17("gpt-4o-realtime-preview-2024-12-17", new BigDecimal("0.000005"),
            new BigDecimal("0.00002"),
            SpanCostCalculator::textGenerationCost),
    gpt_4o_mini_realtime_preview("gpt-4o-mini-realtime-preview", new BigDecimal("0.0000006"),
            new BigDecimal("0.0000024"),
            SpanCostCalculator::textGenerationCost),
    gpt_4o_mini_realtime_preview_2024_12_17("gpt-4o-mini-realtime-preview-2024-12-17", new BigDecimal("0.0000006"),
            new BigDecimal("0.0000024"),
            SpanCostCalculator::textGenerationCost),

    DEFAULT("", new BigDecimal("0"), new BigDecimal("0"), SpanCostCalculator::defaultCost);

    private final String name;
    private final BigDecimal inputPrice;
    private final BigDecimal outputPrice;
    private final BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator;

    public static ModelPrice fromString(String modelName) {
        return Arrays.stream(values())
                .filter(modelPrice -> modelPrice.name.equals(modelName))
                .findFirst()
                .orElse(DEFAULT);
    }

    public BigDecimal calculateCost(Map<String, Integer> usage) {
        return calculator.apply(this, Optional.ofNullable(usage).orElse(Map.of()));
    }
}
