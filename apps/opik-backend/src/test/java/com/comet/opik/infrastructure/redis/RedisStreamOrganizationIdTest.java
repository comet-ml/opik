package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.api.events.RedisSubscriberMessage;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
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
                Arguments.of(TraceThreadToScoreLlmAsJudge.class, TraceThreadToScoreLlmAsJudge.builder()
                        .threadIds(List.of("thread-1"))
                        .ruleId(UUID.randomUUID())
                        .projectId(UUID.randomUUID())
                        .code(PODAM.manufacturePojo(TraceThreadLlmAsJudgeCode.class))
                        .workspaceId("workspace")
                        .userName("user")
                        .organizationId(ORGANIZATION_ID)
                        .build()),
                Arguments.of(ExperimentItemToProcess.class, ExperimentItemToProcess.builder()
                        .batchId(UUID.randomUUID())
                        .prompt(ExperimentExecutionRequest.PromptVariant.builder()
                                .model("gpt-5-mini")
                                .messages(List.of(ExperimentExecutionRequest.PromptVariant.Message.builder()
                                        .role("user")
                                        .content(new TextNode("Hello"))
                                        .build()))
                                .build())
                        .datasetItemId(UUID.randomUUID())
                        .experimentId(UUID.randomUUID())
                        .datasetId(UUID.randomUUID())
                        .projectName("project")
                        .workspaceId("workspace")
                        .userName("user")
                        .allExperimentIds(List.of(UUID.randomUUID()))
                        .testSuite(false)
                        .organizationId(ORGANIZATION_ID)
                        .build()));
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
