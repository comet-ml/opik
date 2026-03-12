package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.JobTimeoutConfig;
import com.comet.opik.infrastructure.TraceThreadConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.podam.PodamFactoryUtils;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddParams;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceThreadsClosingJobStreamAddParamsTest {

    private static final int STREAM_MAX_LEN = 10000;
    private static final int STREAM_TRIM_LIMIT = 100;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private final TraceThreadConfig traceThreadConfig = TraceThreadConfig.builder()
            .streamName("test-trace-thread-stream-%s".formatted(
                    RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase()))
            .streamMaxLen(STREAM_MAX_LEN)
            .streamTrimLimit(STREAM_TRIM_LIMIT)
            .timeoutToMarkThreadAsInactive(Duration.seconds(30))
            .closeTraceThreadJobLockTime(Duration.seconds(4))
            .closeTraceThreadJobLockWaitTime(Duration.milliseconds(300))
            .build();

    private final JobTimeoutConfig jobTimeoutConfig = JobTimeoutConfig.builder()
            .traceThreadsClosingJobTimeout(30)
            .build();

    @Mock
    private TraceThreadService traceThreadService;

    @Mock
    private LockService lockService;

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private RStreamReactive<Object, Object> stream;

    @Mock
    private JobExecutionContext jobExecutionContext;

    @Test
    void shouldUseConfiguredStreamAddParams() {
        var message = podamFactory.manufacturePojo(ProjectWithPendingClosureTraceThreads.class);
        when(redisClient.getStream(anyString(), any())).thenReturn(stream);
        when(stream.add(any())).thenReturn(Mono.just(new StreamMessageId(System.currentTimeMillis(), 0)));
        when(traceThreadService.getProjectsWithPendingClosureThreads(any(), any(), anyInt()))
                .thenReturn(Flux.just(message));
        when(traceThreadService.addToPendingQueue(any())).thenReturn(Mono.just(true));
        when(lockService.bestEffortLock(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.<Mono<?>>getArgument(1));

        var job = new TraceThreadsClosingJob(
                traceThreadService, lockService, traceThreadConfig, redisClient, jobTimeoutConfig);
        job.doJob(jobExecutionContext);

        await().untilAsserted(() -> {
            ArgumentCaptor<StreamAddParams<Object, Object>> captor = ArgumentCaptor.forClass(StreamAddParams.class);
            verify(stream, atLeastOnce()).add(captor.capture());
            var streamAddParams = captor.getValue();
            assertThat(streamAddParams.getMaxLen()).isEqualTo(STREAM_MAX_LEN);
            assertThat(streamAddParams.getLimit()).isEqualTo(STREAM_TRIM_LIMIT);
            assertThat(streamAddParams.isTrimStrict()).isFalse();
        });
    }
}
