package com.comet.opik.infrastructure.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Redis URL parser Unit Test")
class RedisUrlParserTest {

    public static Stream<Arguments> testRedisUrlParser() {
        return Stream.of(
                Arguments.of("redis://localhost:6379/0", "redis", "localhost", 6379, 0),
                Arguments.of("redis://localhost:6379/1", "redis", "localhost", 6379, 1),
                Arguments.of("redis://localhost:6379", "redis", "localhost", 6379, 0),
                Arguments.of("rediss://localhost:6379", "rediss", "localhost", 6379, 0),
                Arguments.of("rediss://master.redis.cache.amazonaws.com:7000/3", "rediss",
                        "master.redis.cache.amazonaws.com", 7000, 3));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Test parse method with different URLs")
    void testRedisUrlParser(String redisUrl, String scheme, String host, int port, int database) {
        RedisUrlParser redisUrlParser = RedisUrlParser.parse(redisUrl);

        assertEquals(scheme, redisUrlParser.scheme());
        assertEquals(host, redisUrlParser.host());
        assertEquals(port, redisUrlParser.port());
        assertEquals(database, redisUrlParser.database());
    }

}