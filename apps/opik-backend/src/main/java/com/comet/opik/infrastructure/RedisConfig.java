package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.aws.AwsIamCredentialsResolver;
import com.comet.opik.infrastructure.redis.RedisUrl;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.BaseConfig;
import org.redisson.config.Config;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Data
@Slf4j
public class RedisConfig {

    @Valid @JsonProperty
    private String singleNodeUrl;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 500, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
    private Duration healthCheckTimeout = Duration.seconds(1);

    @Valid @JsonProperty
    private AwsIamAuthConfig awsIamAuth = new AwsIamAuthConfig();

    @Valid @JsonProperty
    @NotNull private RedisSentinelConfig sentinel = RedisSentinelConfig.builder().build();

    public Config build() {
        Objects.requireNonNull(singleNodeUrl, "singleNodeUrl must not be null");
        // ElastiCache and MemoryDB, where IAM auth applies, offer no Sentinel, and Redisson would send IAM tokens to sentinels
        Preconditions.checkArgument(!(sentinel.enabled() && awsIamAuth.isEnabled()),
                "redis.sentinel and redis.awsIamAuth cannot both be enabled");
        var redisUrl = sentinel.enabled()
                ? RedisUrl.parse(singleNodeUrl, RedisUrl.NO_DEFAULT_PORT)
                : RedisUrl.parse(singleNodeUrl);
        var config = new Config();
        BaseConfig<?> serverConfig = sentinel.enabled()
                ? sentinel.useSentinelServers(config, redisUrl)
                : config.useSingleServer()
                        .setAddress(redisUrl.address())
                        .setDatabase(redisUrl.database());
        if (awsIamAuth.isEnabled()) {
            // Configure Redis with AWS IAM authentication using DefaultCredentialsProvider
            // This will read from environment variables, system properties, IAM roles, etc.
            serverConfig.setCredentialsResolver(new AwsIamCredentialsResolver(awsIamAuth));
        } else {
            // Set username and password from URL if present, in sentinel mode they apply to the master data nodes
            redisUrl.username().ifPresent(serverConfig::setUsername);
            redisUrl.password().ifPresent(serverConfig::setPassword);
        }
        config.setCodec(new JsonJacksonCodec(JsonUtils.getMapper()));
        log.info("Built redis config with address '{}', database '{}', sentinel '{}'", redisUrl.address(),
                redisUrl.database(), sentinel.enabled());
        return config;
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
