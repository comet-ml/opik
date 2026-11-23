package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.infrastructure.auth.FixedOrganizationResolver;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrganizationIdStamper: stamping the organization onto stream messages")
class OrganizationIdStamperTest {

    private static final PodamFactory PODAM_FACTORY = PodamFactoryUtils.newPodamFactory();

    private final String organizationId = UUID.randomUUID().toString();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("Stamps the organization onto a message that has none")
    void stampsAMessageWithoutOne(String carried) {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var message = message(carried);

        var stamped = new OrganizationIdStamper(resolver).stamp(message).block();

        assertThat(stamped).isEqualTo(message.toBuilder().organizationId(organizationId).build());
    }

    @Test
    @DisplayName("Keeps the organization a message carries, without a lookup")
    void keepsTheMessagesOwnOrganization() {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var message = message(UUID.randomUUID().toString());

        var stamped = new OrganizationIdStamper(resolver).stamp(message).block();

        assertThat(stamped).isEqualTo(message);
        assertThat(resolver.lookups()).isZero();
    }

    @Test
    @DisplayName("Leaves a message as it is when no organization is known")
    void leavesTheMessageWhenNoneIsKnown() {
        var message = message(null);

        var stamped = new OrganizationIdStamper(FixedOrganizationResolver.none()).stamp(message).block();

        assertThat(stamped).isEqualTo(message);
    }

    @Test
    @DisplayName("Passes a message that isn't a stream message through, without a lookup")
    void passesOtherMessagesThrough() {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var message = UUID.randomUUID().toString();

        var stamped = new OrganizationIdStamper(resolver).stamp(message).block();

        assertThat(stamped).isEqualTo(message);
        assertThat(resolver.lookups()).isZero();
    }

    @Test
    @DisplayName("Looks the organization up once for all the messages it stamps")
    void looksUpOnce() {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var messages = List.of(message(null), message(null), message(null));
        var stamper = new OrganizationIdStamper(resolver);

        var stamped = Flux.fromIterable(messages).concatMap(stamper::stamp).collectList().block();

        assertThat(stamped).containsExactlyElementsOf(messages.stream()
                .map(message -> message.toBuilder().organizationId(organizationId).build())
                .toList());
        assertThat(resolver.lookups()).isOne();
    }

    private static TraceThreadToScoreLlmAsJudge message(String organizationId) {
        return PODAM_FACTORY.manufacturePojo(TraceThreadToScoreLlmAsJudge.class).toBuilder()
                .organizationId(organizationId)
                .build();
    }
}
