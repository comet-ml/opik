package com.comet.opik.api.resources.v1.jobs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.domain.topics.TopicsFirstBuildService;
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
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.GuiceyLifecycleEvent;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.InjectorPhaseEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topics first-build job")
class TopicsFirstBuildJobTest {

    private final TopicsConfig config = TestConfigUtils.loadConfigTest().getTopics();
    private final TopicsFirstBuildService firstBuildService = mock(TopicsFirstBuildService.class);
    private final LockService lockService = mock(LockService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TopicsFirstBuildJob.class);
    private TopicsFirstBuildJob job;

    @BeforeEach
    void setUp() {
        config.setEnabled(true);
        when(firstBuildService.lease()).thenReturn(Duration.ofMinutes(29));
        // This pod takes the leader lock: the lock service runs the action, here before doJob returns.
        when(lockService.bestEffortLock(eq(TopicsFirstBuildService.LEADER_LOCK), any(), any(),
                eq(Duration.ofMinutes(29)), eq(Duration.ZERO))).thenAnswer(invocation -> {
                    invocation.<Mono<?>>getArgument(1).block();
                    return Mono.empty();
                });
        job = new TopicsFirstBuildJob(config, firstBuildService, lockService);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("a second firing within one interval skips on the run lock; a firing one interval after the run runs")
    void runLock() {
        var now = new AtomicReference<>(Instant.EPOCH);
        var heldUntil = new AtomicReference<>(Instant.EPOCH);
        // The run lock as Redis keeps it, a key set if absent with a TTL, on the test's clock.
        when(lockService.lockUsingToken(eq(TopicsFirstBuildJob.RUN_LOCK), any())).thenAnswer(invocation -> {
            if (now.get().isBefore(heldUntil.get())) {
                return Mono.just(false);
            }
            heldUntil.set(now.get().plus(invocation.<Duration>getArgument(1)));
            return Mono.just(true);
        });

        job.doJob(null);
        verify(firstBuildService).run(any());
        assertThat(heldUntil.get()).isEqualTo(Instant.EPOCH.plus(Duration.ofMinutes(10).minusSeconds(1)));

        now.set(Instant.EPOCH.plus(Duration.ofMinutes(5)));
        job.doJob(null);
        verify(firstBuildService).run(any());
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("lock 'topics:first_build:run'");
        });

        now.set(Instant.EPOCH.plus(Duration.ofMinutes(10)));
        job.doJob(null);
        verify(firstBuildService, times(2)).run(any());
    }

    @Test
    @DisplayName("an interrupt cancels every run in flight, not only the last fire's, and a later firing takes no lock")
    void interrupt() throws Exception {
        var cancelled = new AtomicInteger();
        // Two runs still going when the pod shuts down: the second fire must not detach the first.
        when(lockService.bestEffortLock(eq(TopicsFirstBuildService.LEADER_LOCK), any(), any(),
                eq(Duration.ofMinutes(29)), eq(Duration.ZERO)))
                .thenReturn(Mono.never().doOnCancel(cancelled::incrementAndGet));

        job.doJob(null);
        job.doJob(null);
        job.interrupt();
        assertThat(cancelled).hasValue(2);

        job.doJob(null);
        verify(lockService, times(2)).bestEffortLock(eq(TopicsFirstBuildService.LEADER_LOCK), any(), any(), any(),
                any());
        verify(firstBuildService, never()).run(any());
    }

    /**
     * A pod shutdown through the lifecycle listener's {@code ApplicationShutdown} event, with a first build in flight on a
     * real Redis lock. The run is detached from its Quartz fire, so only the listener's interrupt reaches it. Its fields
     * shadow the outer ones: here the job runs on a real lock service.
     */
    @Nested
    @DisplayName("on shutdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Shutdown {

        // Its own Redis database: the container is reused across classes, and this class flushes it before each test.
        private static final int DATABASE = 9;
        // Far longer than any wait below: a lock still held after the shutdown would fail the test, not expire in it.
        private static final Duration LEASE = Duration.ofMinutes(10);

        private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
        private RedissonReactiveClient redisClient;

        private OpikConfiguration opikConfig;
        private TopicsFirstBuildService firstBuildService;
        private LockService lockService;
        private TopicsFirstBuildJob job;
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
            opikConfig = TestConfigUtils.loadConfigTest();
            opikConfig.getTopics().setEnabled(true);
            firstBuildService = mock(TopicsFirstBuildService.class);
            when(firstBuildService.lease()).thenReturn(LEASE);
            job = new TopicsFirstBuildJob(opikConfig.getTopics(), firstBuildService, lockService);

            injector = mock(Injector.class);
            var jobManager = mock(GuiceJobManager.class);
            scheduler = mock(Scheduler.class);
            when(injector.getInstance(GuiceJobManager.class)).thenReturn(jobManager);
            when(injector.getInstance(OpikConfiguration.class)).thenReturn(opikConfig);
            when(injector.getInstance(TopicsFirstBuildJob.class)).thenReturn(job);
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
        @DisplayName("cancels the run in flight, frees the leader lock at once and starts no new run; a fire during the run "
                + "does not detach it")
        void shutdownCancelsTheRunInFlight() throws Exception {
            var started = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            doAnswer(invocation -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return null;
            }).when(firstBuildService).run(any());

            job.doJob(null);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            verify(firstBuildService).run(any());
            // A fire during the run skips on the leader lock: it must not take the run's place in the job.
            job.doJob(null);
            assertThat(tryLeaderLock()).isFalse();

            shutdown();

            assertThat(interrupted.await(10, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(tryLeaderLock()).isTrue());
            verify(scheduler).shutdown(false);

            // A fire Quartz still delivers after the shutdown takes no lock.
            clearInvocations(lockService, firstBuildService);
            job.doJob(null);
            verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any());
            verify(firstBuildService, never()).run(any());
        }

        @Test
        @DisplayName("a disabled first build is not created at shutdown, and the scheduler still shuts down")
        void disabledJobIsNotCreated() throws Exception {
            opikConfig.getTopics().getFirstBuild().setEnabled(false);

            shutdown();

            verify(injector, never()).getInstance(TopicsFirstBuildJob.class);
            verify(scheduler).shutdown(false);
        }

        private void shutdown() {
            var event = mock(GuiceyLifecycleEvent.class);
            when(event.getType()).thenReturn(GuiceyLifecycle.ApplicationShutdown);
            listener.onEvent(event);
        }

        private boolean tryLeaderLock() {
            return Boolean.TRUE.equals(lockService.bestEffortLock(TopicsFirstBuildService.LEADER_LOCK, Mono.just(true),
                    Mono.empty(), Duration.ofSeconds(5), Duration.ZERO).block());
        }
    }
}
