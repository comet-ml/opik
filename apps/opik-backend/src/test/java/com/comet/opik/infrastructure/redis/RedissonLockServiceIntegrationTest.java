package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.infrastructure.lock.LockService;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(parallel = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonLockServiceIntegrationTest {

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    static {
        MYSQL.start();
        CLICKHOUSE.start();
        REDIS.start();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(MYSQL.getJdbcUrl(),
                databaseAnalyticsFactory, null, REDIS.getRedisURI());
    }

    @Test
    void testExecuteWithLock_AddIfAbsent_Mono(LockService lockService) {
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "test-lock");
        List<String> sharedList = new ArrayList<>();

        String[] valuesToAdd = {"A", "B", "C", "A", "B", "C", "A", "B", "C"};

        Flux<Void> actions = Flux.fromArray(valuesToAdd)
                .flatMap(value -> lockService.executeWithLock(lock, Mono.fromRunnable(() -> {
                    if (!sharedList.contains(value)) {
                        sharedList.add(value);
                    }
                })), 5)
                .thenMany(Flux.empty());

        StepVerifier.create(actions)
                .expectSubscription()
                .verifyComplete();

        // Verify that the list contains only unique values
        assertEquals(3, sharedList.size(), "The list should contain only unique values");
        assertTrue(sharedList.contains("A"));
        assertTrue(sharedList.contains("B"));
        assertTrue(sharedList.contains("C"));
    }

    @Test
    void testExecuteWithLock_AddIfAbsent_Flux(LockService lockService) {
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "test-lock");
        List<String> sharedList = new ArrayList<>();

        Flux<String> valuesToAdd = Flux.just("A", "B", "C", "A", "B", "C", "A", "B", "C");

        Flux<Void> actions = lockService.executeWithLock(lock, valuesToAdd
                .flatMap(value -> {

                    Mono<Void> objectMono = Mono.fromRunnable(() -> {
                        if (!sharedList.contains(value)) {
                            sharedList.add(value);
                        }
                    });

                    return objectMono.subscribeOn(Schedulers.parallel());
                }))
                .repeat(5);

        StepVerifier.create(actions)
                .expectSubscription()
                .verifyComplete();

        // Verify that the list contains only unique values
        assertEquals(3, sharedList.size(), "The list should contain only unique values");
        assertTrue(sharedList.contains("A"));
        assertTrue(sharedList.contains("B"));
        assertTrue(sharedList.contains("C"));
    }

}