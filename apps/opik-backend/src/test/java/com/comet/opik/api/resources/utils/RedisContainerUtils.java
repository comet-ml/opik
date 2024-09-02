package com.comet.opik.api.resources.utils;

import com.redis.testcontainers.RedisContainer;
import lombok.experimental.UtilityClass;
import org.testcontainers.utility.DockerImageName;

@UtilityClass
public class RedisContainerUtils {

    public static RedisContainer newRedisContainer() {
        return new RedisContainer(DockerImageName.parse("redis"))
                .withReuse(true);
    }

}
