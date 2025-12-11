package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.aws.AwsIamCredentialsResolver;
import com.comet.opik.infrastructure.redis.RedisUrl;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Data
@Slf4j
public class RedisConfig {

    @Valid @JsonProperty
    private String singleNodeUrl;

    @Valid @JsonProperty
    private AwsIamAuthConfig awsIamAuth = new AwsIamAuthConfig();

    public Config build() {
        Objects.requireNonNull(singleNodeUrl, "singleNodeUrl must not be null");
        var redisUrl = RedisUrl.parse(singleNodeUrl);
        var config = new Config();
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
        config.setCodec(new JsonJacksonCodec(JsonUtils.getMapper()));
        log.info("Built redis config with address '{}', database '{}'", redisUrl.address(), redisUrl.database());
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
