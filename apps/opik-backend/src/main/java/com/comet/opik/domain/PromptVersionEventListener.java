package com.comet.opik.domain;

import com.comet.opik.api.events.PromptVersionCreatedEvent;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class PromptVersionEventListener {

    private final @NonNull AgentConfigService agentConfigService;

    @Subscribe
    public void onPromptVersionCreated(PromptVersionCreatedEvent event) {
        log.info("Processing prompt version created event: promptId='{}', commit='{}', workspace='{}'",
                event.promptId(), event.commit(), event.workspaceId());

        agentConfigService.updateBlueprintsForNewPromptVersion(
                event.workspaceId(),
                event.promptId(),
                event.commit(),
                "auto-update",
                event.excludeProjectIds());
    }
}
