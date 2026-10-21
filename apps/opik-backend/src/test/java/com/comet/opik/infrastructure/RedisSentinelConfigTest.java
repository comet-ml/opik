package com.comet.opik.infrastructure;

import io.dropwizard.configuration.ConfigurationValidationException;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.config.Config;
import org.redisson.config.FullJitterDelay;
import org.redisson.config.SentinelServersConfig;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Loads the shipped {@code config.yml} with environment overrides, the way the application does, so the env var
 * contract, bean validation and the built Redisson topology are covered together.
 */
class RedisSentinelConfigTest {

    private static final String CONFIG_PATH = "config.yml";

    private final String masterName = RandomStringUtils.secure().nextAlphanumeric(10);
    private final String seedHost = "sentinel-" + RandomStringUtils.secure().nextAlphanumeric(8).toLowerCase();
    private final int seedPort = RandomUtils.secure().randomInt(20_000, 30_000);
    private final int database = RandomUtils.secure().randomInt(1, 15);

    private static RedisConfig loadRedisConfig(Map<String, String> environment) throws Exception {
        var substitutor = new EnvironmentVariableSubstitutor(false);
        substitutor.setVariableResolver(environment::get);
        return new YamlConfigurationFactory<>(OpikConfiguration.class, Validators.newValidator(),
                Jackson.newObjectMapper(), "dw")
                .build(new SubstitutingSourceProvider(new FileConfigurationSourceProvider(), substitutor),
                        CONFIG_PATH)
                .getRedis();
    }

    private Map<String, String> sentinelEnvironment(String singleNodeUrl) {
        var environment = new HashMap<String, String>();
        environment.put("REDIS_URL", singleNodeUrl);
        environment.put("OPIK_REDIS_SENTINEL_ENABLED", "true");
        environment.put("OPIK_REDIS_SENTINEL_MASTER_NAME", masterName);
        return environment;
    }

    private String seedUrl(String scheme) {
        return "%s://data-user:data-password@%s:%d/%d".formatted(scheme, seedHost, seedPort, database);
    }

    @Test
    void buildsSentinelTopologyFromEnvironment() throws Exception {
        var otherHost = "sentinel-" + RandomStringUtils.secure().nextAlphanumeric(8).toLowerCase();
        var environment = sentinelEnvironment(seedUrl("rediss"));
        environment.put("OPIK_REDIS_SENTINEL_MASTER_NAME", " %s ".formatted(masterName));
        // Padded, with an empty entry and a duplicate of the seed, which must collapse
        environment.put("OPIK_REDIS_SENTINEL_NODES",
                " rediss://%s , ,rediss://%s:%d".formatted(otherHost, seedHost, seedPort));
        environment.put("OPIK_REDIS_SENTINEL_USERNAME", "sentinel-user");
        environment.put("OPIK_REDIS_SENTINEL_PASSWORD", "sentinel-password");
        environment.put("OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS", "7");
        environment.put("OPIK_REDIS_SENTINEL_CHECK_SENTINELS_LIST", "false");
        environment.put("OPIK_REDIS_SENTINEL_CONNECT_TIMEOUT", "11s");
        environment.put("OPIK_REDIS_SENTINEL_TIMEOUT", "6s");
        environment.put("OPIK_REDIS_SENTINEL_SCAN_INTERVAL", "1500ms");
        environment.put("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_BASE", "300ms");
        environment.put("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP", "4s");

        var actual = loadRedisConfig(environment).build();

        var expected = new Config().useSentinelServers()
                .setMasterName(masterName)
                .addSentinelAddress("rediss://%s:%d".formatted(seedHost, seedPort),
                        "rediss://%s:26379".formatted(otherHost))
                .setDatabase(database)
                .setRetryAttempts(7)
                .setRetryDelay(new FullJitterDelay(java.time.Duration.ofMillis(300), java.time.Duration.ofSeconds(4)))
                .setCheckSentinelsList(false)
                .setConnectTimeout(11_000)
                .setTimeout(6_000)
                .setScanInterval(1_500)
                .setSentinelUsername("sentinel-user")
                .setSentinelPassword("sentinel-password")
                .setUsername("data-user")
                .setPassword("data-password");
        assertThat(actual.isSentinelConfig()).isTrue();
        assertSentinelServers(actual, expected);
    }

