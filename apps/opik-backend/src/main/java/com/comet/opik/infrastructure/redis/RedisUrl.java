package com.comet.opik.infrastructure.redis;

import com.google.common.base.Preconditions;
import lombok.Builder;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Builder(toBuilder = true)
public record RedisUrl(
        String scheme,
        String host,
        int port,
        int database,
        Optional<String> username,
        Optional<String> password,
        String address) {

    private static final String SCHEME_SEPARATOR = "://";
    private static final char CREDENTIALS_SEPARATOR = '@';
    private static final char PASSWORD_SEPARATOR = ':';
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final int DEFAULT_DATABASE = 0;

    /**
     * Parses a Redis connection URL and extracts its components.
     * <p>
     * The Redis URL format is: {@code redis[s]://[[username][:password]@][host][:port][/db-number]}
     * <p>
     * Where:
     * <ul>
     *   <li>{@code redis} or {@code rediss} - Connection scheme (rediss for TLS)</li>
     *   <li>{@code username} - Optional Redis username (URL-encoded)</li>
     *   <li>{@code password} - Optional Redis password (URL-encoded)</li>
     *   <li>{@code host} - Required Redis server hostname or IP address</li>
     *   <li>{@code port} - Optional port number (defaults to 6379)</li>
     *   <li>{@code db-number} - Optional database number (defaults to 0)</li>
     * </ul>
     * <p>
     * Examples:
     * <pre>
     * redis://localhost:6379/0
     * redis://:password@localhost:6379
     * redis://username:password@localhost:6379/1
     * redis://user%3Aname:pass%3Eword@localhost:6379         // username contains :, password contains >
     * rediss://username:password@secure.redis.com:6380/0
     * </pre>
     *
     * @param redisUrl the Redis connection URL to parse
     * @return a {@link RedisUrl} object containing the parsed components with decoded credentials
     * @throws IllegalArgumentException if the URL is blank or has invalid format
     * @see <a href="https://redis.io/docs/latest/develop/clients/nodejs/connect/">Redis Connection Guide</a>
     * @see <a href="https://github.com/redis/lettuce/wiki/Redis-URI-and-connection-details">Redis URI Documentation</a>
     */
    public static RedisUrl parse(String redisUrl) {
        Preconditions.checkArgument(StringUtils.isNotBlank(redisUrl), "Redis url cannot be blank");
        redisUrl = redisUrl.trim();
        var urlWithoutCredentials = redisUrl;
        var username = Optional.<String>empty();
        var password = Optional.<String>empty();
        var schemeIndex = redisUrl.indexOf(SCHEME_SEPARATOR);
        if (schemeIndex == -1) {
            throw new IllegalArgumentException("Invalid Redis URL: missing scheme '%s'".formatted(redisUrl));
        }
        try {
            var credentialsIndex = redisUrl.indexOf(CREDENTIALS_SEPARATOR, schemeIndex + SCHEME_SEPARATOR.length());
            if (credentialsIndex != -1) {
                // Credentials are present in format: [username][:password]@
                var credentials = redisUrl.substring(schemeIndex + SCHEME_SEPARATOR.length(), credentialsIndex);
                var passwordIndex = credentials.indexOf(PASSWORD_SEPARATOR);
                if (passwordIndex != -1) {
                    // Format: username:password@ or :password@ (both or password only)
                    username = Optional.of(credentials.substring(0, passwordIndex))
                            .map(u -> URLDecoder.decode(u, StandardCharsets.UTF_8))
                            .filter(StringUtils::isNotBlank);
                    password = Optional.of(credentials.substring(passwordIndex + 1))
                            .map(p -> URLDecoder.decode(p, StandardCharsets.UTF_8))
                            .filter(StringUtils::isNotBlank);
                } else {
                    // Format: username@ (username only)
                    username = Optional.of(credentials)
                            .map(u -> URLDecoder.decode(u, StandardCharsets.UTF_8))
                            .filter(StringUtils::isNotBlank);
                }
                // Build URL without credentials for URI parsing
                urlWithoutCredentials = redisUrl.substring(0, schemeIndex + SCHEME_SEPARATOR.length())
                        + redisUrl.substring(credentialsIndex + 1);
            }
            // Use URI to parse the URL (handling special characters)
            var uri = new URI(urlWithoutCredentials);
            var scheme = uri.getScheme();
            var host = uri.getHost();
            int port = uri.getPort() == -1 ? DEFAULT_REDIS_PORT : uri.getPort();
            return RedisUrl.builder()
                    .scheme(scheme)
                    .host(host)
                    .port(port)
                    .database(getDatabase(uri.getPath()))
                    .username(username)
                    .password(password)
                    .address("%s%s%s:%d".formatted(scheme, SCHEME_SEPARATOR, host, port))
                    .build();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Redis URL: '%s'".formatted(redisUrl), exception);
        }
    }

    private static int getDatabase(String path) {
        if (StringUtils.isBlank(path)) {
            return DEFAULT_DATABASE;
        }
        // Excluding the leading '/' character
        var dbString = path.substring(1);
        if (StringUtils.isBlank(dbString)) {
            return DEFAULT_DATABASE;
        }
        return Integer.parseInt(dbString);
    }
}
