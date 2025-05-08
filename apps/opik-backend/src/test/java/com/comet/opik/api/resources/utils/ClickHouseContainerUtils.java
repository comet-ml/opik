package com.comet.opik.api.resources.utils;

import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.google.common.collect.Sets;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.util.Map;
import java.util.Set;

public class ClickHouseContainerUtils {

    public static final String DATABASE_NAME = "opik";
    public static final String DATABASE_NAME_VARIABLE = "ANALYTICS_DB_DATABASE_NAME";
    private static final Network NETWORK = Network.newNetwork();
    private static final Set<GenericContainer<?>> CONTAINERS = Sets.newConcurrentHashSet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CONTAINERS.forEach(container -> {
                if (container.isRunning()) {
                    container.stop();
                }
            });
            NETWORK.close();
        }));
    }

    public static ClickHouseContainer newClickHouseContainer() {
        return newClickHouseContainer(true);
    }

    public static ClickHouseContainer newClickHouseContainer(boolean reusable) {
        ClickHouseContainer container = new ClickHouseContainer(
                DockerImageName.parse("clickhouse/clickhouse-server:24.3.6.48-alpine"))
                .withReuse(reusable);

        CONTAINERS.add(container);

        return container;
    }

    public static GenericContainer<?> newZookeeperContainer() {
        return newZookeeperContainer(true, NETWORK);
    }

    public static GenericContainer<?> newZookeeperContainer(boolean reusable, Network network) {
        var container = new GenericContainer<>("zookeeper:3.9.3")
                .withExposedPorts(2181)
                .withNetworkAliases("zookeeper")
                .withNetwork(network)
                .withEnv("ALLOW_ANONYMOUS_LOGIN", "yes")
                .withEnv("ZOO_MY_ID", "1")
                .withReuse(reusable);

        CONTAINERS.add(container);

        return container;
    }

    public static ClickHouseContainer newClickHouseContainer(GenericContainer<?> zooKeeperContainer) {
        return newClickHouseContainer(true, NETWORK, zooKeeperContainer);
    }

    public static ClickHouseContainer newClickHouseContainer(boolean reusable, Network network,
            GenericContainer<?> zooKeeperContainer) {

        try {

            ClickHouseContainer container = newClickHouseContainer(reusable);

            if (zooKeeperContainer != null) {
                container.dependsOn(zooKeeperContainer);
            }

            CONTAINERS.add(container);

            return container
                    .withReuse(reusable)
                    .withNetworkAliases("clickhouse")
                    .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                    .withNetwork(network)
                    // Default user and password for test containers are test:test while for clickhouse it's default user and no password.
                    // We need to override the default user and password so that we can use the same credentials as test containers.
                    .withUsername("default")
                    .withPassword("")
                    .withCopyFileToContainer(MountableFile.forClasspathResource("clickhouse.xml"),
                            "/etc/clickhouse-server/config.d/clickhouse.xml");

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static DatabaseAnalyticsFactory newDatabaseAnalyticsFactory(ClickHouseContainer clickHouseContainer,
            String databaseName) {
        var databaseAnalyticsFactory = new DatabaseAnalyticsFactory();
        databaseAnalyticsFactory.setProtocol(DatabaseAnalyticsFactory.Protocol.HTTP);
        databaseAnalyticsFactory.setHost(clickHouseContainer.getHost());
        databaseAnalyticsFactory.setPort(clickHouseContainer.getMappedPort(8123));
        databaseAnalyticsFactory.setUsername(clickHouseContainer.getUsername());
        databaseAnalyticsFactory.setPassword(clickHouseContainer.getPassword());
        databaseAnalyticsFactory.setDatabaseName(databaseName);
        return databaseAnalyticsFactory;
    }

    public static Map<String, String> migrationParameters() {
        return Map.of(DATABASE_NAME_VARIABLE, DATABASE_NAME);
    }
}
