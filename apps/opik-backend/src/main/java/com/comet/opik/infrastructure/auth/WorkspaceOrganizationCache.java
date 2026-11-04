package com.comet.opik.infrastructure.auth;

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

@Slf4j
@RequiredArgsConstructor
class WorkspaceOrganizationCache {

    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private static final String KEY_FORMAT = "opik:workspace-organization:%s";
    private static final String ORGANIZATION_ID = "organizationId";
    private static final String REFRESH_AFTER = "refreshAfter";

    record Entry(String organizationId, Instant refreshAfter) {
        boolean isFresh(Instant now) {
            return now.isBefore(refreshAfter);
        }
    }

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull Duration freshFor;
    private final @NonNull Duration keptFor;
    private final @NonNull Clock clock;

    Mono<Entry> find(@NonNull String workspaceId) {
        return map(workspaceId).readAllMap()
                .filter(fields -> fields.containsKey(REFRESH_AFTER))
                .map(fields -> new Entry(StringUtils.trimToNull(fields.get(ORGANIZATION_ID)),
                        Instant.ofEpochMilli(Long.parseLong(fields.get(REFRESH_AFTER)))));
    }

    Mono<String> organizationId(@NonNull String workspaceId) {
        return find(workspaceId)
                .mapNotNull(Entry::organizationId)
                .onErrorResume(error -> {
                    log.warn("Could not read the cached organization of workspace '{}'", workspaceId, error);
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

    // Only the refresh time moves: the expiry stays where the last answer from Comet put it, so a stale copy is
    // never kept for longer than keptFor while Comet keeps failing.
    Mono<Void> retryLater(@NonNull String workspaceId) {
        return map(workspaceId)
                .fastPutIfExists(REFRESH_AFTER, String.valueOf(now().plus(RETRY_AFTER_FAILURE).toEpochMilli()))
                .then();
    }

    Instant now() {
        return clock.instant();
    }

    private RMapReactive<String, String> map(String workspaceId) {
        return redisClient.getMap(KEY_FORMAT.formatted(workspaceId), StringCodec.INSTANCE);
    }
}
