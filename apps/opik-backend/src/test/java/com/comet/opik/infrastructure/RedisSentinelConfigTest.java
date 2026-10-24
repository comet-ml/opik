package com.comet.opik.infrastructure;

import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.config.Config;
import org.redisson.config.FullJitterDelay;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Covers only the logic this config adds on top of Dropwizard binding and validation: resolving and checking the
 * sentinel addresses and mapping the settings onto Redisson. The YAML wiring is exercised by
 * {@code RedisSentinelIntegrationTest}.
 */
class RedisSentinelConfigTest {

    private final String masterName = RandomStringUtils.secure().nextAlphanumeric(10);
    private final String seedHost = "sentinel-" + RandomStringUtils.secure().nextAlphanumeric(8).toLowerCase();
    private final int seedPort = RandomUtils.secure().randomInt(20_000, 30_000);
    private final int database = RandomUtils.secure().randomInt(1, 15);

    private RedisSentinelConfig.RedisSentinelConfigBuilder sentinel() {
        return RedisSentinelConfig.builder()
                .enabled(true)
                .masterName(masterName)
                .retryAttempts(3)
                .retryBackoffBase(Duration.milliseconds(500))
                .retryBackoffCap(Duration.seconds(2))
                .checkSentinelsList(true)
                .connectTimeout(Duration.seconds(10))
                .timeout(Duration.seconds(5))
                .scanInterval(Duration.seconds(2));
    }

    private RedisConfig redisConfig(String singleNodeUrl, RedisSentinelConfig sentinel) {
        var redisConfig = new RedisConfig();
        redisConfig.setSingleNodeUrl(singleNodeUrl);
        redisConfig.setSentinel(sentinel);
        return redisConfig;
    }

    private String seedUrl(String scheme) {
        return "%s://data-user:data%%40pass@%s:%d/%d".formatted(scheme, seedHost, seedPort, database);
    }

    @Test
    void mapsSettingsAndResolvesSentinelAddresses() {
        var otherHost = "sentinel-" + RandomStringUtils.secure().nextAlphanumeric(8).toLowerCase();
        var sentinel = sentinel()
                .masterName(" %s ".formatted(masterName))
                // Padded, with an empty entry and a duplicate of the seed, which must collapse
                .nodes(" rediss://%s:26380 , ,rediss://%s:%d".formatted(otherHost, seedHost, seedPort))
                .username("sentinel-user")
                .password("sentinel-pass")
                .retryAttempts(7)
                .retryBackoffBase(Duration.milliseconds(300))
                .retryBackoffCap(Duration.seconds(4))
                .checkSentinelsList(false)
                .connectTimeout(Duration.seconds(11))
                .timeout(Duration.seconds(6))
                .scanInterval(Duration.milliseconds(1500))
                .build();

        var actual = redisConfig(seedUrl("rediss"), sentinel).build();

        var expected = new Config().useSentinelServers()
                .setMasterName(masterName)
                .addSentinelAddress("rediss://%s:%d".formatted(seedHost, seedPort),
                        "rediss://%s:26380".formatted(otherHost))
                .setDatabase(database)
                .setRetryAttempts(7)
                .setRetryDelay(new FullJitterDelay(java.time.Duration.ofMillis(300), java.time.Duration.ofSeconds(4)))
                .setCheckSentinelsList(false)
                .setConnectTimeout(11_000)
                .setTimeout(6_000)
                .setScanInterval(1_500)
                .setSentinelUsername("sentinel-user")
                .setSentinelPassword("sentinel-pass")
                .setUsername("data-user")
                .setPassword("data@pass");
        assertThat(actual.useSentinelServers())
                .usingRecursiveComparison()
                // Redisson's default resolver wraps username/password, which are compared on their own
                .ignoringFieldsOfTypes(CompletableFuture.class)
                .isEqualTo(expected);
    }

    private static Stream<Arguments> invalidAddresses() {
        return Stream.of(
                arguments("redis", "rediss://sentinel-2:26379", "must use the same scheme as redis.singleNodeUrl"),
                arguments("redis", "http://sentinel-2:26379", "must use the redis or rediss scheme"),
                arguments("redis", "redis://user:secret@sentinel-2:26379", "must not carry credentials"),
                arguments("redis", "redis://sentinel-2", "must include a host and a port"),
                arguments("redis", "redis://sentinel-2:65536", "must include a host and a port"),
                arguments("redis", "redis://:26379", "must include a host and a port"),
                arguments("redis", "redis://bad host:26379", "not a valid URL"),
                arguments("http", "", "redis.singleNodeUrl must use the redis or rediss scheme"));
    }

    @ParameterizedTest
    @MethodSource("invalidAddresses")
    void rejectsInvalidSentinelAddresses(String seedScheme, String nodes, String expectedMessage) {
        var redisConfig = redisConfig(seedUrl(seedScheme), sentinel().nodes(nodes).build());

        assertThatThrownBy(redisConfig::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage)
                .hasMessageNotContaining("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ":0", ":65536"})
    void rejectsSeedWithoutValidPortSinceSentinelsListenOnAnyPort(String port) {
        var redisConfig = redisConfig("redis://%s%s/%d".formatted(seedHost, port, database), sentinel().build());

        assertThatThrownBy(redisConfig::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis.singleNodeUrl must include a sentinel port between 1 and 65535");
    }

    @Test
    void acceptsRedisConfigWithoutSentinelBlock() {
        // Older custom configs have no redis.sentinel block; single-node mode must still validate
        var redisConfig = new RedisConfig();
        redisConfig.setSingleNodeUrl(seedUrl("redis"));

        assertThat(Validators.newValidator().validate(redisConfig)).isEmpty();
    }

    @Test
    void rejectsEnabledSentinelWithMissingSettings() {
        var sentinel = RedisSentinelConfig.builder().enabled(true).masterName(masterName).build();

        assertThat(Validators.newValidator().validate(sentinel))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("completeWhenEnabled");
    }

    @Test
    void rejectsNegativeDatabase() {
        var redisConfig = redisConfig("redis://%s:%d/-1".formatted(seedHost, seedPort), sentinel().build());

        assertThatThrownBy(redisConfig::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis.singleNodeUrl must select a non-negative database");
    }

    @Test
    void rejectsSentinelWithAwsIamAuth() {
        var redisConfig = redisConfig(seedUrl("redis"), sentinel().build());
        redisConfig.getAwsIamAuth().setEnabled(true);

        assertThatThrownBy(redisConfig::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis.sentinel and redis.awsIamAuth cannot both be enabled");
    }
}
