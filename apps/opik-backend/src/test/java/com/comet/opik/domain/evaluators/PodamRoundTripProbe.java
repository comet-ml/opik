package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.Test;

class PodamRoundTripProbe {
    @Test
    void probe() {
        var factory = PodamFactoryUtils.newPodamFactory();
        for (int i = 0; i < 5; i++) {
            var api = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class);
            var msg = api.getCode().messages().getFirst();
            System.out.println("PODAM msg content=" + msg.content()
                    + " | contentArray=" + msg.contentArray());
            var domain = AutomationModelEvaluatorMapper.INSTANCE.map(api.getCode());
            var storedContent = domain.messages().getFirst().content();
            System.out.println("  stored=" + storedContent);
            try {
                var back = AutomationModelEvaluatorMapper.INSTANCE.map(domain);
                var m = back.messages().getFirst();
                System.out.println("  readback content=" + m.content() + " array=" + m.contentArray());
            } catch (Throwable t) {
                System.out.println("  READBACK THREW " + t.getClass().getName() + ": " + t.getMessage());
            }
            System.out.println("  json=" + JsonUtils.writeValueAsString(domain).substring(0, Math.min(200,
                    JsonUtils.writeValueAsString(domain).length())));
        }
    }
}
