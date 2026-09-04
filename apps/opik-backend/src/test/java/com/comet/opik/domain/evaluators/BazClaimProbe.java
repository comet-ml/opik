package com.comet.opik.domain.evaluators;

import dev.langchain4j.data.message.ChatMessageType;
import org.junit.jupiter.api.Test;

class BazClaimProbe {
    @Test
    void probe() {
        for (String content : new String[]{"[null]", "[{}]", "[{\"foo\": \"bar\"}]"}) {
            try {
                var m = AutomationModelEvaluatorMapper.INSTANCE
                        .map(new LlmAsJudgeCodeMessage(ChatMessageType.USER.name(), content));
                System.out.println("PROBE " + content + " -> content=" + m.content()
                        + " contentArray=" + m.contentArray());
            } catch (Throwable t) {
                System.out.println("PROBE " + content + " -> THREW " + t.getClass().getName() + ": " + t.getMessage());
            }
        }
    }
}
