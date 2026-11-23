package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.api.events.RedisSubscriberMessage;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The organization ID on stream messages, across producer versions")
class RedisStreamOrganizationIdTest {

    private static final String ORGANIZATION_ID = "organization-1";
    private static final PodamFactory PODAM = PodamFactoryUtils.newPodamFactory();

    private final ObjectMapper mapper = RedisStreamCodec.buildStreamMapper();

    static Stream<Arguments> messages() {
        return Stream.of(
                Arguments.of(TraceToScoreLlmAsJudge.class, PODAM.manufacturePojo(TraceToScoreLlmAsJudge.class)
                        .toBuilder().organizationId(ORGANIZATION_ID).build()),
                Arguments.of(TraceThreadToScoreLlmAsJudge.class,
                        PODAM.manufacturePojo(TraceThreadToScoreLlmAsJudge.class)
                                .toBuilder().organizationId(ORGANIZATION_ID).build()),
                Arguments.of(ExperimentItemToProcess.class, PODAM.manufacturePojo(ExperimentItemToProcess.class)
                        .toBuilder().organizationId(ORGANIZATION_ID).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    @DisplayName("A message keeps its organization through the stream mapper")
    void organizationSurvivesTheRoundTrip(Class<? extends RedisSubscriberMessage> type,
            RedisSubscriberMessage message) throws Exception {
        var decoded = mapper.readValue(mapper.writeValueAsString(message), type);

        assertThat(decoded.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(decoded).isEqualTo(message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    @DisplayName("A message from a producer that predates the field decodes with no organization")
    void messageWithoutTheFieldDecodesWithNoOrganization(Class<? extends RedisSubscriberMessage> type,
            RedisSubscriberMessage message) throws Exception {
        var json = (ObjectNode) mapper.readTree(mapper.writeValueAsString(message));
        var field = json.has("organization_id") ? "organization_id" : "organizationId";
        assertThat(json.path(field).asText()).isEqualTo(ORGANIZATION_ID);
        json.remove(field);

        var decoded = mapper.readValue(mapper.writeValueAsString(json), type);

        assertThat(decoded.organizationId()).isNull();
        assertThat(decoded.workspaceId()).isEqualTo(message.workspaceId());
    }
}
