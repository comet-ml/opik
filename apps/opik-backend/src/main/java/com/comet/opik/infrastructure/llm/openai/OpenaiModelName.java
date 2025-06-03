package com.comet.opik.infrastructure.llm.openai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;

/**
 * This information is taken from <a href="https://platform.openai.com/docs/models">openai docs</a>
 */
@Slf4j
@RequiredArgsConstructor
public enum OpenaiModelName {
    CHATGPT_4O_LATEST("chatgpt-4o-latest"),
    GPT_4O("gpt-4o"),
    GPT_4O_2024_05_13("gpt-4o-2024-05-13"),
    GPT_4O_2024_08_06("gpt-4o-2024-08-06"),
    GPT_4O_2024_11_20("gpt-4o-2024-11-20"),
    GPT_4O_MINI("gpt-4o-mini"),
    GPT_4O_MINI_2024_07_18("gpt-4o-mini-2024-07-18"),
    GPT_3_5_TURBO("gpt-3.5-turbo"),
    GPT_3_5_TURBO_1106("gpt-3.5-turbo-1106"),
    GPT_3_5_TURBO_0125("gpt-3.5-turbo-0125"),
    GPT_4("gpt-4"),
    GPT_4_0613("gpt-4-0613"),
    GPT_4_0314("gpt-4-0314"),
    GPT_4_TURBO("gpt-4-turbo"),
    GPT_4_TURBO_2024_04_09("gpt-4-turbo-2024-04-09"),
    GPT_4_TURBO_PREVIEW("gpt-4-turbo-preview"),
    GPT_4_1106_PREVIEW("gpt-4-1106-preview"),
    GPT_4_0125_PREVIEW("gpt-4-0125-preview"),
    GPT_O1("o1"),
    GPT_O1_2024_12_17("o1-2024-12-17"),
    GPT_O1_MINI("o1-mini"),
    GPT_O1_MINI_2024_09_12("o1-mini-2024-09-12"),
    GPT_O1_PREVIEW("o1-preview"),
    GPT_O1_PREVIEW_2024_09_12("o1-preview-2024-09-12"),
    GPT_O3_MINI("o3-mini"),
    ;

    private static final String WARNING_UNKNOWN_MODEL = "could not find OpenaiModelName with value '{}'";

    private final String value;

    public static Optional<OpenaiModelName> byValue(String value) {
        var response = Arrays.stream(OpenaiModelName.values())
                .filter(modelName -> modelName.value.equals(value))
                .findFirst();
        if (response.isEmpty()) {
            log.warn(WARNING_UNKNOWN_MODEL, value);
        }
        return response;
    }

    @Override
    public String toString() {
        return value;
    }
}
