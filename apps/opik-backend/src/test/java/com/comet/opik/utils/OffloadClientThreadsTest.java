package com.comet.opik.utils;

import com.clickhouse.client.api.Client;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.RedisConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs each {@link Offload} helper after a reply from the backend's real clients (r2dbc and v2 ClickHouse, Redisson),
 * in the shapes the backend uses, and records the pool every step ran on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OffloadClientThreadsTest {

    private static final String CLICKHOUSE_WORKER = "ClickHouseWorker";
    private static final String REDISSON_NETTY = "redisson-netty";
    private static final String CHC_OPERATION = "chc-operation";
    private static final String BOUNDED_ELASTIC = "boundedElastic";
    private static final String PARALLEL = "parallel";

    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer();
    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();

    private ConnectionFactory connectionFactory;
    private Client clickHouseClient;
    private RedissonClient redissonClient;
    private RedissonReactiveClient redisClient;

    /** The pool the client replied on, the pool the next step ran on as written today, and with {@code Offload}. */
    private record Pools(String reply, String withoutOffload, String withOffload) {
    }

    @BeforeAll
    void setUpAll() {
        Startables.deepStart(clickHouse, redis).join();

        var factory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, "default");
        connectionFactory = factory.build();
        clickHouseClient = factory.buildClient();

        var redisConfig = new RedisConfig();
        redisConfig.setSingleNodeUrl(redis.getRedisURI());
        redissonClient = Redisson.create(redisConfig.build());
        redisClient = redissonClient.reactive();
    }

    @AfterAll
    void tearDownAll() {
        clickHouseClient.close();
        redissonClient.shutdown();
    }

    @Test
    void blockingCallAfterClickHouseReadRunsOnBoundedElastic() {
        // The attachment delete cascade: a DAO read, then an S3 delete
        var actual = new Pools(
                clickHouseRows(1).map(__ -> pool()).blockFirst(),
                clickHouseRows(1).next().flatMap(__ -> Mono.fromCallable(this::pool)).block(),
                clickHouseRows(1).next().flatMap(__ -> Offload.blocking(this::pool)).block());

        assertThat(actual).isEqualTo(new Pools(CLICKHOUSE_WORKER, CLICKHOUSE_WORKER, BOUNDED_ELASTIC));
    }

    @Test
    void blockingCallAfterRedisReplyRunsOnBoundedElastic() {
        // The alert job: a Redis read, then a MySQL lookup
        var key = RandomStringUtils.secure().nextAlphanumeric(20);
        redisClient.getBucket(key).set(RandomStringUtils.secure().nextAlphanumeric(10)).block();

        var actual = new Pools(
                redisClient.getBucket(key).get().map(__ -> pool()).block(),
                redisClient.getBucket(key).get().flatMap(__ -> Mono.fromCallable(this::pool)).block(),
                redisClient.getBucket(key).get().flatMap(__ -> Offload.blocking(this::pool)).block());

        assertThat(actual).isEqualTo(new Pools(REDISSON_NETTY, REDISSON_NETTY, BOUNDED_ELASTIC));
    }

    @Test
    void perItemWorkOnClickHouseStreamRunsOnBoundedElastic() {
        // The streamer: subscribed on boundedElastic, each row written to the response in doOnNext
        var withoutOffload = clickHouseRows(5)
                .subscribeOn(Schedulers.boundedElastic())
                .map(__ -> pool())
                .distinct()
                .collectList()
                .block();
        var withOffload = Offload.blockingEach(clickHouseRows(5))
                .map(__ -> pool())
                .distinct()
                .collectList()
                .block();

        assertThat(List.of(withoutOffload, withOffload))
                .isEqualTo(List.of(List.of(CLICKHOUSE_WORKER), List.of(BOUNDED_ELASTIC)));
    }

    @Test
    void cpuStepAfterClickHouseReadRunsOnParallel() {
        // The topics window build: rows from ClickHouse, then work that grows with them
        var actual = new Pools(
                clickHouseRows(1).map(__ -> pool()).blockFirst(),
                clickHouseRows(1).next().map(__ -> pool()).block(),
                clickHouseRows(1).next().flatMap(__ -> Offload.cpu(this::pool)).block());

        assertThat(actual).isEqualTo(new Pools(CLICKHOUSE_WORKER, CLICKHOUSE_WORKER, PARALLEL));
    }

    @Test
    void stepAfterClickHouseV2InsertClientRunsOnItsOperationPool() {
        // The JSONEachRow bulk insert path replies on the v2 client's pool, not on ClickHouseWorker
        var actual = new Pools(
                clickHouseV2Query().map(__ -> pool()).block(),
                clickHouseV2Query().flatMap(__ -> Mono.fromCallable(this::pool)).block(),
                clickHouseV2Query().flatMap(__ -> Offload.blocking(this::pool)).block());

        assertThat(actual).isEqualTo(new Pools(CHC_OPERATION, CHC_OPERATION, BOUNDED_ELASTIC));
    }

    @Test
    void subscriberLogicResumesOnItsWorkersAfterClientCalls() {
        // A Redis subscriber: processEvent subscribed on workers, then Redis and ClickHouse calls
        Scheduler workers = Schedulers.newBoundedElastic(4, 100, "redis-subscriber-workers-scheduler-test");
        try {
            var key = RandomStringUtils.secure().nextAlphanumeric(20);
            redisClient.getBucket(key).set(RandomStringUtils.secure().nextAlphanumeric(10)).block();
            var flow = Mono.defer(() -> redisClient.getBucket(key).get()
                    .flatMap(__ -> clickHouseRows(1).next()));

            var actual = new Pools(
                    flow.map(__ -> pool()).subscribeOn(workers).block(),
                    flow.map(__ -> pool()).subscribeOn(workers).block(),
                    flow.transform(Offload::resumeOriginal)
                            .map(__ -> pool())
                            .subscribeOn(workers)
                            .contextWrite(context -> Offload.originalOn(context, workers))
                            .block());

            assertThat(actual).isEqualTo(new Pools(
                    CLICKHOUSE_WORKER, CLICKHOUSE_WORKER, "redis-subscriber-workers-scheduler-test"));
        } finally {
            workers.dispose();
        }
    }

    @Test
    void requestLogicResumesOnAVirtualThreadAfterClientCalls() {
        // A resource: the request context set as the resources do, then a ClickHouse read
        var requestContext = RequestContext.builder()
                .userName(RandomStringUtils.secure().nextAlphanumeric(10))
                .workspaceId(RandomStringUtils.secure().nextAlphanumeric(10))
                .workspaceName(RandomStringUtils.secure().nextAlphanumeric(10))
                .build();

        var resumed = clickHouseRows(1).next()
                .transform(Offload::resumeOriginal)
                .map(__ -> Thread.currentThread())
                .contextWrite(context -> AsyncUtils.setRequestContext(context, requestContext))
                .block();

        assertThat(resumed.isVirtual()).isTrue();
        assertThat(resumed.getName()).startsWith("request-vt-");
    }

    private Flux<Long> clickHouseRows(int count) {
        return Flux.usingWhen(connectionFactory.create(),
                connection -> Flux.from(connection.createStatement("SELECT number FROM system.numbers LIMIT :count")
                        .bind("count", count)
                        .execute())
                        .flatMap(result -> result.map((row, metadata) -> row.get("number", Long.class))),
                Connection::close);
    }

    private Mono<Long> clickHouseV2Query() {
        return Mono.fromFuture(() -> clickHouseClient.query("SELECT 1"))
                .map(response -> {
                    try (response) {
                        return response.getReadRows();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
    }

    private String pool() {
        return Thread.currentThread().getName().replaceAll("(-\\d+)+$", "");
    }
}
