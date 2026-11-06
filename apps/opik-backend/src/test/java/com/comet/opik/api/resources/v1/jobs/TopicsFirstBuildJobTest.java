package com.comet.opik.api.resources.v1.jobs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.topics.TopicsNightlyService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topics first-build job")
class TopicsFirstBuildJobTest {

    private final TopicsConfig config = TestConfigUtils.loadConfigTest().getTopics();
    private final TopicsNightlyService nightlyService = mock(TopicsNightlyService.class);
    private final LockService lockService = mock(LockService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TopicsFirstBuildJob.class);
    private TopicsFirstBuildJob job;

    @BeforeEach
    void setUp() {
        config.setEnabled(true);
        when(nightlyService.firstBuildLease()).thenReturn(Duration.ofMinutes(29));
        // This pod takes the leader lock: the lock service runs the action, here before doJob returns.
        when(lockService.bestEffortLock(eq(TopicsNightlyService.FIRST_BUILD_LOCK), any(), any(),
                eq(Duration.ofMinutes(29)), eq(Duration.ZERO))).thenAnswer(invocation -> {
                    invocation.<Mono<?>>getArgument(1).block();
                    return Mono.empty();
                });
        job = new TopicsFirstBuildJob(config, nightlyService, lockService);
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
        verify(nightlyService).runFirstBuilds(any());
        assertThat(heldUntil.get()).isEqualTo(Instant.EPOCH.plus(Duration.ofMinutes(10).minusSeconds(1)));

        now.set(Instant.EPOCH.plus(Duration.ofMinutes(5)));
        job.doJob(null);
        verify(nightlyService).runFirstBuilds(any());
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("lock 'topics:first_build:run'");
        });

        now.set(Instant.EPOCH.plus(Duration.ofMinutes(10)));
        job.doJob(null);
        verify(nightlyService, times(2)).runFirstBuilds(any());
    }
}
