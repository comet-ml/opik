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
 * Authenticated deployment ({@code authentication.enabled=true}).
 *
 * <p>Uses the auth-suggested {@code opikVersion} as a one-way V2 gate and fallback.
 * When {@code authSuggestedVersion} is {@code null} (e.g. auth service not deployed,
 * misconfigured, or errored), both behaviors gracefully degrade — gate is skipped,
 * fallback returns {@code version_1}.</p>
 * <p> The opikVersion is cached in Redis alongside the existing auth cache (same TTL).</p>
 */
@Slf4j
public class AuthWorkspaceVersionService extends AbstractWorkspaceVersionService {

    public AuthWorkspaceVersionService(@NonNull TransactionTemplate transactionTemplate,
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
        if (authSuggestedVersion != null) {
            log.info("Auth fallback: using auth suggestion '{}' for workspace '{}'",
                    authSuggestedVersion.getValue(), workspaceId);
            return authSuggestedVersion;
        }
        log.info("Auth fallback: returning version_1 for workspace '{}' (no auth suggestion available)", workspaceId);
        return OpikVersion.VERSION_1;
    }
}
