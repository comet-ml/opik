package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;

/**
 * The Qwen calls of a nightly update: one generate call with a prompt, the answer's JSON schema and a token cap, returning
 * the answer's {@code output} (an object, or text holding one). A failure throws. {@code tag} names the call in logs and
 * lets the fixture test pick the recorded answer: {@code revise}, {@code verify}, {@code naming_c<group>}.
 */
@FunctionalInterface
public interface NightLlm {

    JsonNode generate(@NonNull String tag, @NonNull String prompt, @NonNull JsonNode schema, int maxTokens);
}
