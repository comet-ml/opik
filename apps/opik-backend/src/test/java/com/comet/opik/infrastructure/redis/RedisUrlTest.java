package com.comet.opik.infrastructure.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Redis URL parser Unit Test")
class RedisUrlTest {

    public static Stream<Arguments> testRedisUrlParser() {
        return Stream.of(
                // URL without credentials
                Arguments.of("redis://redis-server.example.com:7000/5",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("redis-server.example.com")
                                .port(7000)
                                .database(5)
                                .username(Optional.empty())
                                .password(Optional.empty())
                                .address("redis://redis-server.example.com:7000")
                                .build()),
                // Secure URL
                Arguments.of("rediss://master.redis.cache.amazonaws.com:7001/6",
                        RedisUrl.builder()
                                .scheme("rediss")
                                .host("master.redis.cache.amazonaws.com")
                                .port(7001)
                                .database(6)
                                .username(Optional.empty())
                                .password(Optional.empty())
                                .address("rediss://master.redis.cache.amazonaws.com:7001")
                                .build()),
                // URLs with whitespace (should be trimmed)
                Arguments.of("  redis://localhost:7000/5  ",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("localhost")
                                .port(7000)
                                .database(5)
                                .username(Optional.empty())
                                .password(Optional.empty())
                                .address("redis://localhost:7000")
                                .build()),
                // URL with default port
                Arguments.of("redis://localhost/5",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("localhost")
                                .port(6379)
                                .database(5)
                                .username(Optional.empty())
                                .password(Optional.empty())
                                .address("redis://localhost:6379")
                                .build()),
                // URL with default database without trailing slash
                Arguments.of("redis://localhost:7000",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("localhost")
                                .port(7000)
                                .database(0)
                                .username(Optional.empty())
                                .password(Optional.empty())
                                .address("redis://localhost:7000")
                                .build()),
                // URL with default database with trailing slash
                Arguments.of("redis://:opik@redis:7000/",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("redis")
                                .port(7000)
                                .database(0)
                                .username(Optional.empty())
                                .password(Optional.of("opik"))
                                .address("redis://redis:7000")
                                .build()),
                // URL with username and password
                Arguments.of("redis://user123:pass456@localhost:7000/5",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("localhost")
                                .port(7000)
                                .database(5)
                                .username(Optional.of("user123"))
                                .password(Optional.of("pass456"))
                                .address("redis://localhost:7000")
                                .build()),
                // URL with username only
                Arguments.of("redis://user123@somewhere-redis.com:7000/5",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("somewhere-redis.com")
                                .port(7000)
                                .database(5)
                                .username(Optional.of("user123"))
                                .password(Optional.empty())
                                .address("redis://somewhere-redis.com:7000")
                                .build()),
                // URLs with password only
                Arguments.of("rediss://:xxxxxxxxxxx@redis-16379.hosted.com:16379/16",
                        RedisUrl.builder()
                                .scheme("rediss")
                                .host("redis-16379.hosted.com")
                                .port(16379)
                                .database(16)
                                .username(Optional.empty())
                                .password(Optional.of("xxxxxxxxxxx"))
                                .address("rediss://redis-16379.hosted.com:16379")
                                .build()),
                // URL-encoded credentials - both username and password with special chars
                Arguments.of("redis://user%3Aname:pass%40word@localhost:7000/5",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("localhost")
                                .port(7000)
                                .database(5)
                                .username(Optional.of("user:name"))
                                .password(Optional.of("pass@word"))
                                .address("redis://localhost:7000")
                                .build()),
                // URL-encoded credentials - username with special chars
                Arguments.of("redis://user%3Aname@localhost:7000/5",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("localhost")
                                .port(7000)
                                .database(5)
                                .username(Optional.of("user:name"))
                                .password(Optional.empty())
                                .address("redis://localhost:7000")
                                .build()),
                // URLs with password containing special characters unencoded
                Arguments.of("redis://:password<with>special&chars@localhost:7000/5",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("localhost")
                                .port(7000)
                                .database(5)
                                .username(Optional.empty())
                                .password(Optional.of("password<with>special&chars"))
                                .address("redis://localhost:7000")
                                .build()),
                // URL-encoded credentials - password with special chars
                Arguments.of("redis://:my%40pass%3Aword@localhost:7000/5",
                        RedisUrl.builder()
                                .scheme("redis")
                                .host("localhost")
                                .port(7000)
                                .database(5)
                                .username(Optional.empty())
                                .password(Optional.of("my@pass:word"))
                                .address("redis://localhost:7000")
                                .build()));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Test parse method with different URLs")
    void testRedisUrlParser(String url, RedisUrl expected) {
        var redisUrl = RedisUrl.parse(url);

        assertThat(redisUrl).isEqualTo(expected);
    }

    /**
     * Provides test cases for invalid Redis URLs that should throw IllegalArgumentException.
     * Format: url, expectedMessageFragment
     */
    public static Stream<Arguments> invalidRedisUrls() {
        return Stream.of(
                // Blank URLs
                Arguments.of(null, "Redis url cannot be blank"),
                Arguments.of("", "Redis url cannot be blank"),
                Arguments.of("   ", "Redis url cannot be blank"),
                // Missing scheme
                Arguments.of("localhost:7000/5", "Invalid Redis URL: missing scheme"),
                // Invalid database indices
                Arguments.of("redis://localhost:7000/abc", "Invalid Redis URL"),
                // Malformed URLs
                Arguments.of("redis://", "Invalid Redis URL"),
                Arguments.of("redis://local host:6379/0", "Invalid Redis URL"));
    }

    @ParameterizedTest
    @MethodSource("invalidRedisUrls")
    @DisplayName("Should throw IllegalArgumentException for invalid Redis URLs")
    void shouldThrowIllegalArgumentExceptionForInvalidUrls(String invalidUrl, String expectedMessageFragment) {
        // When & Then
        assertThatThrownBy(() -> RedisUrl.parse(invalidUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessageFragment);
    }
}
