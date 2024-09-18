package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import lombok.NonNull;
import org.redisson.api.RScript;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;

import java.util.List;

public class RedisRateLimitService implements RateLimitService {

    private static final String LUA_SCRIPT_ADD = """
            local current = redis.call('GET', KEYS[1])
            if not current then
                current = 0
            else
                current = tonumber(current)
            end

            local limit = tonumber(ARGV[1])
            local increment = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])

            if (current + increment) > limit then
                return 0  -- Failure, limit exceeded
            else
                redis.call('INCRBY', KEYS[1], increment)

                 if redis.call('TTL', KEYS[1]) == -1 then
                    redis.call('EXPIRE', KEYS[1], ttl, 'NX')
                 end

                return 1  -- Success, increment done
            end
            """;

    private static final String LUA_SCRIPT_DECR = """
            local current = redis.call('GET', KEYS[1])
            if not current then
                current = 0
            else
                current = tonumber(current)
            end

            local decrement = tonumber(ARGV[1])

            if (current - decrement) > 0 then
                redis.call('DECRBY', KEYS[1], decrement)
            else
                redis.call('SET', KEYS[1], 0)
            end

            return 'OK'
            """;

    private final RedissonReactiveClient redisClient;

    public RedisRateLimitService(RedissonReactiveClient redisClient) {
        this.redisClient = redisClient;
    }

    @Override
    public Mono<Boolean> isLimitExceeded(String apiKey, long events, String bucketName, long limit,
            long limitDurationInSeconds) {

        Mono<Long> eval = redisClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                LUA_SCRIPT_ADD,
                RScript.ReturnType.INTEGER,
                List.of(bucketName + ":" + apiKey),
                limit,
                events,
                limitDurationInSeconds);

        return eval.map(result -> result == 0);
    }

    @Override
    public Mono<Void> decrement(@NonNull String apiKey, @NonNull String bucketName, long events) {
        Mono<String> eval = redisClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                LUA_SCRIPT_DECR,
                RScript.ReturnType.VALUE,
                List.of(bucketName + ":" + apiKey),
                events);

        return eval.map("OK"::equals)
                .switchIfEmpty(Mono.error(new IllegalStateException("Rate limit bucket not found")))
                .then();
    }

}
