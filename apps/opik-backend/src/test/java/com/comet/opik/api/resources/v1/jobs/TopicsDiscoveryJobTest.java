package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.domain.topics.TopicRunStore;
import com.comet.opik.domain.topics.TopicWorkspaceService;
import com.comet.opik.domain.topics.TopicsRunPlanner;
import com.comet.opik.infrastructure.DistributedLockConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.bi.OpikGuiceyLifecycleEventListener;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.redis.RedisModule;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jobs.GuiceJobManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.quartz.Scheduler;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.config.Config;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.GuiceyLifecycleEvent;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.InjectorPhaseEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topics discovery job")
class TopicsDiscoveryJobTest {

    /** planBudget 2m + countTimeout 60s + leaseSlack 1m, from config-test.yml. */
    private static final Duration LEASE = Duration.ofMinutes(4);

    private final TopicsConfig config = enabled(TestConfigUtils.loadConfigTest()).getTopics();
    private final TopicsRunPlanner planner = mock(TopicsRunPlanner.class);
    private final TopicWorkspaceService workspaceService = mock(TopicWorkspaceService.class);
    private final TopicRunStore store = mock(TopicRunStore.class);
    private final LockService lockService = mock(LockService.class);
    private TopicsDiscoveryJob job;

    @BeforeEach
    void setUp() {
        when(planner.plan(any())).thenReturn(Mono.just(0));
        when(workspaceService.enabledWorkspaceIds()).thenReturn(List.of());
        when(store.deleteTopicsOfMissingProjects(any())).thenReturn(Mono.just(0));
        // This pod takes the leader lock: the lock service runs the action, here before doJob returns.
        when(lockService.bestEffortLock(eq(TopicsDiscoveryJob.LEADER_LOCK), any(), any(), eq(LEASE),
                eq(Duration.ZERO))).thenAnswer(invocation -> invocation.<Mono<?>>getArgument(1));
        job = new TopicsDiscoveryJob(config, planner, workspaceService, store, lockService);
    }

    @Test
    @DisplayName("plans once per plan interval and sweeps once per sweep interval, each on its own lock")
    void planAndSweepLocks() {
        var now = new AtomicReference<>(Instant.EPOCH);
        var plan = new FakeTokenLock(now);
        var sweep = new FakeTokenLock(now);
        when(lockService.lockUsingToken(eq(TopicsDiscoveryJob.PLAN_LOCK), any()))
                .thenAnswer(invocation -> plan.take(invocation.getArgument(1)));
        when(lockService.lockUsingToken(eq(TopicsDiscoveryJob.SWEEP_LOCK), any()))
                .thenAnswer(invocation -> sweep.take(invocation.getArgument(1)));

        job.doJob(null);
        verify(planner).plan(any());
        // The sweep runs on a bounded-elastic thread.
        verify(workspaceService, timeout(5_000)).enabledWorkspaceIds();
        // One interval less the 1s jitter margin.
        assertThat(plan.heldUntil.get()).isEqualTo(Instant.EPOCH.plus(Duration.ofMinutes(5).minusSeconds(1)));
        assertThat(sweep.heldUntil.get()).isEqualTo(Instant.EPOCH.plus(Duration.ofHours(24).minusSeconds(1)));

        now.set(Instant.EPOCH.plus(Duration.ofMinutes(2)));
        job.doJob(null);
        verify(planner).plan(any());

        now.set(Instant.EPOCH.plus(Duration.ofMinutes(5)));
        job.doJob(null);
        verify(planner, times(2)).plan(any());
        verify(workspaceService).enabledWorkspaceIds();
    }

    @Test
    @DisplayName("a failed plan is logged and the sweep still runs")
    void failedPlanStillSweeps() {
        when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.just(true));
        when(planner.plan(any())).thenReturn(Mono.error(new IllegalStateException("plan failed")));

        job.doJob(null);

