package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.infrastructure.ProjectMigrationJobConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Abstract Project Migration Job Test")
@ExtendWith(MockitoExtension.class)
class AbstractProjectMigrationJobTest {

    private static final Duration JOB_TIMEOUT = Duration.seconds(1);
    private static final Duration LOCK_TIMEOUT = Duration.milliseconds(500);
    private static final Duration LOCK_WAIT_TIME = Duration.milliseconds(100);

    @Mock
    private LockService lockService;

    private AtomicBoolean enabled;
    private AtomicInteger cycleCalls;
    private TestProjectMigrationJob job;

    @BeforeEach
    void setUp() {
        enabled = new AtomicBoolean(true);
        cycleCalls = new AtomicInteger(0);
        job = new TestProjectMigrationJob(lockService, enabled, cycleCalls);
    }

    @Test
    @DisplayName("Disabled job short-circuits before acquiring the lock or invoking the cycle")
    void doJobWhenDisabledSkipsLockAndCycle() {
        // Given
        enabled.set(false);

        // When
        job.doJob(null);

        // Then
        verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any(), anyBoolean());
        assertThat(cycleCalls.get()).isZero();
    }

    @Test
    @DisplayName("Lock-skipped path: bestEffortLock invokes the no-lock branch and runMigrationCycle is never called")
    void doJobWhenLockUnavailableRunsNoLockBranch() {
        // Given a lock service that always picks the no-lock branch
        var noLockBranchInvocations = new AtomicInteger(0);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Mono<Void>> noLockMonoCaptor = ArgumentCaptor.forClass(Mono.class);
        when(lockService.bestEffortLock(any(), any(), noLockMonoCaptor.capture(),
                eq(LOCK_TIMEOUT.toJavaDuration()), eq(LOCK_WAIT_TIME.toJavaDuration()), eq(false)))
                .thenAnswer(invocation -> {
                    Mono<Void> noLock = invocation.getArgument(2);
                    return noLock.doOnSubscribe(s -> noLockBranchInvocations.incrementAndGet());
                });

        // When
        job.doJob(null);
        // Drain async work — the job subscribes on boundedElastic
        sleepUntil(() -> noLockBranchInvocations.get() > 0, JOB_TIMEOUT.toJavaDuration());

        // Then
        verify(lockService, times(1)).bestEffortLock(any(), any(), any(), any(), any(), anyBoolean());
        assertThat(cycleCalls.get()).isZero();
        assertThat(noLockBranchInvocations.get()).isOne();
    }

    @Test
    @DisplayName("Interrupt before processing: cycle is not invoked and the lock is never taken")
    void interruptBeforeProcessingSkipsCycle() {
        // When — interrupt first, then call doJob. The interrupted-before-execution guard returns
        // early without subscribing, so the lock is never requested and the cycle stays at 0.
        job.interrupt();
        job.doJob(null);

        // Then
        verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any(), anyBoolean());
        assertThat(cycleCalls.get()).isZero();
    }

    private static void sleepUntil(java.util.function.BooleanSupplier condition, java.time.Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Concrete subclass that wires the abstract hooks to controllable test state. */
    private static final class TestProjectMigrationJob extends AbstractProjectMigrationJob {

        private final ProjectMigrationJobConfig config;
        private final AtomicInteger cycleCalls;

        TestProjectMigrationJob(LockService lockService, AtomicBoolean enabled, AtomicInteger cycleCalls) {
            super(lockService);
            this.config = new TestProjectMigrationJobConfig(enabled);
            this.cycleCalls = cycleCalls;
        }

        @Override
        protected String entityLabel() {
            return "Test entity";
        }

        @Override
        protected String metricNamespace() {
            return "opik.test.abstract_project_migration";
        }

        @Override
        protected ProjectMigrationJobConfig config() {
            return config;
        }

        @Override
        protected Mono<Void> runMigrationCycle() {
            return Mono.fromRunnable(cycleCalls::incrementAndGet);
        }
    }

    private record TestProjectMigrationJobConfig(AtomicBoolean enabledRef) implements ProjectMigrationJobConfig {

        @Override
        public boolean enabled() {
            return enabledRef.get();
        }

        @Override
        public Duration lockTimeout() {
            return LOCK_TIMEOUT;
        }

        @Override
        public Duration lockWaitTime() {
            return LOCK_WAIT_TIME;
        }

        @Override
        public Duration jobTimeout() {
            return JOB_TIMEOUT;
        }
    }
}
