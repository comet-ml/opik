package com.comet.opik.domain.workspaces;

import com.comet.opik.api.OpikVersion;
import com.comet.opik.api.WorkspaceVersion;
import com.comet.opik.domain.AlertDAO;
import com.comet.opik.domain.DashboardDAO;
import com.comet.opik.domain.DatasetDAO;
import com.comet.opik.domain.DemoData;
import com.comet.opik.domain.ExperimentDAO;
import com.comet.opik.domain.OptimizationDAO;
import com.comet.opik.domain.PromptDAO;
import com.comet.opik.domain.evaluators.AutomationRuleDAO;
import com.comet.opik.infrastructure.CacheConfiguration;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.cache.CacheManager;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Optional;

import static com.comet.opik.infrastructure.ServiceTogglesConfig.FORCE_WORKSPACE_VERSION_DISABLED;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * Determines the Opik navigation version for a workspace.
 *
 * <p><b>The Opik backend is the single authority for version determination.</b>
 * The frontend calls {@code GET /v1/private/workspaces/versions} on workspace load
 * and never derives the version itself.</p>
 *
 * <h3>Determination Logic (priority order):</h3>
 * <ol>
 *   <li><b>V2 workspace allowlist</b> ({@code TOGGLE_V2_WORKSPACE_ALLOWLIST}) — comma-separated workspace IDs
 *       that always receive {@code version_2}. Highest priority, all deployment modes.</li>
 *   <li><b>Feature flag override</b> ({@code TOGGLE_FORCE_WORKSPACE_VERSION}) — if set to a valid
 *       {@link OpikVersion} value ({@code "version_1"} or {@code "version_2"}), that version is returned
 *       unconditionally. Value {@code "disabled"} means no override.</li>
 *   <li><b>Auth one-way V2 gate</b> (authenticated mode only) — if auth metadata indicates the workspace
 *       was created post-launch ({@code version_2}), always return {@code version_2}.
 *       Prevents V2 to V1 demotion. Defensive: gracefully degrades when auth service doesn't
 *       provide the field (returns null, falls through to entity check).</li>
 *   <li><b>Version 1 entity check</b> (primary signal, all modes) — queries the state database and
 *       analytics database for entities without {@code project_id}. If any exist, returns {@code version_1}.
 *       If none, returns {@code version_2}. Demo data is excluded from the check.</li>
 *   <li><b>Fallback on check failure</b> — if the DB queries error out:
 *       authenticated mode falls back to auth suggestion (defaults to {@code version_1} when unavailable);
 *       unauthenticated mode falls back to {@code version_1}.</li>
 * </ol>
 *
 * <h3>Entity Types Checked:</h3>
 * <ul>
 *   <li>State database (cheapest-first): dashboards, automation_rules (multi-project check), prompts, datasets (excl. demo) etc.</li>
 *   <li>Analytics database (cheapest-first): optimizations, experiments (excl. demo) etc.</li>
 *   <li>Not yet checked: alerts (no project_id column yet)</li>
 * </ul>
 *
 * <h3>Deployment Mode Behavior:</h3>
 * <ul>
 *   <li><b>Unauthenticated</b> ({@code authentication.enabled=false}): Uses {@link UnauthWorkspaceVersionService}.
 *       Auth steps are skipped. Determination uses entity check only, with {@code version_1} fallback on failure.</li>
 *   <li><b>Authenticated</b> ({@code authentication.enabled=true}): Uses {@link AuthWorkspaceVersionService}.
 *       Full determination logic including auth one-way gate.</li>
 * </ul>
 *
 * <h3>Caching:</h3>
 * <p>Results are cached in Redis per workspace using the {@code workspace_version} cache group.
 * The TTL is configured in the {@code cacheManager.caches.workspace_version} config property
 * (defaults to the global {@code cacheManager.defaultDuration}).
 * Cache is bypassed when the feature flag override is active or when caching is globally disabled.
 * Fallback results (from DB failures) are not cached to avoid masking transient issues.</p>
 *
 * @see OpikVersion
 * @see WorkspaceVersion
 */
