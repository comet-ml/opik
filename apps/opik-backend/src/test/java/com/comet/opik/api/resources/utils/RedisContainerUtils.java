package com.comet.opik.api.resources.utils;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.redis.testcontainers.RedisContainer;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.stream.IntStream;

@UtilityClass
public class RedisContainerUtils {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2.4-alpine3.19");

    public static RedisContainer newRedisContainer() {
        return new RedisContainer(REDIS_IMAGE)
                .withReuse(true);
    }

    @Builder(toBuilder = true)
    public record SentinelTopology(String masterName, int masterPort, int replicaPort, int sentinelPort) {

        public String sentinelUrl(String host) {
            return "redis://%s:%d/0".formatted(host, sentinelPort);
        }
    }

    /** A started {@link #newSentinelContainer(SentinelTopology)} with the topology it listens on. */
    public record StartedSentinel(SentinelTopology topology, GenericContainer<?> container) {
    }

    /**
     * Starts a sentinel topology on free host ports. A port can be taken between probing it and Docker binding it, so
     * a failed start is retried on fresh ports rather than failing the test.
     */
    public static StartedSentinel startSentinel(String masterName) {
        ContainerLaunchException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            var topology = newSentinelTopology(masterName);
            var container = newSentinelContainer(topology);
            try {
                container.start();
                return new StartedSentinel(topology, container);
            } catch (ContainerLaunchException exception) {
                container.stop();
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    public static SentinelTopology newSentinelTopology(String masterName) {
        var ports = findFreePorts(3);
        return SentinelTopology.builder()
                .masterName(masterName)
                .masterPort(ports[0])
                .replicaPort(ports[1])
                .sentinelPort(ports[2])
                .build();
    }

    /**
     * A master, a replica and one sentinel in a single container, each listening on the same port inside and outside.
     * The sentinel then announces {@code 127.0.0.1:<port>} addresses that are reachable from the host as-is, so clients
     * connect exactly as in production, with no address translation.
     */
    @SuppressWarnings("resource")
    public static GenericContainer<?> newSentinelContainer(SentinelTopology topology) {
        var script = """
                redis-server --port %1$d --protected-mode no --daemonize yes
                redis-server --port %2$d --protected-mode no --replicaof 127.0.0.1 %1$d --daemonize yes
                printf 'port %3$d\\nprotected-mode no\\nsentinel monitor %4$s 127.0.0.1 %1$d 1\\n' > /tmp/sentinel.conf
                exec redis-sentinel /tmp/sentinel.conf
                """.formatted(topology.masterPort(), topology.replicaPort(), topology.sentinelPort(),
                topology.masterName());
        // Ready once the replica is in sync and the sentinel reports it, not merely once the ports listen
        var ready = "redis-cli -p %d INFO replication | grep -q master_link_status:up && redis-cli -p %d SENTINEL REPLICAS %s | grep -q %d"
                .formatted(topology.replicaPort(), topology.sentinelPort(), topology.masterName(),
                        topology.replicaPort());
        int[] ports = {topology.masterPort(), topology.replicaPort(), topology.sentinelPort()};
        return new GenericContainer<>(REDIS_IMAGE)
                .withExposedPorts(IntStream.of(ports).boxed().toArray(Integer[]::new))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(IntStream.of(ports)
                        // Loopback only: the topology has no auth
                        .mapToObj(port -> new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", port),
                                ExposedPort.tcp(port)))
                        .toList()))
                .withCommand("sh", "-c", script)
                .waitingFor(Wait.forSuccessfulCommand(ready));
    }

    // Held open together so the ports are distinct
    private static int[] findFreePorts(int count) {
        var sockets = new ArrayList<ServerSocket>();
        try {
            for (int i = 0; i < count; i++) {
                sockets.add(new ServerSocket(0));
            }
            return sockets.stream().mapToInt(ServerSocket::getLocalPort).toArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            sockets.forEach(socket -> {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Closing a probe socket cannot leave the port taken
                }
            });
        }
    }
}