    @Test
    void appliesShippedDefaults() throws Exception {
        // Without a port the seed is a sentinel on 26379, not a Redis data node on 6379
        var environment = sentinelEnvironment(
                "redis://data-user:data-password@%s/%d".formatted(seedHost, database));
        // An exported but empty variable substitutes to null rather than to the '' default
        environment.put("OPIK_REDIS_SENTINEL_NODES", "");

        var actual = loadRedisConfig(environment).build();

        var expected = new Config().useSentinelServers()
                .setMasterName(masterName)
                .addSentinelAddress("redis://%s:26379".formatted(seedHost))
                .setDatabase(database)
                .setRetryAttempts(3)
                .setRetryDelay(new FullJitterDelay(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(2)))
                .setCheckSentinelsList(true)
                .setConnectTimeout(10_000)
                .setTimeout(5_000)
                .setScanInterval(2_000)
                .setUsername("data-user")
                .setPassword("data-password");
        assertSentinelServers(actual, expected);
    }

    @Test
    void keepsSingleServerWhenSentinelIsDisabled() throws Exception {
        var actual = loadRedisConfig(Map.of("REDIS_URL", seedUrl("redis"))).build();

        assertThat(actual.isSingleConfig()).isTrue();
        assertThat(actual.useSingleServer().getAddress()).isEqualTo("redis://%s:%d".formatted(seedHost, seedPort));
    }

    private static void assertSentinelServers(Config actual, SentinelServersConfig expected) {
        assertThat(actual.useSentinelServers())
                .usingRecursiveComparison()
                // Redisson's default resolver wraps username/password, which are compared on their own
                .ignoringFieldsOfTypes(CompletableFuture.class)
                .isEqualTo(expected);
    }

    @Test
    void rejectsPartialSentinelBlockInsteadOfDisablingProtections() {
        // Defaults live only in config.yml, so a hand-written block that omits a setting must fail, not run with 0/false
        var yaml = "enabled: true\nmasterName: %s\n".formatted(masterName);
        var factory = new YamlConfigurationFactory<>(RedisSentinelConfig.class, Validators.newValidator(),
                Jackson.newObjectMapper(), "dw");

        assertThatThrownBy(() -> factory.build(path -> new ByteArrayInputStream(yaml.getBytes(UTF_8)), "sentinel"))
                .isInstanceOf(ConfigurationValidationException.class)
                .hasMessageContainingAll("retryAttempts", "checkSentinelsList", "retryBackoffBase",
                        "retryBackoffCap", "connectTimeout", "timeout", "scanInterval");
    }

    private static Stream<Arguments> invalidSettings() {
        return Stream.of(
                arguments("OPIK_REDIS_SENTINEL_MASTER_NAME", " ", "masterName must not be blank"),
                arguments("OPIK_REDIS_SENTINEL_RETRY_ATTEMPTS", "21", "retryAttempts"),
                arguments("OPIK_REDIS_SENTINEL_CONNECT_TIMEOUT", "6m", "connectTimeout"),
                arguments("OPIK_REDIS_SENTINEL_TIMEOUT", "6m", "timeout"),
                arguments("OPIK_REDIS_SENTINEL_SCAN_INTERVAL", "50ms", "scanInterval"),
                arguments("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_BASE", "3s", "retryBackoffBase must not exceed"),
                arguments("OPIK_REDIS_SENTINEL_RETRY_BACKOFF_CAP", "6m", "retryBackoffCap"));
    }

    @ParameterizedTest
    @MethodSource("invalidSettings")
    void rejectsInvalidSettingsAtStartup(String variable, String value, String expectedMessage) {
        var environment = sentinelEnvironment(seedUrl("redis"));
        environment.put(variable, value);

        assertThatThrownBy(() -> loadRedisConfig(environment))
                .isInstanceOf(ConfigurationValidationException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static Stream<Arguments> invalidNodes() {
        return Stream.of(
                arguments("redis", "rediss://sentinel-2:26379", "must use the same scheme as redis.singleNodeUrl"),
                arguments("redis", "http://sentinel-2:26379", "must use the redis or rediss scheme"),
                arguments("redis", "redis://user:secret@sentinel-2:26379", "must not carry credentials"),
                arguments("redis", "redis://sentinel-2:65536", "a port between 1 and 65535"),
                arguments("redis", "redis://:26379", "must include a host"),
                arguments("redis", "redis://bad host:26379", "not a valid URL"),
                arguments("http", "", "redis.singleNodeUrl must use the redis or rediss scheme"));
    }

    @ParameterizedTest
    @MethodSource("invalidNodes")
    void rejectsInvalidSentinelAddresses(String seedScheme, String nodes, String expectedMessage) throws Exception {
        var environment = sentinelEnvironment(seedUrl(seedScheme));
        environment.put("OPIK_REDIS_SENTINEL_NODES", nodes);
        var redisConfig = loadRedisConfig(environment);

        assertThatThrownBy(redisConfig::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage)
                .hasMessageNotContaining("secret");
    }
}
