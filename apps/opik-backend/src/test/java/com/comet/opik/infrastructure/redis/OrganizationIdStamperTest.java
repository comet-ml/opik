package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.api.events.SpanToScoreUserDefinedMetricPython;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
import com.comet.opik.infrastructure.auth.FixedOrganizationResolver;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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

        var stamped = new OrganizationIdStamper(resolver.organizationId()).stamp(message).block();

        assertThat(stamped).isEqualTo(message.toBuilder().organizationId(organizationId).build());
    }

    @Test
    @DisplayName("Keeps the organization a message carries, without a lookup")
    void keepsTheMessagesOwnOrganization() {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var message = message(UUID.randomUUID().toString());

        var stamped = new OrganizationIdStamper(resolver.organizationId()).stamp(message).block();

        assertThat(stamped).isEqualTo(message);
        assertThat(resolver.lookups()).isZero();
    }

    @Test
    @DisplayName("Leaves a message as it is when no organization is known")
    void leavesTheMessageWhenNoneIsKnown() {
        var message = message(null);

        var stamped = new OrganizationIdStamper(FixedOrganizationResolver.none().organizationId()).stamp(message)
                .block();

        assertThat(stamped).isEqualTo(message);
    }

    @Test
    @DisplayName("Passes a message that isn't a stream message through, without a lookup")
    void passesOtherMessagesThrough() {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var message = UUID.randomUUID().toString();

        var stamped = new OrganizationIdStamper(resolver.organizationId()).stamp(message).block();

        assertThat(stamped).isEqualTo(message);
        assertThat(resolver.lookups()).isZero();
    }

    @Test
    @DisplayName("Skips the lookup for messages that can't carry an organization")
    void skipsTheLookupForMessagesThatCannotCarryOne() {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var messages = List.of(
                PODAM_FACTORY.manufacturePojo(SpanToScoreLlmAsJudge.class),
                PODAM_FACTORY.manufacturePojo(SpanToScoreUserDefinedMetricPython.class),
                PODAM_FACTORY.manufacturePojo(TraceToScoreUserDefinedMetricPython.class),
                PODAM_FACTORY.manufacturePojo(TraceThreadToScoreUserDefinedMetricPython.class));

        var stamped = new OrganizationIdStamper(resolver.organizationId()).stampAll(messages).block();

        assertThat(stamped).containsExactlyElementsOf(messages);
        assertThat(resolver.lookups()).isZero();
    }

    @Test
    @DisplayName("Looks the organization up once for all the messages it stamps, skipping the ones that can't carry it")
    void looksUpOnce() {
        var resolver = FixedOrganizationResolver.of(organizationId);
        var span = PODAM_FACTORY.manufacturePojo(SpanToScoreLlmAsJudge.class);
        var trace = PODAM_FACTORY.manufacturePojo(TraceToScoreLlmAsJudge.class).toBuilder()
                .organizationId(null)
                .build();
        var python = PODAM_FACTORY.manufacturePojo(TraceToScoreUserDefinedMetricPython.class);
        var threads = List.of(message(null), message(null));

        var stamped = new OrganizationIdStamper(resolver.organizationId())
                .stampAll(List.of(span, trace, python, threads.getFirst(), threads.getLast()))
                .block();

        assertThat(stamped).containsExactly(
                span,
                trace.toBuilder().organizationId(organizationId).build(),
                python,
                threads.getFirst().toBuilder().organizationId(organizationId).build(),
                threads.getLast().toBuilder().organizationId(organizationId).build());
        assertThat(resolver.lookups()).isOne();
    }

    private static TraceThreadToScoreLlmAsJudge message(String organizationId) {
        return PODAM_FACTORY.manufacturePojo(TraceThreadToScoreLlmAsJudge.class).toBuilder()
                .organizationId(organizationId)
                .build();
    }
}
