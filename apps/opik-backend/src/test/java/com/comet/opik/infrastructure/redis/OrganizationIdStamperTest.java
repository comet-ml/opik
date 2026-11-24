package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.api.events.SpanToScoreUserDefinedMetricPython;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
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
        var message = message(carried);

        assertThat(OrganizationIdStamper.stamp(message, organizationId))
                .isEqualTo(message.toBuilder().organizationId(organizationId).build());
    }

    @Test
    @DisplayName("Keeps the organization a message carries")
    void keepsTheMessagesOwnOrganization() {
        var message = message(UUID.randomUUID().toString());

        assertThat(OrganizationIdStamper.stamp(message, organizationId)).isEqualTo(message);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("Leaves a message as it is when no organization is known")
    void leavesTheMessageWhenNoneIsKnown(String organizationId) {
        var message = message(null);

        assertThat(OrganizationIdStamper.stamp(message, organizationId)).isEqualTo(message);
    }

    @Test
    @DisplayName("Passes a message that isn't a stream message through")
    void passesOtherMessagesThrough() {
        var message = UUID.randomUUID().toString();

        assertThat(OrganizationIdStamper.stamp(message, organizationId)).isEqualTo(message);
    }

    @Test
    @DisplayName("Leaves messages that can't carry an organization as they are")
    void leavesMessagesThatCannotCarryOne() {
        List<Object> messages = List.of(
                PODAM_FACTORY.manufacturePojo(SpanToScoreLlmAsJudge.class),
                PODAM_FACTORY.manufacturePojo(SpanToScoreUserDefinedMetricPython.class),
                PODAM_FACTORY.manufacturePojo(TraceToScoreUserDefinedMetricPython.class),
                PODAM_FACTORY.manufacturePojo(TraceThreadToScoreUserDefinedMetricPython.class));

        assertThat(messages.stream().map(message -> OrganizationIdStamper.stamp(message, organizationId)).toList())
                .containsExactlyElementsOf(messages);
    }

    private static TraceThreadToScoreLlmAsJudge message(String organizationId) {
        return PODAM_FACTORY.manufacturePojo(TraceThreadToScoreLlmAsJudge.class).toBuilder()
                .organizationId(organizationId)
                .build();
    }
}
