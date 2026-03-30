package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ExperimentDAO;
import com.comet.opik.domain.LocalWorkspacePermissionsService;
import com.comet.opik.domain.OptimizationDAO;
import com.comet.opik.domain.RemoteWorkspacePermissionsService;
import com.comet.opik.domain.WorkspacePermissionsService;
import com.comet.opik.domain.workspaces.AuthWorkspaceVersionService;
import com.comet.opik.domain.workspaces.UnauthWorkspaceVersionService;
import com.comet.opik.domain.workspaces.WorkspaceVersionService;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.CacheConfiguration;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.cache.CacheManager;
import com.google.common.base.Preconditions;
import com.google.inject.Provides;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Objects;

@Slf4j
public class AuthModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public AuthService authService(
            @Config("authentication") AuthenticationConfig config,
            @NonNull Provider<RequestContext> requestContext,
            @NonNull RedissonReactiveClient redissonClient) {

        if (!config.isEnabled()) {
            return new AuthServiceImpl(requestContext);
        }

        Objects.requireNonNull(config.getReactService(),
                "The property authentication.reactService.url is required when authentication is enabled");

        Preconditions.checkArgument(StringUtils.isNotBlank(config.getReactService().url()),
                "The property authentication.reactService.url must not be blank when authentication is enabled");

        var cacheService = config.getApiKeyResolutionCacheTTLInSec() > 0
                ? new AuthCredentialsCacheService(redissonClient, config.getApiKeyResolutionCacheTTLInSec())
                : new NoopCacheService();

        return new RemoteAuthService(client(), config.getReactService(), requestContext, cacheService);
    }

    public Client client() {
        return ClientBuilder.newClient();
    }

    @Provides
    @Singleton
    public WorkspacePermissionsService workspacePermissionsService(
            @Config("authentication") AuthenticationConfig config) {

        if (!config.isEnabled()) {
            return new LocalWorkspacePermissionsService();
        }

        Objects.requireNonNull(config.getReactService(),
                "The property authentication.reactService.url is required when authentication is enabled");

        Preconditions.checkArgument(StringUtils.isNotBlank(config.getReactService().url()),
                "The property authentication.reactService.url must not be blank when authentication is enabled");

        return new RemoteWorkspacePermissionsService(client(), config.getReactService());
    }

    @Provides
    @Singleton
    public WorkspaceVersionService workspaceVersionService(
            @Config("authentication") AuthenticationConfig authenticationConfig,
            @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @Config("cacheManager") CacheConfiguration cacheConfiguration,
            TransactionTemplate transactionTemplate,
            ExperimentDAO experimentDAO,
            OptimizationDAO optimizationDAO,
            CacheManager cacheManager) {
        if (!authenticationConfig.isEnabled()) {
            log.info("Authentication disabled, using UnauthWorkspaceVersionService");
            return new UnauthWorkspaceVersionService(
                    transactionTemplate, experimentDAO, optimizationDAO, serviceTogglesConfig, cacheManager,
                    cacheConfiguration);
        }
        log.info("Authentication enabled, using AuthWorkspaceVersionService");
        return new AuthWorkspaceVersionService(
                transactionTemplate, experimentDAO, optimizationDAO, serviceTogglesConfig, cacheManager,
                cacheConfiguration);
    }
}
