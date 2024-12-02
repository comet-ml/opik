package com.comet.opik.infrastructure.redis;

import java.net.URI;
import java.net.URISyntaxException;

public record RedisUrlParser(String scheme, String host, int port, int database) {

    public static RedisUrlParser parse(String redisUrl) {
        try {
            URI uri = new URI(redisUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 6379 : uri.getPort();
            String path = uri.getPath();
            int database = 0;

            if (path != null && path.length() > 1) {
                database = getDatabase(path);
            }

            return new RedisUrlParser(scheme, host, port, database);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static int getDatabase(String path) {
        try {
            return Integer.parseInt(path.substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid database index in Redis URL: " + path);
        }
    }
}
