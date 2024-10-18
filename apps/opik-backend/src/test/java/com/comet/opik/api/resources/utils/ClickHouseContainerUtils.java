package com.comet.opik.api.resources.utils;

import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

public class ClickHouseContainerUtils {

    public static final String DATABASE_NAME = "opik";
    public static final String DATABASE_NAME_VARIABLE = "ANALYTICS_DB_DATABASE_NAME";

    public static ClickHouseContainer newClickHouseContainer() {
        return newClickHouseContainer(true);
    }

    public static ClickHouseContainer newClickHouseContainer(boolean reusable) {
        return new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.3.6.48-alpine"))
                .withReuse(reusable);
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
