package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;

/**
 * This information is taken from <a href="https://platform.openai.com/docs/models">openai docs</a>
 */
@Slf4j
@RequiredArgsConstructor
public enum OpenaiModelName implements StructuredOutputSupported {
    CHATGPT_4O_LATEST("chatgpt-4o-latest", true),
    GPT_4O("gpt-4o", true),
    GPT_4O_2024_05_13("gpt-4o-2024-05-13", false),
    GPT_4O_2024_08_06("gpt-4o-2024-08-06", true),
    GPT_4O_2024_11_20("gpt-4o-2024-11-20", true),
    GPT_4O_MINI("gpt-4o-mini", true),
    GPT_4O_MINI_2024_07_18("gpt-4o-mini-2024-07-18", true),
    GPT_4_1("gpt-4.1", true),
    GPT_4_1_MINI("gpt-4.1-mini", true),
    GPT_4_1_NANO("gpt-4.1-nano", true),
    GPT_3_5_TURBO("gpt-3.5-turbo", false),
    GPT_3_5_TURBO_1106("gpt-3.5-turbo-1106", false),
    GPT_3_5_TURBO_0125("gpt-3.5-turbo-0125", false),
    GPT_4("gpt-4", false),
    GPT_4_0613("gpt-4-0613", false),
    GPT_4_0314("gpt-4-0314", false),
    GPT_4_TURBO("gpt-4-turbo", false),
    GPT_4_TURBO_2024_04_09("gpt-4-turbo-2024-04-09", false),
    GPT_4_TURBO_PREVIEW("gpt-4-turbo-preview", false),
    GPT_4_1106_PREVIEW("gpt-4-1106-preview", false),
    GPT_4_0125_PREVIEW("gpt-4-0125-preview", false),
    GPT_O1("o1", false),
    GPT_O1_2024_12_17("o1-2024-12-17", false),
    GPT_O1_MINI("o1-mini", false),
    GPT_O1_MINI_2024_09_12("o1-mini-2024-09-12", false),
    GPT_O1_PREVIEW("o1-preview", false),
    GPT_O1_PREVIEW_2024_09_12("o1-preview-2024-09-12", false),
    GPT_O3("o3", true),
    GPT_O3_MINI("o3-mini", false),
    GPT_O4_MINI("o4-mini", true),
    GPT_5("gpt-5", true),
    GPT_5_MINI("gpt-5-mini", true),
    GPT_5_NANO("gpt-5-nano", true),
    GPT_5_CHAT_LATEST("gpt-5-chat-latest", false),
    GPT_5_1("gpt-5.1", true),
    GPT_5_3_CODEX("gpt-5.3-codex", true),
    GPT_5_2("gpt-5.2", true),
    GPT_5_2_CHAT_LATEST("gpt-5.2-chat-latest", false),
    ;

    private static final String WARNING_UNKNOWN_MODEL = "could not find OpenaiModelName with value '{}'";

    private final String value;
    private final boolean structuredOutputSupported;

    @Override
    public boolean isStructuredOutputSupported() {
        return this.structuredOutputSupported;
    }

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
