package com.comet.opik.domain.llm;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

@UtilityClass
public class ModelAnswerText {

    // Bedrock's openai.gpt-oss models put their chain of thought in the answer text, as
    // "<reasoning>...</reasoning>{...}", and the block often holds a draft of the JSON, so the answer can't be found by
    // looking for the first brace. Other gateways do the same with <think> or <thinking>.
    private static final Pattern LEADING_REASONING = Pattern.compile(
            "\\A\\s*<(reasoning|think|thinking)>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    public static String withoutLeadingReasoning(@NonNull String text) {
        var answer = text;
        var matcher = LEADING_REASONING.matcher(answer);
        while (matcher.find()) {
            answer = answer.substring(matcher.end());
            matcher = LEADING_REASONING.matcher(answer);
        }
        return answer;
    }
}
