package com.comet.opik.domain.automations;

import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.api.annotationqueue.ScoreConditionOperator;
import com.comet.opik.api.automations.Automation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutomationExecutorTest {

    private static Automation.StepConditions regressionsAbove(double value) {
        return Automation.StepConditions.builder().groups(List.of(Automation.StepConditions.Group.builder()
                .conditions(List.of(Automation.StepConditions.Condition.builder()
                        .stepOutput("run.regressions").operator(ScoreConditionOperator.GREATER_THAN).value(value)
                        .build()))
                .build())).build();
    }

    @Test
    @DisplayName("An only_if holds on a produced output and never on a missing one")
    void onlyIfHoldsOnOutputs() {
        assertThat(AutomationExecutor.holds(regressionsAbove(0), Map.of("run.regressions", 2L))).isTrue();
        assertThat(AutomationExecutor.holds(regressionsAbove(0), Map.of("run.regressions", 0))).isFalse();
        assertThat(AutomationExecutor.holds(regressionsAbove(0), Map.of("run.pass_rate", 0.5))).isFalse();
        assertThat(AutomationExecutor.holds(regressionsAbove(0), Map.of())).isFalse();
    }

    @Test
    @DisplayName("A trigger fires on its own event and only for the prompt, suite or repository it names")
    void triggersFireOnWhatTheyName() {
        UUID promptId = UUID.randomUUID();
        var onPrompt = Automation.Trigger.builder().event("prompt:committed").promptId(promptId).build();
        var anyPrompt = Automation.Trigger.builder().event("prompt:committed").build();
        var onRepo = Automation.Trigger.builder().event("ci:run").repository("acme/demo").build();

        assertThat(
                AutomationExecutor.fires(onPrompt, event("prompt:committed", Map.of("prompt_id", promptId.toString()))))
                .isTrue();
        assertThat(AutomationExecutor.fires(onPrompt,
                event("prompt:committed", Map.of("prompt_id", UUID.randomUUID().toString()))))
                .isFalse();
        assertThat(AutomationExecutor.fires(anyPrompt, event("prompt:committed", Map.of()))).isTrue();
        assertThat(AutomationExecutor.fires(anyPrompt, event("ci:run", Map.of()))).isFalse();
        assertThat(AutomationExecutor.fires(onRepo, event("ci:run", Map.of("repository", "acme/demo")))).isTrue();
        assertThat(AutomationExecutor.fires(onRepo, event("ci:run", Map.of("repository", "acme/other")))).isFalse();
    }

    @Test
    @DisplayName("A chat template becomes its messages; a text template is one user message")
    void templatesBecomeMessages() {
        var chat = PromptVersion.builder().id(UUID.randomUUID()).templateStructure(TemplateStructure.CHAT)
                .template(
                        "[{\"role\":\"system\",\"content\":\"Be brief.\"},{\"role\":\"user\",\"content\":\"{{input}}\"}]")
                .build();
        var text = PromptVersion.builder().id(UUID.randomUUID()).templateStructure(TemplateStructure.TEXT)
                .template("Answer {{input}}").build();

        assertThat(AutomationExecutor.messagesOf(chat))
                .extracting(ExperimentExecutionRequest.PromptVariant.Message::role)
                .containsExactly("system", "user");
        var single = AutomationExecutor.messagesOf(text);
        assertThat(single).hasSize(1);
        assertThat(single.getFirst().role()).isEqualTo("user");
        assertThat(single.getFirst().content().asText()).isEqualTo("Answer {{input}}");
    }

    private static AutomationEvent event(String name, Map<String, Object> payload) {
        return AutomationEvent.builder().workspaceId("ws").workspaceName("ws").userName("u").event(name)
                .itemId("item").payload(payload).depth(0).build();
    }
}