        verify(workspaceService, timeout(5_000)).enabledWorkspaceIds();
    }

    @Test
    @DisplayName("an interrupt cancels every plan in flight, not only the last fire's, and a later fire takes no lock")
    void interrupt() throws Exception {
        var cancelled = new AtomicInteger();
        when(lockService.bestEffortLock(eq(TopicsDiscoveryJob.LEADER_LOCK), any(), any(), eq(LEASE),
                eq(Duration.ZERO))).thenReturn(Mono.never().doOnCancel(cancelled::incrementAndGet));

        job.doJob(null);
        job.doJob(null);
        job.interrupt();
        assertThat(cancelled).hasValue(2);

        job.doJob(null);
        verify(lockService, times(2)).bestEffortLock(any(), any(), any(), any(), any());
    }

    /**
     * The job on real Redis locks: two pods, and a pod shutdown through the lifecycle listener's
     * {@code ApplicationShutdown} event. The plan is detached from its Quartz fire, so only the listener's interrupt
     * reaches it.
     */
    @Nested
    @DisplayName("on Redis")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class OnRedis {

        // Its own Redis database: the container is reused across classes, and this class flushes it before each test.
        private static final int DATABASE = 9;

        private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
        private RedissonReactiveClient redisClient;
        private OpikConfiguration opikConfig;
        private LockService lockService;
        private TopicsDiscoveryJob job;
        private Injector injector;
        private Scheduler scheduler;
        private OpikGuiceyLifecycleEventListener listener;

        @BeforeAll
        void beforeAll() {
            redis.start();
            var config = new Config();
            config.useSingleServer().setAddress(redis.getRedisURI()).setDatabase(DATABASE);
            redisClient = Redisson.create(config).reactive();
        }

        @AfterAll
        void afterAll() {
            redisClient.shutdown();
        }

        @BeforeEach
        void setUp() throws Exception {
            redisClient.getKeys().flushdb().block();
            var redisModule = new RedisModule();
            lockService = spy(redisModule.lockService(redisClient,
                    DistributedLockConfig.builder().lockTimeoutMS(5_000).ttlInSeconds(60).build(),
                    redisModule.lockMetrics()));
            opikConfig = enabled(TestConfigUtils.loadConfigTest());
            job = newJob();

            injector = mock(Injector.class);
            var jobManager = mock(GuiceJobManager.class);
            scheduler = mock(Scheduler.class);
            when(injector.getInstance(GuiceJobManager.class)).thenReturn(jobManager);
            when(injector.getInstance(OpikConfiguration.class)).thenReturn(opikConfig);
            when(injector.getInstance(TopicsDiscoveryJob.class)).thenReturn(job);
            when(jobManager.getScheduler()).thenReturn(scheduler);
            when(scheduler.getJobKeys(any())).thenReturn(Set.of());

            listener = new OpikGuiceyLifecycleEventListener();
            var run = mock(InjectorPhaseEvent.class);
            when(run.getType()).thenReturn(GuiceyLifecycle.ApplicationRun);
            when(run.getInjector()).thenReturn(injector);
            listener.onEvent(run);
        }

        @AfterEach
        void tearDown() throws Exception {
            job.interrupt();
        }

        @Test
        @DisplayName("two pods that fire within one interval plan once")
        void twoPodsPlanOnce() throws Exception {
            var other = newJob();

            job.doJob(null);
            other.doJob(null);
            job.doJob(null);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(planner).plan(any()));
            // Both fires after the first one ended: neither may plan again within the interval.
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> verify(planner).plan(any()));
            other.interrupt();
        }

        @Test
        @DisplayName("a shutdown cancels the plan in flight, frees the leader lock at once and starts no new plan")
        void shutdownCancelsThePlanInFlight() throws Exception {
            var subscribed = new CountDownLatch(1);
            var cancelled = new CountDownLatch(1);
            when(planner.plan(any())).thenReturn(Mono.<Integer>never()
                    .doOnSubscribe(__ -> subscribed.countDown())
                    .doOnCancel(cancelled::countDown));

            job.doJob(null);
            await().atMost(Duration.ofSeconds(10)).until(() -> subscribed.getCount() == 0);
            assertThat(tryLeaderLock()).isFalse();

            shutdown();

            await().atMost(Duration.ofSeconds(10)).until(() -> cancelled.getCount() == 0);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(tryLeaderLock()).isTrue());
            verify(scheduler).shutdown(false);

            // A fire Quartz still delivers after the shutdown takes no lock.
            clearInvocations(lockService);
            job.doJob(null);
            verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("with topics off the discovery job is not created at shutdown, and the scheduler still shuts down")
        void disabledJobIsNotCreated() throws Exception {
            opikConfig.getTopics().setEnabled(false);

            shutdown();

            verify(injector, never()).getInstance(TopicsDiscoveryJob.class);
            verify(scheduler).shutdown(false);
        }

        private TopicsDiscoveryJob newJob() {
            return new TopicsDiscoveryJob(opikConfig.getTopics(), planner, workspaceService, store, lockService);
        }

        private void shutdown() {
            var event = mock(GuiceyLifecycleEvent.class);
            when(event.getType()).thenReturn(GuiceyLifecycle.ApplicationShutdown);
            listener.onEvent(event);
        }

        private boolean tryLeaderLock() {
            return Boolean.TRUE.equals(lockService.bestEffortLock(TopicsDiscoveryJob.LEADER_LOCK, Mono.just(true),
                    Mono.empty(), Duration.ofSeconds(5), Duration.ZERO).block(Duration.ofSeconds(10)));
        }
    }

    private static OpikConfiguration enabled(OpikConfiguration config) {
        config.getTopics().setEnabled(true);
        return config;
    }

    /** A lock as Redis keeps it, a key set if absent with a TTL, on the test's clock. */
    private record FakeTokenLock(AtomicReference<Instant> now, AtomicReference<Instant> heldUntil) {

        FakeTokenLock(AtomicReference<Instant> now) {
            this(now, new AtomicReference<>(Instant.EPOCH));
        }

        Mono<Boolean> take(Duration ttl) {
            if (now.get().isBefore(heldUntil.get())) {
                return Mono.just(false);
            }
            heldUntil.set(now.get().plus(ttl));
            return Mono.just(true);
        }
    }
}
