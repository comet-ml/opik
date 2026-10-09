package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.aws.AwsIamCredentialsResolver;
import com.comet.opik.infrastructure.redis.RedisUrl;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Data
@Slf4j
public class RedisConfig {

    private static final String SSL_SCHEME = "rediss";

    @Valid @JsonProperty
    private String singleNodeUrl;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 500, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
    private Duration healthCheckTimeout = Duration.seconds(1);

    @Valid @JsonProperty
    private AwsIamAuthConfig awsIamAuth = new AwsIamAuthConfig();

    @Valid @JsonProperty
    @NotNull private SentinelConfig sentinel = new SentinelConfig();

    public Config build() {
        Objects.requireNonNull(singleNodeUrl, "singleNodeUrl must not be null");
        var redisUrl = RedisUrl.parse(singleNodeUrl);
        var config = new Config();
        if (sentinel.isEnabled()) {
            buildSentinelServerConfig(config, redisUrl);
        } else {
            buildSingleServerConfig(config, redisUrl);
        }
        config.setCodec(new JsonJacksonCodec(JsonUtils.getMapper()));
        return config;
    }

    private void buildSingleServerConfig(Config config, RedisUrl redisUrl) {
        var singleServerConfig = config.useSingleServer()
                .setAddress(redisUrl.address())
                .setDatabase(redisUrl.database());
        if (awsIamAuth.isEnabled()) {
            // Configure Redis with AWS IAM authentication using DefaultCredentialsProvider
            // This will read from environment variables, system properties, IAM roles, etc.
            singleServerConfig.setCredentialsResolver(new AwsIamCredentialsResolver(awsIamAuth));
        } else {
            // Set username and password from URL if present
            redisUrl.username().ifPresent(singleServerConfig::setUsername);
            redisUrl.password().ifPresent(singleServerConfig::setPassword);
        }
        log.info("Built redis single node config with address '{}', database '{}'", redisUrl.address(),
                redisUrl.database());
    }

    /**
     * Builds a Redisson config backed by Redis Sentinel. This method configures Sentinel discovery; Redisson handles
     * topology monitoring and reconnection behavior.
     * <p>
     * Successful failover requires a healthy Sentinel quorum, an eligible synchronized replica, and Sentinel-announced
     * addresses that are reachable from the backend.
     * <p>
     * {@code singleNodeUrl} is reused as the seed sentinel address: its scheme decides whether sentinel connections use
     * TLS, its host and port identify the seed sentinel (usually {@code 26379}), and its credentials and database number
     * apply to the master data nodes. Extra seed sentinels can be declared through {@code sentinel.nodes} so that
     * startup does not depend on a single sentinel being reachable.
     */
    private void buildSentinelServerConfig(Config config, RedisUrl redisUrl) {
        Preconditions.checkArgument(StringUtils.isNotBlank(sentinel.getMasterName()),
                "sentinel.masterName must not be blank when sentinel.enabled is true");
        var sentinelAddresses = resolveSentinelAddresses(redisUrl);
        var sentinelServersConfig = config.useSentinelServers()
                .setMasterName(sentinel.getMasterName())
                .addSentinelAddress(sentinelAddresses.toArray(String[]::new))
                .setDatabase(redisUrl.database())
                .setRetryAttempts(sentinel.getRetryAttempts())
                .setCheckSentinelsList(sentinel.isCheckSentinelsList())
                .setConnectTimeout(Math.toIntExact(sentinel.getConnectTimeout().toMilliseconds()))
                .setTimeout(Math.toIntExact(sentinel.getTimeout().toMilliseconds()))
                .setScanInterval(Math.toIntExact(sentinel.getScanInterval().toMilliseconds()));
        setSentinelCredentials(sentinelServersConfig);
        if (awsIamAuth.isEnabled()) {
            sentinelServersConfig.setCredentialsResolver(new AwsIamCredentialsResolver(awsIamAuth));
        } else {
            redisUrl.username().ifPresent(sentinelServersConfig::setUsername);
            redisUrl.password().ifPresent(sentinelServersConfig::setPassword);
        }
        log.info("Built redis sentinel config with master name '{}', sentinel address count '{}', database '{}'",
                sentinel.getMasterName(), sentinelAddresses.size(), redisUrl.database());
    }

    /**
     * The seed sentinel derived from {@code singleNodeUrl} always comes first, followed by any explicitly configured
     * extra sentinels. Duplicates are dropped while preserving the declaration order.
     */
    private Set<String> resolveSentinelAddresses(RedisUrl redisUrl) {
        var sentinelScheme = redisUrl.scheme();
        Preconditions.checkArgument(
                "redis".equals(sentinelScheme) || SSL_SCHEME.equals(sentinelScheme),
                "singleNodeUrl must use the redis or rediss scheme when sentinel.enabled is true");
        var addresses = new LinkedHashSet<String>();
        addresses.add(redisUrl.address());
        for (var address : sentinel.getNodes()) {
            var addressUri = parseSentinelAddress(address);
            Preconditions.checkArgument(
                    sentinelScheme.equals(addressUri.getScheme()),
                    "sentinel.nodes entries must use the same scheme as singleNodeUrl");
            addresses.add(address);
        }
        return addresses;
    }

