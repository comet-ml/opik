package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.RunnerService;
import com.comet.opik.infrastructure.RunnerConfig;
import com.comet.opik.infrastructure.lock.LockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunnerReaperJobTest {

    @Mock
    private RunnerService runnerService;

    @Mock
    private LockService lockService;

    @Mock
    private RunnerConfig runnerConfig;

    private RunnerReaperJob reaperJob;

    @BeforeEach
    void setUp() {
        reaperJob = new RunnerReaperJob(runnerService, lockService, runnerConfig);

        lenient().when(runnerConfig.getReaperLockDurationSeconds()).thenReturn(55);
        lenient().when(runnerConfig.getReaperLockWaitSeconds()).thenReturn(5);

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

        verify(lockService).bestEffortLock(any(Lock.class), any(Mono.class), any(Mono.class), any(Duration.class),
                any(Duration.class));
        verify(runnerService).reapDeadRunners();
    }

    @Test
    void usesConfigForLockDurations() {
        when(runnerConfig.isEnabled()).thenReturn(true);
        when(runnerConfig.getReaperLockDurationSeconds()).thenReturn(120);
        when(runnerConfig.getReaperLockWaitSeconds()).thenReturn(10);

        reaperJob.doJob(null);

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        ArgumentCaptor<Duration> waitCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(lockService).bestEffortLock(any(Lock.class), any(Mono.class), any(Mono.class),
                durationCaptor.capture(), waitCaptor.capture());

        assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofSeconds(120));
        assertThat(waitCaptor.getValue()).isEqualTo(Duration.ofSeconds(10));
    }
}
