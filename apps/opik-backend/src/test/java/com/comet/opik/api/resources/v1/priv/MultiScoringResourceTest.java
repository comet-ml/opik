package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreGroup;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.SpanType;
import com.comet.opik.testutils.PodamFactoryUtils;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.vyarus.dropwizard.guice.test.jupiter.TestGuiceyApp;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ResourcesTestUtils.API_KEY;
import static com.comet.opik.api.resources.utils.ResourcesTestUtils.TEST_WORKSPACE;
import static com.comet.opik.testutils.PodamFactoryUtils.podamFactory;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(DropwizardExtensionsSupport.class)
@TestGuiceyApp(value = TestApplication.class, config = "config/test-config.yml")
class MultiScoringResourceTest {

    private static final ResourceExtension resources = ResourceExtension.builder()
            .addResource(new SpansResource(
                    null, null, null, null, null, null, null, null, null))
            .addResource(new TracesResource(
                    null, null, null, null, null, null, null, null, null, null, null, null))
            .build();

    private SpanResourceClient spanResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeEach
    void setUp() {
        spanResourceClient = new SpanResourceClient(resources.client(), resources.baseUri());
        traceResourceClient = new TraceResourceClient(resources.client(), resources.baseUri());
    }

    @Test
    void testMultiScoringForSpans() {
        // Create a span first
        Span span = podamFactory.manufacturePojo(Span.class);
        span = span.toBuilder().type(SpanType.LLM).build();
        
        Response createResponse = spanResourceClient.create(span, API_KEY, TEST_WORKSPACE);
        assertThat(createResponse.getStatus()).isEqualTo(201);
        
        UUID spanId = span.id();

        // Add multiple scores for the same metric
        FeedbackScore score1 = FeedbackScore.builder()
                .name("quality")
                .value(new BigDecimal("8.5"))
                .source(ScoreSource.UI)
                .reason("Good response")
                .build();

        FeedbackScore score2 = FeedbackScore.builder()
                .name("quality")
                .value(new BigDecimal("7.0"))
                .source(ScoreSource.SDK)
                .reason("Automated evaluation")
                .build();

        FeedbackScore score3 = FeedbackScore.builder()
                .name("quality")
                .value(new BigDecimal("9.0"))
                .source(ScoreSource.UI)
                .reason("Excellent response")
                .build();

        // Add scores one by one
        spanResourceClient.feedbackScore(spanId, score1, TEST_WORKSPACE, API_KEY);
        spanResourceClient.feedbackScore(spanId, score2, TEST_WORKSPACE, API_KEY);
        spanResourceClient.feedbackScore(spanId, score3, TEST_WORKSPACE, API_KEY);

        // Get the score groups
        Response groupsResponse = spanResourceClient.getFeedbackScoreGroups(spanId, API_KEY, TEST_WORKSPACE);
        assertThat(groupsResponse.getStatus()).isEqualTo(200);

        List<FeedbackScoreGroup> groups = groupsResponse.readEntity(List.class);
        assertThat(groups).hasSize(1);
        
        // Verify the group has the correct statistics
        Map<String, Object> group = (Map<String, Object>) groups.get(0);
        assertThat(group.get("name")).isEqualTo("quality");
        assertThat(group.get("scoreCount")).isEqualTo(3);
        
        // Verify average calculation (8.5 + 7.0 + 9.0) / 3 = 8.17
        BigDecimal averageValue = new BigDecimal(group.get("averageValue").toString());
        assertThat(averageValue).isCloseTo(new BigDecimal("8.17"), new BigDecimal("0.01"));
    }

