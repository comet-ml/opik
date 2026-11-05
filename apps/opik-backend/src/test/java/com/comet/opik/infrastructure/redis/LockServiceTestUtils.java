package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.DistributedLockConfig;
import com.comet.opik.infrastructure.lock.LockService;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.redisson.api.RedissonClient;

/** The real, package-private lock service, for tests outside this package that need cross-pod locking. */
@UtilityClass
public class LockServiceTestUtils {

    public static LockService newLockService(@NonNull RedissonClient redisson) {
        var config = DistributedLockConfig.builder()
                .lockTimeoutMS(500)
                .ttlInSeconds(5)
                .build();
        return new RedissonLockService(redisson.reactive(), config, new LockMetrics());
    }
}
