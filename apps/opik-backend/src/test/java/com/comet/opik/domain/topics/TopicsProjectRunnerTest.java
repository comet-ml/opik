package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicsProjectRunnerTest {

    @ParameterizedTest
    @CsvSource({"true, 1", "false, 0"})
    @DisplayName("a lock Redis answers after the wait gives a 503, and is released only when the late reply took it")
    void lateLockReply(boolean taken, int unlocks) {
        var lock = TopicsFirstBuildService.BY_HAND_LOCK;
        var reply = Sinks.<Boolean>one();
        var lockService = mock(LockService.class);
        when(lockService.lockUsingToken(eq(lock), any())).thenReturn(reply.asMono());
        when(lockService.unlockUsingToken(lock)).thenReturn(Mono.empty());
        var runner = new TopicsProjectRunner(TestConfigUtils.loadConfigTest().getTopics(),
                mock(TransactionTemplate.class), mock(TopicsRebuildService.class), lockService);
        runner.lockWait = Duration.ofMillis(50);

        assertThatThrownBy(() -> runner.lockByHand(lock)).isInstanceOf(ServiceUnavailableException.class);
        verify(lockService, times(0)).unlockUsingToken(any());

        reply.tryEmitValue(taken).orThrow();

        verify(lockService, times(unlocks)).unlockUsingToken(lock);
    }
}
