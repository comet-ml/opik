package com.comet.opik.api.resources.utils;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

public class MySQLContainerUtils {

    public static MySQLContainer<?> newMySQLContainer() {
        return newMySQLContainer(true);
    }

    public static MySQLContainer<?> newMySQLContainer(boolean reusable) {
        return new MySQLContainer<>(DockerImageName.parse("mysql"))
                .withUrlParam("createDatabaseIfNotExist", "true")
                .withUrlParam("rewriteBatchedStatements", "true")
                .withUrlParam("serverTimezone", "UTC")
                .withDatabaseName("opik")
                .withPassword("opik")
                .withUsername("opik")
                .withReuse(reusable)
                .withCommand("--max_connections=500");
    }

    public static Map<String, String> migrationParameters() {
        return Map.of("STATE_DB_DATABASE_NAME", "opik");
    }
}
