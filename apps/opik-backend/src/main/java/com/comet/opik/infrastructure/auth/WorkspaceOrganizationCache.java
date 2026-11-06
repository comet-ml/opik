package com.comet.opik.infrastructure.auth;

import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The organization Comet last reported for each workspace, shared by all Opik instances through Redis.
 * <p>
 * One Redis hash per workspace, under {@code opik:workspace-organization:<workspaceId>}:
 * <ul>
 * <li>{@code organizationId}: the organization, or an empty string when the workspace has none.</li>
 * <li>{@code refreshAfter}: epoch millis after which Comet is asked again. Set to now + {@code freshFor}
 * ({@code authentication.organizationCacheTTL}, 10m by default) on every answer from Comet, and to now +
 * {@code retryAfterFailure} ({@code authentication.organizationCacheRetryAfterFailure}, 30s by default) when
 * Comet fails.</li>
 * </ul>
 * The whole key expires {@code keptFor} ({@code authentication.organizationCacheStaleTTL}, 60m by default) after the
 * last answer from Comet.
 */
@Slf4j
@RequiredArgsConstructor
class WorkspaceOrganizationCache {

    private static final String KEY_FORMAT = "opik:workspace-organization:%s";
    private static final String ORGANIZATION_ID = "organizationId";
    private static final String REFRESH_AFTER = "refreshAfter";

    @Builder(toBuilder = true)
    record Entry(String organizationId, @NonNull Instant refreshAfter) {
        boolean isFresh(@NonNull Instant now) {
            return now.isBefore(refreshAfter);
        }
    }

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull Duration freshFor;
    private final @NonNull Duration keptFor;
    private final @NonNull Duration retryAfterFailure;
    private final @NonNull Clock clock;

    Mono<Entry> find(@NonNull String workspaceId) {
        return map(workspaceId).readAllMap()
                .filter(fields -> fields.containsKey(REFRESH_AFTER))
                .map(fields -> Entry.builder()
                        .organizationId(StringUtils.trimToNull(fields.get(ORGANIZATION_ID)))
                        .refreshAfter(Instant.ofEpochMilli(Long.parseLong(fields.get(REFRESH_AFTER))))
                        .build());
    }

    Mono<String> organizationId(@NonNull String workspaceId) {
        return find(workspaceId)
                .mapNotNull(Entry::organizationId)
                .onErrorResume(error -> {
                    log.warn("Could not read the cached organization, workspace '{}'", workspaceId, error);
                    return Mono.empty();
                });
    }

    Mono<Void> save(@NonNull String workspaceId, String organizationId) {
        var map = map(workspaceId);
        return map.putAll(Map.of(
                ORGANIZATION_ID, StringUtils.defaultString(organizationId),
                REFRESH_AFTER, String.valueOf(now().plus(freshFor).toEpochMilli())))
                .then(map.expire(keptFor))
                .then();
    }

    /**
     * Moves only the refresh time: the expiry stays where the last answer from Comet put it, so a stale copy is never
     * kept for longer than {@code keptFor} while Comet keeps failing.
     */
    Mono<Void> retryLater(@NonNull String workspaceId) {
        return map(workspaceId)
                .fastPutIfExists(REFRESH_AFTER, String.valueOf(now().plus(retryAfterFailure).toEpochMilli()))
                .then();
    }

    Instant now() {
        return clock.instant();
    }

    private RMapReactive<String, String> map(String workspaceId) {
        return redisClient.getMap(KEY_FORMAT.formatted(workspaceId), StringCodec.INSTANCE);
    }
}
