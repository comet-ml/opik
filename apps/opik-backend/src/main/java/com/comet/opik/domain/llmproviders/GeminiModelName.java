package com.comet.opik.domain.llmproviders;

import lombok.RequiredArgsConstructor;

/*
Langchain4j doesn't provide gemini models enum.
This information is taken from: https://ai.google.dev/gemini-api/docs/models/gemini
 */
@RequiredArgsConstructor
public enum GeminiModelName {
    GEMINI_2_0_FLASH("gemini-2.0-flash-exp"),
    GEMINI_1_5_FLASH("gemini-1.5-flash"),
    GEMINI_1_5_FLASH_8B("gemini-1.5-flash-8b"),
    GEMINI_1_5_PRO("gemini-1.5-pro"),
    GEMINI_1_0_PRO("gemini-1.0-pro"),
    TEXT_EMBEDDING("text-embedding-004"),
    AQA("aqa");

    private final String value;

    @Override
    public String toString() {
        return value;
    }
}
