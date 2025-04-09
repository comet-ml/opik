package com.comet.opik.api.resources.utils;

import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.util.Map;

public class ClickHouseContainerUtils {

    public static final String DATABASE_NAME = "opik";
    public static final String DATABASE_NAME_VARIABLE = "ANALYTICS_DB_DATABASE_NAME";
    private static final Network NETWORK = Network.newNetwork();

    public static ClickHouseContainer newClickHouseContainer() {
        return newClickHouseContainer(true);
    }

    public static ClickHouseContainer newClickHouseContainer(boolean reusable) {
        return new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.3.6.48-alpine"))
                .withReuse(reusable);
    }

    public static GenericContainer<?> newZookeeperContainer() {
        return newZookeeperContainer(true, NETWORK);
    }

    public static GenericContainer<?> newZookeeperContainer(boolean reusable, Network network) {
        return new GenericContainer<>("zookeeper:3.9.3")
                .withExposedPorts(2181)
                .withNetworkAliases("zookeeper")
                .withNetwork(network)
                .withEnv("ALLOW_ANONYMOUS_LOGIN", "yes")
                .withEnv("ZOO_MY_ID", "1")
                .withReuse(reusable);
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

            return container
                    .withReuse(reusable)
                    .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                    .withNetwork(network)
                    .withCopyFileToContainer(MountableFile.forClasspathResource("macros.xml"),
                            "/etc/clickhouse-server/config.d/macros.xml")
                    .withCopyFileToContainer(MountableFile.forClasspathResource("zookeeper.xml"),
                            "/etc/clickhouse-server/config.d/zookeeper.xml");

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
