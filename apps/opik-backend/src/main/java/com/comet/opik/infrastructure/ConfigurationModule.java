package com.comet.opik.infrastructure;

import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class ConfigurationModule extends DropwizardAwareModule<OpikConfiguration> {

    @Override
    protected void configure() {
        var batchOperationsConfig = configuration(BatchOperationsConfig.class);
        var workspaceSettings = configuration(WorkspaceSettings.class);
        var webhookConfig = configuration(WebhookConfig.class);

        bind(BatchOperationsConfig.class).toInstance(batchOperationsConfig);
        bind(WorkspaceSettings.class).toInstance(workspaceSettings);
        bind(WebhookConfig.class).toInstance(webhookConfig);
    }
}
