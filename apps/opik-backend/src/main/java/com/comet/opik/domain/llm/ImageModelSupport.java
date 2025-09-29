package com.comet.opik.domain.llm;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ImageModelSupport {

    private static final Set<String> EXACT_MODELS = Set.of(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4o-2024-08-06",
            "gpt-4o-2024-05-13",
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "gpt-4-turbo",
            "gpt-4-turbo-preview",
            "gpt-4-turbo-2024-04-09",
            "gpt-4-1106-preview",
            "gpt-4o-mini-2024-07-18",
            "gpt-5",
            "gpt-5-mini",
            "gpt-5-chat-latest",
            "o1",
            "o1-mini",
            "o3",
            "o3-mini",
            "o4-mini");

    private static final List<String> KEYWORD_MATCHES = List.of(
            "gpt-4o",
            "gpt-4.1",
            "gpt-5",
            "o1",
            "o3",
            "o4");

    private ImageModelSupport() {
    }

    static boolean supportsImageInput(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }

        String normalized = model.toLowerCase(Locale.ENGLISH);

        if (EXACT_MODELS.contains(normalized)) {
            return true;
        }

        return KEYWORD_MATCHES.stream().anyMatch(normalized::contains);
    }
}
