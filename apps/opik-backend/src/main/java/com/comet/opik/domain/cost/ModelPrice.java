package com.comet.opik.domain.cost;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;

@RequiredArgsConstructor
@Getter
public enum ModelPrice {
    gpt_4o("gpt-4o", 2.5, 10, SpanCostCalculator::textGenerationCost),
    gpt_4o_2024_08_06("gpt-4o-2024-08-06", 2.5, 10, SpanCostCalculator::textGenerationCost),
    gpt_4o_audio_preview("gpt-4o-audio-preview", 2.5, 10, SpanCostCalculator::textGenerationCost),
    gpt_4o_audio_preview_2024_10_01("gpt-4o-audio-preview-2024-10-01", 2.5, 10, SpanCostCalculator::textGenerationCost),
    gpt_4o_2024_05_13("gpt-4o-2024-05-13", 5, 15, SpanCostCalculator::textGenerationCost),
    gpt_4o_mini("gpt-4o-mini", 0.15, 0.6, SpanCostCalculator::textGenerationCost),
    gpt_4o_mini_2024_07_18("gpt-4o-mini-2024-07-18", 0.15, 0.6, SpanCostCalculator::textGenerationCost),
    o1_preview("o1-preview", 15, 60, SpanCostCalculator::textGenerationCost),
    o1_preview_2024_09_12("o1-preview-2024-09-12", 15, 60, SpanCostCalculator::textGenerationCost),
    o1_mini("o1-mini", 3, 12, SpanCostCalculator::textGenerationCost),
    o1_mini_2024_09_12("o1-mini-2024-09-12", 3, 12, SpanCostCalculator::textGenerationCost),
    gpt_4o_realtime_preview("gpt-4o-realtime-preview", 5, 20, SpanCostCalculator::textGenerationCost),
    gpt_4o_realtime_preview_2024_10_01("gpt-4o-realtime-preview-2024-10-01", 5, 20,
            SpanCostCalculator::textGenerationCost),
    chatgpt_4o_latest("chatgpt-4o-latest", 5, 15, SpanCostCalculator::textGenerationCost),
    gpt_4_turbo("gpt-4-turbo", 10, 30, SpanCostCalculator::textGenerationCost),
    gpt_4_turbo_2024_04_09("gpt-4-turbo-2024-04-09", 10, 30, SpanCostCalculator::textGenerationCost),
    gpt_4("gpt-4", 30, 60, SpanCostCalculator::textGenerationCost),
    gpt_4_32k("gpt-4-32k", 60, 120, SpanCostCalculator::textGenerationCost),
    gpt_4_0125_preview("gpt-4-0125-preview", 10, 30, SpanCostCalculator::textGenerationCost),
    gpt_4_1106_preview("gpt-4-1106-preview", 10, 30, SpanCostCalculator::textGenerationCost),
    gpt_4_vision_preview("gpt-4-vision-preview", 10, 30, SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo("gpt-3.5-turbo", 1.5, 2, SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_0125("gpt-3.5-turbo-0125", 0.5, 1.5, SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_instruct("gpt-3.5-turbo-instruct", 1.5, 2, SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_1106("gpt-3.5-turbo-1106", 1, 2, SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_0613("gpt-3.5-turbo-0613", 1.5, 2, SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_16k_0613("gpt-3.5-turbo-16k-0613", 3, 4, SpanCostCalculator::textGenerationCost),
    gpt_3_5_turbo_0301("gpt-3.5-turbo-0301", 1.5, 2, SpanCostCalculator::textGenerationCost),
    davinci_002("davinci-002", 2, 2, SpanCostCalculator::textGenerationCost),
    babbage_002("babbage-002", .4, 0.4, SpanCostCalculator::textGenerationCost),
    DEFAULT("", 0, 0, SpanCostCalculator::defaultCost);

    private final String name;
    private final double inputPricePer1M;
    private final double outputPricePer1M;
    private final BiFunction<ModelPrice, Map<String, Integer>, Double> calculator;

    public static ModelPrice fromString(String modelName) {
        return Arrays.stream(values())
                .filter(modelPrice -> modelPrice.name.equals(modelName))
                .findFirst()
                .orElse(DEFAULT);
    }

    public double calculateCost(Map<String, Integer> usage) {
        return calculator.apply(this, usage);
    }
}