    @Test
    void testMultiScoringForTraces() {
        // Create a trace first
        Trace trace = podamFactory.manufacturePojo(Trace.class);
        
        Response createResponse = traceResourceClient.create(trace, API_KEY, TEST_WORKSPACE);
        assertThat(createResponse.getStatus()).isEqualTo(201);
        
        UUID traceId = trace.id();

        // Add multiple scores for different metrics
        FeedbackScore score1 = FeedbackScore.builder()
                .name("accuracy")
                .value(new BigDecimal("9.5"))
                .source(ScoreSource.UI)
                .reason("Very accurate")
                .build();

        FeedbackScore score2 = FeedbackScore.builder()
                .name("accuracy")
                .value(new BigDecimal("8.8"))
                .source(ScoreSource.SDK)
                .reason("Automated check")
                .build();

        FeedbackScore score3 = FeedbackScore.builder()
                .name("relevance")
                .value(new BigDecimal("7.5"))
                .source(ScoreSource.UI)
                .reason("Somewhat relevant")
                .build();

        // Add scores
        traceResourceClient.feedbackScore(traceId, score1, TEST_WORKSPACE, API_KEY);
        traceResourceClient.feedbackScore(traceId, score2, TEST_WORKSPACE, API_KEY);
        traceResourceClient.feedbackScore(traceId, score3, TEST_WORKSPACE, API_KEY);

        // Get the score groups
        Response groupsResponse = traceResourceClient.getFeedbackScoreGroups(traceId, API_KEY, TEST_WORKSPACE);
        assertThat(groupsResponse.getStatus()).isEqualTo(200);

        List<FeedbackScoreGroup> groups = groupsResponse.readEntity(List.class);
        assertThat(groups).hasSize(2); // accuracy and relevance
        
        // Find the accuracy group
        Map<String, Object> accuracyGroup = groups.stream()
                .map(g -> (Map<String, Object>) g)
                .filter(g -> "accuracy".equals(g.get("name")))
                .findFirst()
                .orElseThrow();
        
        assertThat(accuracyGroup.get("scoreCount")).isEqualTo(2);
        
        // Verify average calculation (9.5 + 8.8) / 2 = 9.15
        BigDecimal averageValue = new BigDecimal(accuracyGroup.get("averageValue").toString());
        assertThat(averageValue).isCloseTo(new BigDecimal("9.15"), new BigDecimal("0.01"));
    }

    @Test
    void testDeleteSpecificScore() {
        // Create a span
        Span span = podamFactory.manufacturePojo(Span.class);
        span = span.toBuilder().type(SpanType.LLM).build();
        
        Response createResponse = spanResourceClient.create(span, API_KEY, TEST_WORKSPACE);
        assertThat(createResponse.getStatus()).isEqualTo(201);
        
        UUID spanId = span.id();

        // Add two scores
        FeedbackScore score1 = FeedbackScore.builder()
                .name("quality")
                .value(new BigDecimal("8.0"))
                .source(ScoreSource.UI)
                .build();

        FeedbackScore score2 = FeedbackScore.builder()
                .name("quality")
                .value(new BigDecimal("9.0"))
                .source(ScoreSource.UI)
                .build();

        spanResourceClient.feedbackScore(spanId, score1, TEST_WORKSPACE, API_KEY);
        spanResourceClient.feedbackScore(spanId, score2, TEST_WORKSPACE, API_KEY);

        // Get score groups to find the score IDs
        Response groupsResponse = spanResourceClient.getFeedbackScoreGroups(spanId, API_KEY, TEST_WORKSPACE);
        assertThat(groupsResponse.getStatus()).isEqualTo(200);

        List<FeedbackScoreGroup> groups = groupsResponse.readEntity(List.class);
        assertThat(groups).hasSize(1);
        
        Map<String, Object> group = (Map<String, Object>) groups.get(0);
        List<Map<String, Object>> scores = (List<Map<String, Object>>) group.get("scores");
        assertThat(scores).hasSize(2);

        // Delete one specific score
        UUID scoreId = UUID.fromString(scores.get(0).get("scoreId").toString());
        Response deleteResponse = spanResourceClient.deleteFeedbackScoreById(spanId, scoreId, API_KEY, TEST_WORKSPACE);
        assertThat(deleteResponse.getStatus()).isEqualTo(204);

        // Verify only one score remains
        Response remainingGroupsResponse = spanResourceClient.getFeedbackScoreGroups(spanId, API_KEY, TEST_WORKSPACE);
        assertThat(remainingGroupsResponse.getStatus()).isEqualTo(200);

        List<FeedbackScoreGroup> remainingGroups = remainingGroupsResponse.readEntity(List.class);
        assertThat(remainingGroups).hasSize(1);
        
        Map<String, Object> remainingGroup = (Map<String, Object>) remainingGroups.get(0);
        assertThat(remainingGroup.get("scoreCount")).isEqualTo(1);
    }
}