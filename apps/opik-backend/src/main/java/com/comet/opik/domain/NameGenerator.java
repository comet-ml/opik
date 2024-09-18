package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.security.SecureRandom;
import java.util.List;

@Builder
public class NameGenerator {

    private final @NonNull SecureRandom secureRandom;

    private final @NonNull List<String> adjectives;
    private final @NonNull List<String> nouns;

    public String generateName() {
        var adjective = getRandom(adjectives);
        var noun = getRandom(nouns);
        var number = secureRandom.nextInt(0, 10000);
        return "%s_%s_%s".formatted(adjective, noun, number);
    }

    private String getRandom(List<String> strings) {
        int index = secureRandom.nextInt(0, strings.size());
        return strings.get(index);
    }
}
