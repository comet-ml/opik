package com.comet.opik.api;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.redisson.client.codec.Codec;
import org.redisson.codec.CompositeCodec;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.codec.LZ4CodecV2;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum AutomationRuleEvaluatorType {

    LLM_AS_JUDGE(Constants.LLM_AS_JUDGE, new CompositeCodec(new LZ4CodecV2(), new JsonJacksonCodec(JsonUtils.MAPPER)));
    // for non-java consumers, use StringCodec.INSTANCE as producer codec (no composite), unless testing

    @JsonValue
    private final String type;
    private final Codec messageCodec;

    public static AutomationRuleEvaluatorType fromString(String type) {
        return Arrays.stream(values())
                .filter(v -> v.type.equals(type)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown evaluator type: " + type));
    }

    @UtilityClass
    public static class Constants {
        public static final String LLM_AS_JUDGE = "llm_as_judge";
    }
}
