package com.comet.opik.api.resources.utils;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

public class MySQLContainerUtils {

    public static MySQLContainer<?> newMySQLContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql"))
                .withUrlParam("createDatabaseIfNotExist", "true")
                .withUrlParam("rewriteBatchedStatements", "true")
                .withDatabaseName("opik")
                .withPassword("opik")
                .withUsername("opik")
                .withReuse(true);
    }

    public static Map<String, String> migrationParameters() {
        return Map.of("STATE_DB_DATABASE_NAME", "opik");
    }
}
