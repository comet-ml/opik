package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.NonNull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One question of a decide call. A {@code noul} question asks yes or no and is answered with the probability of
 * yes; a {@code choice} question picks one option out of {@code criteria} and is answered with a probability per
 * option.
 */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecideQuestion(@NonNull String type, @NonNull String instructions, Object criteria) {

    public static final String NOUL = "noul";
    public static final String CHOICE = "choice";

    public static DecideQuestion noul(@NonNull String instructions) {
        return new DecideQuestion(NOUL, instructions, null);
    }

    public static DecideQuestion noul(@NonNull String instructions, @NonNull String whenTrue,
            @NonNull String whenFalse) {
        return new DecideQuestion(NOUL, instructions, Map.of("true", whenTrue, "false", whenFalse));
    }

    /** The options go without descriptions: the option text itself is what the model reads. */
    public static DecideQuestion choice(@NonNull String instructions, @NonNull Collection<String> options) {
        var criteria = new LinkedHashMap<String, String>();
        options.forEach(option -> criteria.put(option, null));
        return new DecideQuestion(CHOICE, instructions, criteria);
    }
}
