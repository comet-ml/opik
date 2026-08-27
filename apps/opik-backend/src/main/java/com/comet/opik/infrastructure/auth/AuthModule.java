package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.LocalWorkspacePermissionsService;
import com.comet.opik.domain.RemoteWorkspacePermissionsService;
import com.comet.opik.domain.WorkspacePermissionsService;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.redaction.RedactionService;
import com.google.common.base.Preconditions;
import com.google.inject.Provides;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Objects;

@Slf4j
public class AuthModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public AuthService authService(
            @Config("authentication") AuthenticationConfig config,
            @NonNull Provider<RequestContext> requestContext,
            @NonNull RedissonReactiveClient redissonClient,
            @NonNull Client client,
            @NonNull RedactionService redactionService,
            @NonNull WorkspacePermissionsService workspacePermissionsService) {

        if (!config.isEnabled()) {
            if (redactionService.isEnabled()) {
                // original_data_view is resolved from the workspace permissions API and nothing else
                // sets it, so with authentication off no caller can hold it and every response is masked for
                // everybody, with no way to grant an exemption. Said out loud because the configuration reads
                // as if it were a per-caller control here, and it is not.
                log.warn("Read-time redaction is enabled while authentication is disabled: no caller can hold "
                        + "'{}', so every response will be redacted for every caller",
                        WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue());
            }

            return new AuthServiceImpl(requestContext);
        }

        Objects.requireNonNull(config.getReactService(),
                "The property authentication.reactService.url is required when authentication is enabled");

        Preconditions.checkArgument(StringUtils.isNotBlank(config.getReactService().url()),
                "The property authentication.reactService.url must not be blank when authentication is enabled");

        var cacheService = config.getApiKeyResolutionCacheTTLInSec() > 0
                ? new AuthCredentialsCacheService(redissonClient, config.getApiKeyResolutionCacheTTLInSec())
                : new NoopCacheService();

        // Asking RedactionService rather than the raw config so the request and the thing that acts on the
        // answer cannot disagree: a deployment with the flag on but no rules redacts nothing, and must not pay
        // for permissions it will not use.
        return new RemoteAuthService(client, config.getReactService(), requestContext, cacheService,
                workspacePermissionsService, redactionService.isEnabled());
    }

    @Provides
    @Singleton
    public WorkspacePermissionsService workspacePermissionsService(
            @Config("authentication") AuthenticationConfig config,
            @NonNull Client client) {

        if (!config.isEnabled()) {
            return new LocalWorkspacePermissionsService();
        }

        Objects.requireNonNull(config.getReactService(),
                "The property authentication.reactService.url is required when authentication is enabled");

        Preconditions.checkArgument(StringUtils.isNotBlank(config.getReactService().url()),
                "The property authentication.reactService.url must not be blank when authentication is enabled");

        return new RemoteWorkspacePermissionsService(client, config.getReactService());
    }
}
