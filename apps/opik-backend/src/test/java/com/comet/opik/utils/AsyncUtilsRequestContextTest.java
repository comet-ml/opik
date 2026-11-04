package com.comet.opik.utils;

import com.comet.opik.infrastructure.auth.RequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

class AsyncUtilsRequestContextTest {

    private static final String ORGANIZATION_ID = UUID.randomUUID().toString();

    @Test
    void requestOrganizationReachesTheReactorContext() {
        var requestContext = request(ORGANIZATION_ID);

        StepVerifier.create(organizationAsTraceServiceReadsIt()
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext)))
                .expectNext(ORGANIZATION_ID)
                .verifyComplete();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void requestWithoutOrganizationLeavesTheKeyOut(String organizationId) {
        var requestContext = request(organizationId);

        StepVerifier.create(Mono.deferContextual(ctx -> Mono.just(ctx.hasKey(RequestContext.ORGANIZATION_ID)))
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext)))
                .expectNext(false)
                .verifyComplete();
    }

    private static Mono<String> organizationAsTraceServiceReadsIt() {
        return Mono.deferContextual(ctx -> Mono.justOrEmpty(
                ctx.<String>getOrDefault(RequestContext.ORGANIZATION_ID, null)));
    }

    private static RequestContext request(String organizationId) {
        return RequestContext.builder()
                .userName("user")
                .workspaceId(UUID.randomUUID().toString())
                .workspaceName("workspace")
                .organizationId(organizationId)
                .build();
    }
}
