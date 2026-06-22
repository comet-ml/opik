package com.comet.opik.api.resources.utils;

import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

public class MySQLContainerUtils {

    public static MySQLContainer newMySQLContainer() {
        return newMySQLContainer(true);
    }

    public static MySQLContainer newMySQLContainer(boolean reusable) {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4.2"))
                .withUrlParam("createDatabaseIfNotExist", "true")
                .withUrlParam("rewriteBatchedStatements", "true")
                // Keep Instant reads independent of the JVM default timezone (connectionTimeZone replaces the
                // deprecated serverTimezone alias; forceConnectionTimeZoneToSession pins the session time_zone to UTC).
                .withUrlParam("connectionTimeZone", "UTC")
                .withUrlParam("forceConnectionTimeZoneToSession", "true")
                .withDatabaseName("opik")
                .withPassword("opik")
                .withUsername("opik")
                .withReuse(reusable);
    }

    public static Map<String, String> migrationParameters() {
        return Map.of("STATE_DB_DATABASE_NAME", "opik");
    }
}
