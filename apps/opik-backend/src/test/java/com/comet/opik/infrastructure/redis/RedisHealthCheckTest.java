package com.comet.opik.infrastructure.redis;

import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.api.redisnode.RedisSentinelMasterSlave;
import org.redisson.api.redisnode.RedisSingle;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redisson rejects a node group that does not match the configured topology, so the health check has to ask for the
 * group matching the current mode. Getting this wrong makes the check throw on every run, which keeps a pod out of
 * service forever even though the application is serving fine.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Redis Health Check Test")
class RedisHealthCheckTest {

    private static final Duration TIMEOUT = Duration.seconds(1);

    @Mock
    private RedissonClient redisClient;

    @Mock
    private RedisSingle redisSingle;

    @Mock
    private RedisSentinelMasterSlave redisSentinelMasterSlave;

    @Test
    @DisplayName("Should ping the single node group when sentinel is disabled")
    void shouldPingSingleNodeGroupWhenSentinelDisabled() {
        when(redisClient.getRedisNodes(RedisNodes.SINGLE)).thenReturn(redisSingle);
        when(redisSingle.pingAll(anyLong(), any(TimeUnit.class))).thenReturn(true);

        var result = new RedisHealthCheck(redisClient, TIMEOUT, false).execute();

        assertThat(result.isHealthy()).isTrue();
        verify(redisClient).getRedisNodes(RedisNodes.SINGLE);
    }

    @Test
    @DisplayName("Should ping the sentinel master slave group when sentinel is enabled")
    void shouldPingSentinelMasterSlaveGroupWhenSentinelEnabled() {
        when(redisClient.getRedisNodes(RedisNodes.SENTINEL_MASTER_SLAVE)).thenReturn(redisSentinelMasterSlave);
        when(redisSentinelMasterSlave.pingAll(anyLong(), any(TimeUnit.class))).thenReturn(true);

        var result = new RedisHealthCheck(redisClient, TIMEOUT, true).execute();

        assertThat(result.isHealthy()).isTrue();
        verify(redisClient).getRedisNodes(RedisNodes.SENTINEL_MASTER_SLAVE);
    }

    @Test
    @DisplayName("Should report unhealthy when a sentinel discovered node does not respond")
    void shouldReportUnhealthyWhenSentinelDiscoveredNodeDoesNotRespond() {
        when(redisClient.getRedisNodes(RedisNodes.SENTINEL_MASTER_SLAVE)).thenReturn(redisSentinelMasterSlave);
        when(redisSentinelMasterSlave.pingAll(anyLong(), any(TimeUnit.class))).thenReturn(false);

        var result = new RedisHealthCheck(redisClient, TIMEOUT, true).execute();

        assertThat(result.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("Should report unhealthy when redis cannot be reached")
    void shouldReportUnhealthyWhenRedisCannotBeReached() {
        when(redisClient.getRedisNodes(RedisNodes.SENTINEL_MASTER_SLAVE))
                .thenThrow(new IllegalStateException("unreachable"));

        var result = new RedisHealthCheck(redisClient, TIMEOUT, true).execute();

        assertThat(result.isHealthy()).isFalse();
    }
}
