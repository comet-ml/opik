package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redis.RedisUrl;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.redisson.config.Config;
import org.redisson.config.FullJitterDelay;
import org.redisson.config.SentinelServersConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis Sentinel topology. When enabled, {@code redis.singleNodeUrl} is the seed sentinel: its scheme selects TLS for
 * every sentinel connection, its host and port identify the seed sentinel, and its credentials and database apply to the
 * master data nodes. Redisson discovers the master through the sentinels and follows it on failover.
 */
@Data
public class RedisSentinelConfig {

    static final int DEFAULT_PORT = 26379;

    private static final Set<String> SCHEMES = Set.of("redis", "rediss");
    private static final int MAX_PORT = 65_535;

    @Valid @JsonProperty
    private boolean enabled;

    // Must match the name the sentinels monitor, so it cannot be defaulted
    @Valid @JsonProperty
    private String masterName;

    // Comma-separated, so it binds from a single environment variable. An empty variable binds as null
    @Valid @JsonProperty
    private String nodes;

    // Sentinels have their own ACLs, independent from the master data nodes
    @Valid @JsonProperty
    private String username;

    @Valid @JsonProperty
    private String password;

    @Valid @JsonProperty
    @NotNull @Min(0) @Max(20) private Integer retryAttempts;

    // Full jitter: each retry waits a random time up to min(base * 2^attempt, cap), like the Python backend
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.MINUTES)
    private Duration retryBackoffBase;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.MINUTES)
    private Duration retryBackoffCap;

    // Redisson refuses to start unless the sentinels report at least two of them; single-sentinel dev setups turn it off
    @Valid @JsonProperty
    @NotNull private Boolean checkSentinelsList;

    // Redisson takes these as int milliseconds
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.MINUTES)
    private Duration connectTimeout;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.MINUTES)
    private Duration timeout;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.MINUTES)
    private Duration scanInterval;

    @JsonIgnore
    @AssertTrue(message = "masterName must not be blank when sentinel is enabled") public boolean isMasterNameValid() {
        return !enabled || StringUtils.isNotBlank(masterName);
    }

    @JsonIgnore
    @AssertTrue(message = "retryBackoffBase must not exceed retryBackoffCap") public boolean isRetryBackoffValid() {
        return retryBackoffBase == null || retryBackoffCap == null
                || retryBackoffBase.toMilliseconds() <= retryBackoffCap.toMilliseconds();
    }

    @JsonIgnore
    public List<String> getNodeList() {
        return Arrays.stream(StringUtils.defaultString(nodes).split(","))
                .map(String::strip)
                .filter(StringUtils::isNotBlank)
                .toList();
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
     * address must share the seed's scheme: Redisson derives the master's transport from the sentinel addresses.
     */
    Set<String> sentinelAddresses(RedisUrl seed) {
        Preconditions.checkArgument(SCHEMES.contains(seed.scheme()),
                "redis.singleNodeUrl must use the redis or rediss scheme when sentinel is enabled");
        var addresses = new LinkedHashSet<String>();
        addresses.add(seed.address());
        getNodeList().stream()
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
        var port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
        Preconditions.checkArgument(StringUtils.isNotBlank(uri.getHost()) && port > 0 && port <= MAX_PORT,
                "redis.sentinel.nodes entries must include a host, and a port between 1 and 65535 if any");
        return "%s://%s:%d".formatted(uri.getScheme(), uri.getHost(), port);
    }
}
