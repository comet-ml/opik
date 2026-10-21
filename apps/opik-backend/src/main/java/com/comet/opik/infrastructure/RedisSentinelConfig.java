package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redis.RedisUrl;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Preconditions;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.apache.commons.lang3.StringUtils;
import org.redisson.config.Config;
import org.redisson.config.FullJitterDelay;
import org.redisson.config.SentinelServersConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis Sentinel topology. When enabled, {@code redis.singleNodeUrl} is the seed sentinel: its scheme selects TLS for
 * every sentinel connection, its host and port identify the seed sentinel, and its credentials and database apply to the
 * master data nodes. Redisson discovers the master through the sentinels and follows it on failover. Defaults live only
 * in {@code config.yml}.
 *
 * @param enabled whether the backend connects through Sentinel instead of a single node.
 * @param masterName the master group the sentinels monitor. It must match their configuration, so it has no default.
 * @param nodes comma-separated extra seed sentinels, e.g. {@code redis://sentinel-2:26379}, so that startup does not
 *        depend on one sentinel. Comma-separated so it binds from a single environment variable.
 * @param username username for the sentinels themselves, whose ACLs are independent from the master data nodes'.
 * @param password password for the sentinels themselves.
 * @param retryAttempts times a failed command is retried, e.g. while a failover is in progress. Boxed so that an
 *        omitted or empty value fails validation instead of silently disabling retries.
 * @param retryBackoffBase base of the full-jitter backoff: each retry waits a random time up to
 *        {@code min(base * 2^attempt, cap)}, the same formula as the Python backend.
 * @param retryBackoffCap longest wait between retries.
 * @param checkSentinelsList Redisson's guard requiring the sentinels to report at least two of them; single-sentinel
 *        setups turn it off. Boxed for the same reason as {@code retryAttempts}.
 * @param connectTimeout timeout for establishing connections.
 * @param timeout timeout for command responses.
 * @param scanInterval how often Redisson polls the sentinels for topology changes.
 */
@Builder(toBuilder = true)
public record RedisSentinelConfig(
        boolean enabled,
        String masterName,
        String nodes,
        String username,
        String password,
        @NotNull @Min(0) @Max(20) Integer retryAttempts,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS) @MaxDuration(value = 5, unit = TimeUnit.MINUTES) Duration retryBackoffBase,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS) @MaxDuration(value = 5, unit = TimeUnit.MINUTES) Duration retryBackoffCap,
        @NotNull Boolean checkSentinelsList,
        @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS) @MaxDuration(value = 5, unit = TimeUnit.MINUTES) Duration connectTimeout,
        @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS) @MaxDuration(value = 5, unit = TimeUnit.MINUTES) Duration timeout,
        @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS) @MaxDuration(value = 5, unit = TimeUnit.MINUTES) Duration scanInterval) {

    private static final Set<String> SCHEMES = Set.of("redis", "rediss");
    private static final int MAX_PORT = 65_535;

    @JsonIgnore
    @AssertTrue(message = "masterName must not be blank when sentinel is enabled") public boolean isMasterNameValid() {
        return !enabled || StringUtils.isNotBlank(masterName);
    }

    @JsonIgnore
    @AssertTrue(message = "retryBackoffBase must not exceed retryBackoffCap") public boolean isRetryBackoffValid() {
        return retryBackoffBase == null || retryBackoffCap == null
                || retryBackoffBase.toMilliseconds() <= retryBackoffCap.toMilliseconds();
    }

    SentinelServersConfig useSentinelServers(Config config, RedisUrl seed) {
        Preconditions.checkArgument(isMasterNameValid(), "redis.sentinel.masterName must not be blank when enabled");
        var sentinelServersConfig = config.useSentinelServers()
                .setMasterName(masterName.strip())
                .addSentinelAddress(sentinelAddresses(seed).toArray(String[]::new))
                .setDatabase(seed.database())
                .setRetryAttempts(retryAttempts)
                .setRetryDelay(new FullJitterDelay(retryBackoffBase.toJavaDuration(), retryBackoffCap.toJavaDuration()))
                .setCheckSentinelsList(checkSentinelsList)
                .setConnectTimeout(Math.toIntExact(connectTimeout.toMilliseconds()))
                .setTimeout(Math.toIntExact(timeout.toMilliseconds()))
                .setScanInterval(Math.toIntExact(scanInterval.toMilliseconds()));
        if (StringUtils.isNotBlank(username)) {
            sentinelServersConfig.setSentinelUsername(username);
        }
        if (StringUtils.isNotBlank(password)) {
            sentinelServersConfig.setSentinelPassword(password);
        }
        return sentinelServersConfig;
    }

    /**
     * The seed from {@code singleNodeUrl} first, then the extra nodes in declaration order, without duplicates. Every
     * address must share the seed's scheme, since Redisson derives the master's transport from the sentinel addresses,
     * and carry an explicit port, since sentinels can listen on any port.
     */
    private Set<String> sentinelAddresses(RedisUrl seed) {
        Preconditions.checkArgument(SCHEMES.contains(seed.scheme()),
                "redis.singleNodeUrl must use the redis or rediss scheme when sentinel is enabled");
        Preconditions.checkArgument(seed.port() != RedisUrl.NO_DEFAULT_PORT,
                "redis.singleNodeUrl must include the sentinel port when sentinel is enabled");
        var addresses = new LinkedHashSet<String>();
        addresses.add(seed.address());
        Arrays.stream(StringUtils.defaultString(nodes).split(","))
                .map(String::strip)
                .filter(StringUtils::isNotBlank)
                .map(RedisSentinelConfig::toAddress)
                .forEach(address -> {
                    Preconditions.checkArgument(address.startsWith(seed.scheme() + "://"),
                            "redis.sentinel.nodes entries must use the same scheme as redis.singleNodeUrl");
                    addresses.add(address);
                });
        return addresses;
    }

    // Credentials belong in username/password, so a node carrying them is rejected rather than echoed in errors
    private static String toAddress(String node) {
        URI uri;
        try {
            uri = new URI(node);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("redis.sentinel.nodes has an entry that is not a valid URL", exception);
        }
        Preconditions.checkArgument(SCHEMES.contains(uri.getScheme()),
                "redis.sentinel.nodes entries must use the redis or rediss scheme");
        Preconditions.checkArgument(uri.getUserInfo() == null,
                "redis.sentinel.nodes entries must not carry credentials, use redis.sentinel.username/password");
        Preconditions.checkArgument(StringUtils.isNotBlank(uri.getHost()) && uri.getPort() > 0
                && uri.getPort() <= MAX_PORT,
                "redis.sentinel.nodes entries must include a host and a port between 1 and 65535");
        return "%s://%s:%d".formatted(uri.getScheme(), uri.getHost(), uri.getPort());
    }
}
