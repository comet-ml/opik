package com.comet.opik.domain.workspaces;

import com.comet.opik.api.OpikVersion;
import com.comet.opik.domain.ExperimentDAO;
import com.comet.opik.domain.OptimizationDAO;
import com.comet.opik.infrastructure.CacheConfiguration;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.cache.CacheManager;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

/**
 * Unauthenticated deployment ({@code authentication.enabled=false}).
 * No external auth service available. Auth-suggested version is always null;
 * fallback on failure is version_1 (safe default).
 */
@Slf4j
public class UnauthWorkspaceVersionService extends AbstractWorkspaceVersionService {

    public UnauthWorkspaceVersionService(@NonNull TransactionTemplate transactionTemplate,
            @NonNull ExperimentDAO experimentDAO,
            @NonNull OptimizationDAO optimizationDAO,
            @NonNull ServiceTogglesConfig serviceTogglesConfig,
            @NonNull CacheManager cacheManager,
            @NonNull CacheConfiguration cacheConfiguration) {
        super(transactionTemplate, experimentDAO, optimizationDAO, serviceTogglesConfig, cacheManager,
                cacheConfiguration);
    }

    @Override
    protected OpikVersion getFallbackVersion(String workspaceId, OpikVersion authSuggestedVersion) {
        log.warn("Unauth fallback: returning version_1 for workspace '{}'", workspaceId);
        return OpikVersion.VERSION_1;
    }
}
