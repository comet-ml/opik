package com.comet.opik.infrastructure.redis;

import lombok.NonNull;

import java.net.URI;
import java.net.URISyntaxException;

public record RedisUrl(String scheme, String host, int port, int database) {

    public static RedisUrl parse(@NonNull String redisUrl) {
        try {
            URI uri = new URI(redisUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 6379 : uri.getPort();
            String path = uri.getPath();
            int database = 0;

            if (path != null && path.length() > 1) {
                database = getDatabase(path);
            }

            return new RedisUrl(scheme, host, port, database);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static int getDatabase(String path) {
        try {
            if (path.length() <= 1) {
                throw new IllegalArgumentException("Invalid database path in Redis URL: " + path);
            }
            return Integer.parseInt(path.substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid database index in Redis URL: " + path);
        }
    }
}
