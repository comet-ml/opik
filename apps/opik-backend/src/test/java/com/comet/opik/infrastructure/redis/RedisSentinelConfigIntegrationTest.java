package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.config.Config;
import org.redisson.misc.RedisURI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link RedisConfig} in sentinel mode produces a Redisson client that discovers the master through the
 * sentinels, against a real master, replica and sentinel topology.
 * <p>
 * An actual failover is deliberately not exercised here. It depends on the sentinel timers firing within
 * {@code down-after-milliseconds}, and sentinels running inside a virtualised runner routinely enter tilt mode on clock
 * jitter, which suppresses failover and makes such a test flaky. Reacting to a failover is entirely Redisson's
 * responsibility, what this test covers is the configuration handed to it.
 * <p>
 * Sentinels announce the redis nodes by their docker network aliases, which are not routable from the host, so a NAT
 * mapper translates each alias to the port testcontainers published for it. This is only needed by the test, real
 * deployments reach the announced addresses directly.
 */
@Slf4j
@DisplayName("Redis Sentinel Config Integration Test")
class RedisSentinelConfigIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2.4-alpine3.19");
    private static final String MASTER_NAME = "mymaster";
    private static final String MASTER_ALIAS = "redis-master";
    private static final String REPLICA_ALIAS = "redis-replica";
    private static final String SENTINEL_ALIAS = "redis-sentinel";
    private static final int REDIS_PORT = 6379;
    private static final int SENTINEL_PORT = 26379;

    private static Network network;
    private static GenericContainer<?> master;
    private static GenericContainer<?> replica;
    private static GenericContainer<?> sentinel;

    @BeforeAll
    @SuppressWarnings("resource")
    static void beforeAll() {
        network = Network.newNetwork();
        master = new GenericContainer<>(REDIS_IMAGE)
                .withNetwork(network)
                .withNetworkAliases(MASTER_ALIAS)
                .withExposedPorts(REDIS_PORT)
                .waitingFor(Wait.forListeningPort());
        replica = new GenericContainer<>(REDIS_IMAGE)
                .withNetwork(network)
                .withNetworkAliases(REPLICA_ALIAS)
                .withExposedPorts(REDIS_PORT)
                // The replica announces its network alias, so that the sentinel reports an address the NAT mapper can
                // translate rather than the container IP
                .withCommand("redis-server",
                        "--replicaof", MASTER_ALIAS, String.valueOf(REDIS_PORT),
                        "--replica-announce-ip", REPLICA_ALIAS,
                        "--replica-announce-port", String.valueOf(REDIS_PORT))
                .waitingFor(Wait.forListeningPort());
        sentinel = new GenericContainer<>(REDIS_IMAGE)
                .withNetwork(network)
                .withNetworkAliases(SENTINEL_ALIAS)
                .withExposedPorts(SENTINEL_PORT)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("redis/sentinel.conf"),
                        "/etc/redis/sentinel.conf")
                .withCommand("redis-sentinel", "/etc/redis/sentinel.conf")
                .waitingFor(Wait.forListeningPort());
        master.start();
        replica.start();
        sentinel.start();
    }

    @AfterAll
    static void afterAll() {
        sentinel.stop();
        replica.stop();
        master.stop();
        network.close();
    }

    private static RedisConfig newRedisConfig(String singleNodeUrl) {
        var redisConfig = new RedisConfig();
        redisConfig.setSingleNodeUrl(singleNodeUrl);
        var sentinelConfig = new RedisConfig.SentinelConfig();
        sentinelConfig.setEnabled(true);
        sentinelConfig.setMasterName(MASTER_NAME);
        // A single sentinel keeps the topology deterministic, so the production quorum check has to be relaxed
        sentinelConfig.setCheckSentinelsList(false);
        redisConfig.setSentinel(sentinelConfig);
        return redisConfig;
    }

    private static RedisConfig newRedisConfig() {
        return newRedisConfig(sentinelUrl(sentinel.getMappedPort(SENTINEL_PORT)));
    }

    private static String sentinelUrl(int port) {
        return "redis://%s:%d/0".formatted(sentinel.getHost(), port);
    }

    /**
     * Translates the docker network aliases announced by the sentinel into the host reachable mapped ports.
     */
    private static Config withNatMapper(Config config) {
        var containersByAlias = Map.of(MASTER_ALIAS, master, REPLICA_ALIAS, replica, SENTINEL_ALIAS, sentinel);
        config.useSentinelServers().setNatMapper(address -> {
            var container = containersByAlias.get(address.getHost());
            return container == null
                    ? address
                    : new RedisURI(address.getScheme(), container.getHost(),
                            container.getMappedPort(address.getPort()));
        });
        return config;
    }

    private static void assertCanReadAndWrite(RedissonClient client, String value) {
        var bucket = client.getBucket("test:sentinel:%s".formatted(UUID.randomUUID()));
        bucket.set(value);
        assertThat((String) bucket.get()).isEqualTo(value);
        bucket.delete();
        assertThat(bucket.isExists()).isFalse();
    }

    private static int findFreePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to find a free port", exception);
        }
    }

    @Test
    @DisplayName("Should discover the master through the sentinel and run commands against it")
    void shouldDiscoverMasterThroughSentinelAndRunCommandsAgainstIt() {
        RedissonClient client = Redisson.create(withNatMapper(newRedisConfig().build()));
        try {
            assertCanReadAndWrite(client, "hello-sentinel");
        } finally {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("Should fail to connect when the master name is unknown to the sentinel")
    void shouldFailToConnectWhenMasterNameIsUnknownToTheSentinel() {
        var redisConfig = newRedisConfig();
        redisConfig.getSentinel().setMasterName("unknown-master");

        var config = redisConfig.build();

        assertThatThrownBy(() -> Redisson.create(config)).isInstanceOf(RedisConnectionException.class);
    }

    @Test
    @DisplayName("Should fall back to an extra seed sentinel when the one from the url is unreachable")
    void shouldFallBackToExtraSeedSentinelWhenTheOneFromTheUrlIsUnreachable() {
        // Seed with a sentinel that is not listening, to prove the extra nodes are used as fallbacks
        var redisConfig = newRedisConfig(sentinelUrl(findFreePort()));
        redisConfig.getSentinel()
                .setNodes("redis://%s:%d".formatted(sentinel.getHost(), sentinel.getMappedPort(SENTINEL_PORT)));

        RedissonClient client = Redisson.create(withNatMapper(redisConfig.build()));
        try {
            assertCanReadAndWrite(client, "hello-fallback");
        } finally {
            client.shutdown();
        }
    }
}
