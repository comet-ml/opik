package com.comet.opik.utils;

import com.comet.opik.infrastructure.auth.RequestContext;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OffloadTest {

    @Test
    void cpuRunsOnParallelAfterAClientCall() {
        var client = Schedulers.newSingle("offload-test-client");
        try {
            var cpuThreadName = Mono.just(1).publishOn(client)
                    .flatMap(__ -> Offload.cpu(() -> Thread.currentThread().getName()))
                    .block();

            assertThat(cpuThreadName).startsWith("parallel-");
        } finally {
            client.dispose();
        }
    }

    @Test
    void resumeOriginalReturnsToTheEntryPointPoolAndAddsNoHopWithoutOne() {
        var workers = Schedulers.newSingle("offload-test-workers");
        var client = Schedulers.newSingle("offload-test-client");
        try {
            var reply = Mono.just(1).publishOn(client);

            var resumedThreadName = reply.transform(Offload::resumeOriginal)
                    .map(__ -> Thread.currentThread().getName())
                    .contextWrite(context -> Offload.originalOn(context, workers))
                    .block();
            var resumedItemPoolNames = Flux.just(1, 2).publishOn(client)
                    .transform(Offload::resumeOriginal)
                    .map(__ -> Thread.currentThread().getName().replaceAll("-\\d+$", ""))
                    .distinct()
                    .collectList()
                    .contextWrite(context -> Offload.originalOn(context, workers))
                    .block();
            var cpuThreadName = Mono.just(1)
                    .flatMap(__ -> Offload.cpu(() -> Thread.currentThread().getName()))
                    .contextWrite(context -> Offload.originalOn(context, workers))
                    .block();
            var unsetThreadName = reply.transform(Offload::resumeOriginal)
                    .map(__ -> Thread.currentThread().getName())
                    .block();

            assertThat(resumedThreadName).startsWith("offload-test-workers-");
            assertThat(resumedItemPoolNames).containsExactly("offload-test-workers");
            assertThat(unsetThreadName).startsWith("offload-test-client-");
            assertThat(cpuThreadName).startsWith("parallel-");
        } finally {
            workers.dispose();
            client.dispose();
        }
    }

    @Test
    void resumeOriginalReturnsToARequestVirtualThreadOnAnApiPath() {
        var client = Schedulers.newSingle("offload-test-client");
        try {
            var requestContext = RequestContext.builder()
                    .userName(RandomStringUtils.secure().nextAlphanumeric(10))
                    .workspaceId(RandomStringUtils.secure().nextAlphanumeric(10))
                    .workspaceName(RandomStringUtils.secure().nextAlphanumeric(10))
                    .build();

            var resumedThread = Mono.just(1).publishOn(client)
                    .transform(Offload::resumeOriginal)
                    .map(__ -> Thread.currentThread())
                    .contextWrite(context -> AsyncUtils.setRequestContext(context, requestContext))
                    .block();

            assertThat(resumedThread.isVirtual()).isTrue();
            assertThat(resumedThread.getName()).startsWith("request-vt-");
        } finally {
            client.dispose();
        }
    }

    @Test
    void blockingRunsTheCallAndBlockingEachRunsEveryItemOnBoundedElastic() {
        var client = Schedulers.newSingle("offload-test-client");
        try {
            var callThreadName = Mono.just(1).publishOn(client)
                    .flatMap(__ -> Offload.blocking(() -> Thread.currentThread().getName()))
                    .block();
            var runnableThreadName = new AtomicReference<String>();
            Mono.just(1).publishOn(client)
                    .flatMap(__ -> Offload.blocking(() -> runnableThreadName.set(Thread.currentThread().getName())))
                    .block();
            var itemPoolNames = Offload.blockingEach(Flux.just(1, 2, 3).publishOn(client))
                    .map(__ -> Thread.currentThread().getName().replaceAll("-\\d+$", ""))
                    .distinct()
                    .collectList()
                    .block();

            assertThat(callThreadName).startsWith("boundedElastic-");
            assertThat(runnableThreadName.get()).startsWith("boundedElastic-");
            assertThat(itemPoolNames).containsExactly("boundedElastic");
        } finally {
            client.dispose();
        }
    }
}
