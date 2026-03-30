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

import java.util.Optional;

/**
 * Authenticated deployment ({@code authentication.enabled=true}).
 * Uses external auth service for one-way V2 gate and fallback.
 *
 * <p>TODO (OPIK-5170): Once auth response includes workspace metadata (opikVersion / createdAt),
 * implement {@link #getAuthSuggestedVersion} to read from cached auth data.
 * The metadata should be cached in Redis alongside the existing auth cache (same TTL).</p>
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
    protected Optional<OpikVersion> getAuthSuggestedVersion(String workspaceId) {
        // TODO (OPIK-5170): Read suggested version from cached auth response metadata.
        //  If auth says 'version_2' (workspace created post-launch), return VERSION_2 — one-way gate.
        log.debug("Auth one-way gate check skipped — OPIK-5170 not yet implemented, workspace '{}'", workspaceId);
        return Optional.empty();
    }

    @Override
    protected OpikVersion getFallbackVersion(String workspaceId) {
        // TODO (OPIK-5170): Use cached auth suggestion as fallback instead of hardcoded version_1.
        log.info("Auth fallback: returning version_1 for workspace '{}' (auth fallback not yet available)",
                workspaceId);
        return OpikVersion.VERSION_1;
    }
}
