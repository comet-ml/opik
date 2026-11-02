package com.comet.opik.domain.topics;

import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.OpikConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The workspace attributes of the topics metrics: {@code workspace_id} and {@code workspace_name}, always paired. The
 * name falls back to the id when it is blank or its lookup fails, and a failed lookup never fails a tick or a run.
 * Names are kept per pod, a failed lookup for a shorter time, so a down React service costs one call per workspace
 * per {@link #FAILURE_TTL}, not one per tick.
 */
@Slf4j
@Singleton
public class TopicsWorkspaceNames {

    static final AttributeKey<String> WORKSPACE_ID = stringKey("workspace_id");
    static final AttributeKey<String> WORKSPACE_NAME = stringKey("workspace_name");

    static final Duration NAME_TTL = Duration.ofHours(1);
    static final Duration FAILURE_TTL = Duration.ofMinutes(10);

    private record Name(String value, Instant until) {
    }

    private final Function<String, String> lookup;
    private final Clock clock;
    private final Map<String, Name> names = new ConcurrentHashMap<>();

    @Inject
    public TopicsWorkspaceNames(@NonNull WorkspaceNameService workspaceNameService,
            @NonNull OpikConfiguration opikConfiguration) {
        this(workspaceId -> Optional.ofNullable(opikConfiguration.getAuthentication())
                .map(authentication -> authentication.getReactService())
                .map(reactService -> reactService.url())
                .filter(StringUtils::isNotBlank)
                .map(url -> workspaceNameService.getWorkspaceName(workspaceId, url))
                .orElse(null), Clock.systemUTC());
    }

    TopicsWorkspaceNames(@NonNull Function<String, String> lookup, @NonNull Clock clock) {
        this.lookup = lookup;
        this.clock = clock;
    }

    /** The workspace's name, or its id when the name is blank or its lookup fails. */
    public String name(@NonNull String workspaceId) {
        var now = clock.instant();
        var cached = names.get(workspaceId);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.value();
        }
        String name = null;
        try {
            name = lookup.apply(workspaceId);
        } catch (RuntimeException exception) {
            log.warn("Topics workspace name lookup failed, using the id, workspace '{}'", workspaceId, exception);
        }
        var resolved = StringUtils.isBlank(name)
                ? new Name(workspaceId, now.plus(FAILURE_TTL))
                : new Name(name, now.plus(NAME_TTL));
        names.put(workspaceId, resolved);
        return resolved.value();
    }

    /** Both workspace attributes, to add the metric's own attributes to. */
    public AttributesBuilder builder(@NonNull String workspaceId) {
        return Attributes.builder().put(WORKSPACE_ID, workspaceId).put(WORKSPACE_NAME, name(workspaceId));
    }

    /** Both workspace attributes, with a name already resolved: for gauges, whose callback must not look names up. */
    public static AttributesBuilder builder(@NonNull String workspaceId, @NonNull String workspaceName) {
        return Attributes.builder().put(WORKSPACE_ID, workspaceId)
                .put(WORKSPACE_NAME, StringUtils.defaultIfBlank(workspaceName, workspaceId));
    }

    public Attributes of(@NonNull String workspaceId) {
        return builder(workspaceId).build();
    }
}