public interface WorkspaceVersionService {
    Mono<WorkspaceVersion> getWorkspaceVersion(String workspaceId, OpikVersion authSuggestedVersion);

    Mono<Boolean> evictCache(String workspaceId);
}

/**
 * Base implementation of workspace version determination.
 * Encapsulates the shared version 1 entity check logic, feature flag handling, and Redis caching.
 * Subclasses implement deployment-mode-specific behavior (auth gate, fallback strategy).
 */
abstract class AbstractWorkspaceVersionService implements WorkspaceVersionService {

    private static final String CACHE_GROUP = "workspace_version";

    /**
     * Logger for the actual subclass, in order to have the correct class name in the logs.
     */
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    protected final TransactionTemplate transactionTemplate;
    protected final ExperimentDAO experimentDAO;
    protected final OptimizationDAO optimizationDAO;
    protected final ServiceTogglesConfig serviceTogglesConfig;
    protected final CacheManager cacheManager;
    protected final CacheConfiguration cacheConfiguration;

    protected AbstractWorkspaceVersionService(@NonNull TransactionTemplate transactionTemplate,
            @NonNull ExperimentDAO experimentDAO,
            @NonNull OptimizationDAO optimizationDAO,
            @NonNull ServiceTogglesConfig serviceTogglesConfig,
            @NonNull CacheManager cacheManager,
            @NonNull CacheConfiguration cacheConfiguration) {
        this.transactionTemplate = transactionTemplate;
        this.experimentDAO = experimentDAO;
        this.optimizationDAO = optimizationDAO;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.cacheManager = cacheManager;
        this.cacheConfiguration = cacheConfiguration;
    }

    @Override
    public Mono<WorkspaceVersion> getWorkspaceVersion(@NonNull String workspaceId, OpikVersion authSuggestedVersion) {
        var isWorkspaceInV2Allowlist = serviceTogglesConfig.getV2WorkspaceAllowlistIds().contains(workspaceId);
        if (isWorkspaceInV2Allowlist) {
            log.info("Workspace included in version_2 allowlist, workspaceId '{}'", workspaceId);
            return Mono.just(buildResponse(OpikVersion.VERSION_2));
        }
        var forcedVersion = getForcedVersion();
        if (forcedVersion.isPresent()) {
            log.info("Workspace version forced by feature flag, opikVersion '{}', workspaceId '{}'",
                    forcedVersion.get().getValue(), workspaceId);
            return Mono.just(buildResponse(forcedVersion.get()));
        }
        if (cacheConfiguration.isEnabled()) {
            return cacheManager.get(cacheKey(workspaceId), WorkspaceVersion.class)
                    .onErrorResume(throwable -> {
                        log.warn("Cache read failed, computing version, workspaceId '{}'", workspaceId, throwable);
                        return Mono.empty();
                    })
                    .switchIfEmpty(Mono.defer(() -> computeVersion(workspaceId, authSuggestedVersion)
                            .flatMap(response -> cacheResult(workspaceId, response))
                            .onErrorResume(throwable -> fallback(workspaceId, authSuggestedVersion, throwable))));
        }
        return computeVersion(workspaceId, authSuggestedVersion)
                .onErrorResume(throwable -> fallback(workspaceId, authSuggestedVersion, throwable));
    }

    protected abstract OpikVersion getFallbackVersion(String workspaceId, OpikVersion authSuggestedVersion);

    private Mono<WorkspaceVersion> fallback(String workspaceId, OpikVersion authSuggestedVersion,
            Throwable throwable) {
        log.warn("Version 1 entity check failed, returning fallback, workspaceId '{}'", workspaceId, throwable);
        return Mono.just(buildResponse(getFallbackVersion(workspaceId, authSuggestedVersion)));
    }

