package com.comet.opik.infrastructure;

import com.comet.opik.domain.LocalWorkspaceFeaturesService;
import com.comet.opik.domain.RemoteWorkspaceFeaturesService;
import com.comet.opik.domain.WorkspaceFeaturesService;
import com.comet.opik.utils.RetryUtils;
import com.google.common.base.Preconditions;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Objects;

/**
 * Picks where per-organization features come from. With authentication off there is no platform, so every feature
 * is on and the deployment's service toggles decide; with it on, the platform's workspace features API answers.
 */
public class WorkspaceFeaturesModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public WorkspaceFeaturesService workspaceFeaturesService(
            @Config("authentication") AuthenticationConfig authentication,
            @Config("workspaceFeatures") WorkspaceFeaturesConfig config,
            @NonNull RetriableHttpClient retriableHttpClient,
            @NonNull RedissonReactiveClient redissonClient) {

        if (!authentication.isEnabled()) {
            return new LocalWorkspaceFeaturesService();
        }

        Objects.requireNonNull(authentication.getReactService(),
                "The property authentication.reactService.url is required when authentication is enabled");

        Preconditions.checkArgument(StringUtils.isNotBlank(authentication.getReactService().url()),
                "The property authentication.reactService.url must not be blank when authentication is enabled");

        // The same platform as authentication, on a request's path: the auth hop's timeout and retries apply.
        return new RemoteWorkspaceFeaturesService(retriableHttpClient, authentication.getReactService(),
                redissonClient, config.getCacheTtl().toJavaDuration(),
                authentication.getRequestTimeout().toJavaDuration(),
                RetryUtils.handleHttpErrors(authentication.getRequestMaxRetries(),
                        authentication.getRequestRetryMinBackoff().toJavaDuration(),
                        authentication.getRequestRetryMaxBackoff().toJavaDuration()));
    }
}