    private static URI parseSentinelAddress(String address) {
        final URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("sentinel.nodes entries must be valid Redis URLs");
        }

        var scheme = uri.getScheme();
        Preconditions.checkArgument(
                "redis".equals(scheme) || SSL_SCHEME.equals(scheme),
                "sentinel.nodes entries must use the redis or rediss scheme");
        Preconditions.checkArgument(StringUtils.isNotBlank(uri.getHost())
                && uri.getPort() > 0
                && uri.getPort() <= 65_535,
                "sentinel.nodes entries must include a valid host and port");
        return uri;
    }

    /**
     * Sentinel nodes have their own ACLs, independent from the ones guarding the master data nodes.
     */
    private void setSentinelCredentials(SentinelServersConfig sentinelServersConfig) {
        if (StringUtils.isNotBlank(sentinel.getUsername())) {
            sentinelServersConfig.setSentinelUsername(sentinel.getUsername());
        }
        if (StringUtils.isNotBlank(sentinel.getPassword())) {
            sentinelServersConfig.setSentinelPassword(sentinel.getPassword());
        }
    }

    @Data
    public static class SentinelConfig {

        @Valid @JsonProperty
        private boolean enabled = false;

        @Valid @JsonProperty
        private String masterName;

        /**
         * Comma-separated list of additional seed sentinel addresses, on top of the one derived from
         * {@code singleNodeUrl}, so that startup does not depend on a single sentinel being reachable.
         *
         * <p>Stored as a scalar rather than a YAML list so it binds cleanly from a comma-separated environment
         * override (Dropwizard substitutes {@code ${...}} into the raw YAML before parsing, so a comma-separated env
         * value cannot bind to a {@code List}). {@link #getNodes()} splits, strips and drops blanks.
         */
        @Valid @JsonProperty
        @NotNull private String nodes = "";

        /** Derived: the parsed, stripped, blank-free list of extra seed sentinel addresses. */
        public List<String> getNodes() {
            Preconditions.checkArgument(nodes != null, "sentinel.nodes must not be null");
            return Arrays.stream(nodes.split(","))
                    .map(String::strip)
                    .filter(StringUtils::isNotBlank)
                    .toList();
        }

        @Valid @JsonProperty
        private String username;

        @Valid @JsonProperty
        private String password;

        @Valid @JsonProperty
        @Min(0) private int retryAttempts = 3;

        /**
         * Redisson refuses to start unless the sentinels report at least two nodes, which is the right guard for a
         * production quorum but blocks single sentinel setups used in development. Disable it to allow them.
         */
        @Valid @JsonProperty
        private boolean checkSentinelsList = true;

        @Valid @JsonProperty
        @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = Integer.MAX_VALUE, unit = TimeUnit.MILLISECONDS)
        private Duration connectTimeout = Duration.seconds(10);

        @Valid @JsonProperty
        @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = Integer.MAX_VALUE, unit = TimeUnit.MILLISECONDS)
        private Duration timeout = Duration.seconds(5);

        @Valid @JsonProperty
        @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = Integer.MAX_VALUE, unit = TimeUnit.MILLISECONDS)
        private Duration scanInterval = Duration.seconds(2);

        /**
         * The master name identifies the monitored master group and cannot be defaulted, as it must match the name the
         * sentinels were configured with. It is only required when sentinel mode is enabled.
         */
        @JsonIgnore
        @AssertTrue(message = "sentinel.masterName must not be blank when sentinel.enabled is true") public boolean isMasterNameProvidedWhenEnabled() {
            return !enabled || StringUtils.isNotBlank(masterName);
        }
    }

    @Data
    public static class AwsIamAuthConfig {

        @Valid @JsonProperty
        private boolean enabled = false;

        @Valid @JsonProperty
        @NotNull private String awsUserId = "";

        @Valid @JsonProperty
        @NotBlank private String awsRegion = "us-east-1";

        @Valid @JsonProperty
        @NotNull private String awsResourceName = ""; // replication group / cluster / serverless name

        // Token cache refresh/expire timings
        @Valid @JsonProperty
        @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration tokenCacheRefreshAfter = Duration.minutes(13);

        @Valid @JsonProperty
        @NotNull @MinDuration(value = 2, unit = TimeUnit.SECONDS)
        private Duration tokenCacheExpireAfter = Duration.minutes(14);

        // Presigned token expiry duration
        @Valid @JsonProperty
        @NotNull @MinDuration(value = 3, unit = TimeUnit.SECONDS)
        private Duration tokenExpiryDuration = Duration.minutes(15);
    }
}