    private Mono<WorkspaceVersion> cacheResult(String workspaceId, WorkspaceVersion response) {
        var ttlDuration = cacheConfiguration.getCaches()
                .getOrDefault(CACHE_GROUP, cacheConfiguration.getDefaultDuration());
        return cacheManager.put(cacheKey(workspaceId), response, ttlDuration)
                .thenReturn(response)
                .onErrorResume(throwable -> {
                    log.warn("Failed to cache workspace version, workspaceId '{}'", workspaceId, throwable);
                    return Mono.just(response);
                });
    }

    private Mono<WorkspaceVersion> computeVersion(String workspaceId, OpikVersion authSuggestedVersion) {
        if (authSuggestedVersion == OpikVersion.VERSION_2) {
            log.info("Locked via auth one-way gate, workspaceId '{}', version '{}'", workspaceId, authSuggestedVersion);
            return Mono.just(buildResponse(OpikVersion.VERSION_2));
        }
        return Flux.concat(
                Mono.fromCallable(() -> hasStateDbVersion1Entities(workspaceId))
                        .subscribeOn(Schedulers.boundedElastic()),
                optimizationDAO.hasVersion1Optimizations(workspaceId, DemoData.OPTIMIZATIONS),
                experimentDAO.hasVersion1Experiments(workspaceId, DemoData.EXPERIMENTS))
                .any(found -> found)
                .map(found -> {
                    var version = found ? OpikVersion.VERSION_1 : OpikVersion.VERSION_2;
                    log.info("Workspace version determined as '{}' for workspace '{}' (hasVersion1Entities={})",
                            version.getValue(), workspaceId, found);
                    return buildResponse(version);
                });
    }

    private Optional<OpikVersion> getForcedVersion() {
        var forced = serviceTogglesConfig.getForceWorkspaceVersion();
        if (StringUtils.isBlank(forced) || FORCE_WORKSPACE_VERSION_DISABLED.equalsIgnoreCase(forced)) {
            return Optional.empty();
        }
        var version = OpikVersion.findByValue(forced);
        if (version.isEmpty()) {
            log.warn("Invalid forceWorkspaceVersion config value: '{}', ignoring", forced);
        }
        return version;
    }

    /**
     * Checks state database entity types for version 1 entities (missing project_id).
     * Short-circuits on the first positive result.
     *
     * <p>Uses a single READ_ONLY transaction — one connection acquisition
     * is more efficient than multiple transactions for independent EXISTS queries.</p>
     */
    private boolean hasStateDbVersion1Entities(String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            if (handle.attach(DashboardDAO.class).hasVersion1Dashboards(workspaceId)) {
                log.info("Found version_1 dashboards in workspace '{}'", workspaceId);
                return true;
            }
            if (handle.attach(AutomationRuleDAO.class).hasVersion1AutomationRules(workspaceId)) {
                log.info("Found multi-project automation rules in workspace '{}'", workspaceId);
                return true;
            }
            if (handle.attach(PromptDAO.class).hasVersion1Prompts(workspaceId, DemoData.PROMPTS)) {
                log.info("Found version_1 prompts in workspace '{}'", workspaceId);
                return true;
            }
            if (handle.attach(DatasetDAO.class).hasVersion1Datasets(workspaceId, DemoData.DATASETS)) {
                log.info("Found version_1 datasets in workspace '{}'", workspaceId);
                return true;
            }
            if (handle.attach(AlertDAO.class).hasVersion1Alerts(workspaceId)) {
                log.info("Found version_1 alerts in workspace '{}'", workspaceId);
                return true;
            }
            return false;
        });
    }

    private String cacheKey(String workspaceId) {
        return "opik:%s:%s".formatted(CACHE_GROUP, workspaceId);
    }

    private WorkspaceVersion buildResponse(OpikVersion version) {
        return WorkspaceVersion.builder().opikVersion(version).build();
    }

    @Override
    public Mono<Boolean> evictCache(@NonNull String workspaceId) {
        return cacheManager.evict(cacheKey(workspaceId), false);
    }
}
