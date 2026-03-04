package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.LocalRunnerService;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalRunnerReaperJobTest {

    @Mock
    private LocalRunnerService runnerService;

    @Mock
    private LockService lockService;

    @Mock
    private LocalRunnerConfig runnerConfig;

    private LocalRunnerReaperJob reaperJob;

    @BeforeEach
    void setUp() {
        reaperJob = new LocalRunnerReaperJob(runnerService, lockService, runnerConfig);

        lenient().when(runnerConfig.getReaperLockDuration()).thenReturn(Duration.seconds(55));
        lenient().when(runnerConfig.getReaperLockWait()).thenReturn(Duration.seconds(5));

        lenient().when(lockService.bestEffortLock(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void skipsWhenDisabled() {
        when(runnerConfig.isEnabled()).thenReturn(false);

        reaperJob.doJob(null);

        verify(runnerService, never()).reapDeadRunners();
        verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any());
    }

    @Test
    void callsReapWhenEnabled() {
        when(runnerConfig.isEnabled()).thenReturn(true);

        reaperJob.doJob(null);

        verify(lockService).bestEffortLock(any(Lock.class), any(Mono.class), any(Mono.class),
                any(java.time.Duration.class), any(java.time.Duration.class));
        verify(runnerService).reapDeadRunners();
    }

    @Test
    void usesConfigForLockDurations() {
        when(runnerConfig.isEnabled()).thenReturn(true);
        when(runnerConfig.getReaperLockDuration()).thenReturn(Duration.seconds(120));
        when(runnerConfig.getReaperLockWait()).thenReturn(Duration.seconds(10));

        reaperJob.doJob(null);

        ArgumentCaptor<java.time.Duration> durationCaptor = ArgumentCaptor.forClass(java.time.Duration.class);
        ArgumentCaptor<java.time.Duration> waitCaptor = ArgumentCaptor.forClass(java.time.Duration.class);

        verify(lockService).bestEffortLock(any(Lock.class), any(Mono.class), any(Mono.class),
                durationCaptor.capture(), waitCaptor.capture());

        assertThat(durationCaptor.getValue()).isEqualTo(java.time.Duration.ofSeconds(120));
        assertThat(waitCaptor.getValue()).isEqualTo(java.time.Duration.ofSeconds(10));
    }

    @Test
    void skipsWhenInterrupted() {
        reaperJob.interrupt();
        reaperJob.doJob(null);

        verify(runnerService, never()).reapDeadRunners();
        verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any());
    }
}
