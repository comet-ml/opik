package com.comet.opik.utils;

import com.comet.opik.infrastructure.auth.RequestContext;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

class OffloadTest {

    @Test
    void cpuRunsOnParallelUnlessTheEntryPointSetsAPool() {
        var workers = Schedulers.newSingle("offload-test-workers");
        try {
            var defaultThread = Offload.cpu(() -> Thread.currentThread().getName()).block();
            var routedThread = Mono.just(1)
                    .flatMap(__ -> Offload.cpu(() -> Thread.currentThread().getName()))
                    .contextWrite(context -> Offload.cpuOn(context, workers))
                    .block();

            assertThat(defaultThread).startsWith("parallel-");
            assertThat(routedThread).startsWith("offload-test-workers-");
        } finally {
            workers.dispose();
        }
    }

    @Test
    void resumeOriginalReturnsToTheEntryPointPoolAndAddsNoHopWithoutOne() {
        var workers = Schedulers.newSingle("offload-test-workers");
        var client = Schedulers.newSingle("offload-test-client");
        try {
            var reply = Mono.just(1).publishOn(client);

            var resumedThread = reply.transform(Offload::resumeOriginal)
                    .map(__ -> Thread.currentThread().getName())
                    .contextWrite(context -> Offload.originalOn(context, workers))
                    .block();
            var resumedItemThreads = Flux.just(1, 2).publishOn(client)
                    .transform(Offload::resumeOriginal)
                    .map(__ -> Thread.currentThread().getName().replaceAll("-\\d+$", ""))
                    .distinct()
                    .collectList()
                    .contextWrite(context -> Offload.originalOn(context, workers))
                    .block();
            var cpuThread = Mono.just(1)
                    .flatMap(__ -> Offload.cpu(() -> Thread.currentThread().getName()))
                    .contextWrite(context -> Offload.originalOn(context, workers))
                    .block();
            var unsetThread = reply.transform(Offload::resumeOriginal)
                    .map(__ -> Thread.currentThread().getName())
                    .block();

            assertThat(resumedThread).startsWith("offload-test-workers-");
            assertThat(resumedItemThreads).containsExactly("offload-test-workers");
            assertThat(unsetThread).startsWith("offload-test-client-");
            assertThat(cpuThread).startsWith("parallel-");
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

            var resumed = Mono.just(1).publishOn(client)
                    .transform(Offload::resumeOriginal)
                    .map(__ -> Thread.currentThread())
                    .contextWrite(context -> AsyncUtils.setRequestContext(context, requestContext))
                    .block();

            assertThat(resumed.isVirtual()).isTrue();
            assertThat(resumed.getName()).startsWith("request-vt-");
        } finally {
            client.dispose();
        }
    }
}
