package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.lock.LockService;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RedissonReactiveClient;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class RedissonLockServiceIntegrationTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        APP = newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(new CustomConfig("distributedLock.lockTimeoutMS", "100"),
                                new CustomConfig("distributedLock.ttlInSeconds", "1")))
                        .build());
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

    @Test
    void testExecuteWithLock_LockShouldHaveBeenEvicted(LockService lockService, RedissonReactiveClient redisClient) {
        LockService.Lock lock = new LockService.Lock(UUID.randomUUID(), "test-lock");
        List<String> sharedList = new ArrayList<>();

        lockService.executeWithLock(lock, Mono.delay(Duration.ofMillis(100)).then(Mono.fromCallable(() -> {
            sharedList.add("A");
            return true;
        }))).block();

        Mono.delay(Duration.ofMillis(1500)).block();

        StepVerifier.create(redisClient.getBucket(lock.key()).isExists())
                .assertNext(data -> assertThat(data).isFalse())
                .verifyComplete();

        lockService.executeWithLock(lock, Mono.delay(Duration.ofMillis(100)).then(Mono.fromCallable(() -> {
            sharedList.add("B");
            return true;
        }))).block();

        assertTrue(sharedList.contains("A"));
        assertTrue(sharedList.contains("B"));

        Mono.delay(Duration.ofSeconds(1)).block();

        StepVerifier.create(redisClient.getBucket(lock.key()).isExists())
                .assertNext(data -> assertThat(data).isFalse())
                .verifyComplete();
    }

}
