package com.comet.opik.domain.evaluators.python;

import com.comet.opik.api.evaluators.CommonMetric;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.PythonEvaluatorConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Common Metrics Registry Test")
class CommonMetricsRegistryTest {

    @Mock
    private Client client;

    @Mock
    private WebTarget webTarget;

    @Mock
    private Invocation.Builder builder;

    @Mock
    private AsyncInvoker asyncInvoker;

    private CommonMetricsRegistry registry;
    private OpikConfiguration config;

    @BeforeEach
    void setUp() {
        PythonEvaluatorConfig pythonEvaluatorConfig = new PythonEvaluatorConfig();
        pythonEvaluatorConfig.setUrl("http://localhost:8000");
        pythonEvaluatorConfig.setMaxRetryAttempts(4);
        pythonEvaluatorConfig.setMinRetryDelay(Duration.milliseconds(100));
        pythonEvaluatorConfig.setMaxRetryDelay(Duration.milliseconds(100));

        config = new OpikConfiguration();
        config.setPythonEvaluator(pythonEvaluatorConfig);
    }

    private void setupHttpCallChain() {
        when(client.target(anyString())).thenReturn(webTarget);
        when(webTarget.request()).thenReturn(builder);
        when(builder.async()).thenReturn(asyncInvoker);
    }

    private void setupRegistryWithMockedResponse(List<CommonMetric> metrics) {
        setupHttpCallChain();

        Response successResponse = createMockResponse(Status.OK,
                CommonMetric.CommonMetricList.builder().content(metrics).build());

        doAnswer(invocation -> {
            InvocationCallback<Response> callback = invocation.getArgument(0);
            callback.completed(successResponse);
            return null;
        }).when(asyncInvoker).get(any(InvocationCallback.class));

        registry = new CommonMetricsRegistry(new RetriableHttpClient(client), config);
    }

    private Response createMockResponse(Status status, Object entity) {
        Response mockResponse = mock(Response.class);

        lenient().when(mockResponse.getStatusInfo()).thenReturn(status);
        lenient().when(mockResponse.getStatus()).thenReturn(status.getStatusCode());
        lenient().when(mockResponse.hasEntity()).thenReturn(entity != null);
        lenient().when(mockResponse.bufferEntity()).thenReturn(entity != null);

        if (entity instanceof CommonMetric.CommonMetricList metricList) {
            lenient().when(mockResponse.readEntity(CommonMetric.CommonMetricList.class))
                    .thenReturn(metricList);
        } else if (entity instanceof String string) {
            lenient().when(mockResponse.readEntity(String.class)).thenReturn(string);
        }

        return mockResponse;
    }

    private List<CommonMetric> createSampleMetrics() {
        return List.of(
                CommonMetric.builder()
                        .id("equals")
                        .name("Equals")
                        .description("A metric that checks if an output string exactly matches a reference string.")
                        .scoreParameters(List.of(
                                CommonMetric.ScoreParameter.builder()
                                        .name("output")
                                        .type("str")
                                        .description("The output string to check.")
                                        .required(true)
                                        .mappable(true)
                                        .build(),
                                CommonMetric.ScoreParameter.builder()
                                        .name("reference")
                                        .type("str")
                                        .description("The reference string to compare against.")
                                        .required(true)
                                        .mappable(false)
                                        .build()))
                        .initParameters(List.of(
                                CommonMetric.InitParameter.builder()
                                        .name("case_sensitive")
                                        .type("bool")
                                        .description("Whether the comparison should be case-sensitive.")
                                        .defaultValue("False")
                                        .required(false)
                                        .build()))
                        .build(),
                CommonMetric.builder()
                        .id("contains")
                        .name("Contains")
                        .description("A metric that checks if a reference string is contained within an output string.")
                        .scoreParameters(List.of(
                                CommonMetric.ScoreParameter.builder()
                                        .name("output")
                                        .type("str")
                                        .description("The output string to check.")
                                        .required(true)
                                        .mappable(true)
                                        .build(),
                                CommonMetric.ScoreParameter.builder()
                                        .name("reference")
                                        .type("str")
                                        .description("The reference string to look for.")
                                        .required(false)
                                        .mappable(false)
                                        .build()))
                        .initParameters(List.of(
                                CommonMetric.InitParameter.builder()
                                        .name("case_sensitive")
                                        .type("bool")
                                        .description("Whether the comparison should be case-sensitive.")
                                        .defaultValue("False")
                                        .required(false)
                                        .build(),
                                CommonMetric.InitParameter.builder()
                                        .name("reference")
                                        .type("str")
                                        .description("Optional default reference string.")
                                        .defaultValue(null)
                                        .required(false)
                                        .build()))
                        .build(),
                CommonMetric.builder()
                        .id("levenshtein_ratio")
                        .name("LevenshteinRatio")
                        .description("A metric that calculates the Levenshtein ratio between two strings.")
                        .scoreParameters(List.of(
                                CommonMetric.ScoreParameter.builder()
                                        .name("output")
                                        .type("str")
                                        .description("The output string to compare.")
                                        .required(true)
                                        .mappable(true)
                                        .build(),
                                CommonMetric.ScoreParameter.builder()
                                        .name("reference")
                                        .type("str")
                                        .description("The reference string to compare against.")
                                        .required(true)
                                        .mappable(false)
                                        .build()))
                        .initParameters(List.of(
                                CommonMetric.InitParameter.builder()
                                        .name("case_sensitive")
                                        .type("bool")
                                        .description("Whether the comparison should be case-sensitive.")
                                        .defaultValue("False")
                                        .required(false)
                                        .build()))
                        .build(),
                CommonMetric.builder()
                        .id("regex_match")
                        .name("RegexMatch")
                        .description("A metric that checks if an output string matches a regular expression pattern.")
                        .scoreParameters(List.of(
                                CommonMetric.ScoreParameter.builder()
                                        .name("output")
                                        .type("str")
                                        .description("The output string to check against the regex pattern.")
                                        .required(true)
                                        .mappable(true)
                                        .build()))
                        .initParameters(List.of(
                                CommonMetric.InitParameter.builder()
                                        .name("regex")
                                        .type("str")
                                        .description("The regular expression pattern to match against.")
                                        .defaultValue(null)
                                        .required(true)
                                        .build()))
                        .build(),
                CommonMetric.builder()
                        .id("is_json")
                        .name("IsJson")
                        .description("A metric that checks if a given output string is valid JSON.")
                        .scoreParameters(List.of(
                                CommonMetric.ScoreParameter.builder()
                                        .name("output")
                                        .type("str")
                                        .description("The output string to check for JSON validity.")
                                        .required(true)
                                        .mappable(true)
                                        .build()))
                        .initParameters(List.of())
                        .build());
    }

    @Nested
    @DisplayName("lazy initialization")
    class LazyInitialization {

        @Test
        @DisplayName("should not fetch metrics until first access")
        void shouldNotFetchMetricsUntilFirstAccess() {
            // Given - just create the registry without setting up mocks
            registry = new CommonMetricsRegistry(new RetriableHttpClient(client), config);

            // Then - no HTTP call yet
            verify(client, never()).target(anyString());
        }

        @Test
        @DisplayName("should fetch metrics from Python backend on first getAll call")
        void shouldFetchMetricsFromPythonBackendOnFirstGetAllCall() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            registry.getAll();

            // Then
            verify(client).target("http://localhost:8000/v1/private/evaluators/common-metrics");
            verify(asyncInvoker).get(any(InvocationCallback.class));
        }

        @Test
        @DisplayName("should fetch metrics from Python backend on first findById call")
        void shouldFetchMetricsFromPythonBackendOnFirstFindByIdCall() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            registry.findById("equals");

            // Then
            verify(client).target("http://localhost:8000/v1/private/evaluators/common-metrics");
            verify(asyncInvoker).get(any(InvocationCallback.class));
        }

        @Test
        @DisplayName("should only fetch metrics once even with multiple calls")
        void shouldOnlyFetchMetricsOnceEvenWithMultipleCalls() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            registry.getAll();
            registry.getAll();
            registry.findById("equals");
            registry.findById("contains");

            // Then - HTTP call should happen only once
            verify(client, times(1)).target("http://localhost:8000/v1/private/evaluators/common-metrics");
            verify(asyncInvoker, times(1)).get(any(InvocationCallback.class));
        }

        @Test
        @DisplayName("should return empty list when Python backend is unavailable")
        void shouldReturnEmptyListWhenPythonBackendIsUnavailable() {
            // Given
            setupHttpCallChain();

            Response errorResponse = createMockResponse(Status.SERVICE_UNAVAILABLE, "Service unavailable");

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(0);
                callback.completed(errorResponse);
                return null;
            }).when(asyncInvoker).get(any(InvocationCallback.class));

            registry = new CommonMetricsRegistry(new RetriableHttpClient(client), config);

            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            assertThat(result.content()).isEmpty();
        }

        @Test
        @DisplayName("should retry on 503 errors and eventually succeed")
        void shouldRetryOn503ErrorsAndEventuallySucceed() {
            // Given
            setupHttpCallChain();

            List<CommonMetric> expectedMetrics = createSampleMetrics();
            Response errorResponse = createMockResponse(Status.SERVICE_UNAVAILABLE, null);
            Response successResponse = createMockResponse(Status.OK,
                    CommonMetric.CommonMetricList.builder().content(expectedMetrics).build());

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(0);
                callback.completed(errorResponse);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(0);
                callback.completed(successResponse);
                return null;
            }).when(asyncInvoker).get(any(InvocationCallback.class));

            registry = new CommonMetricsRegistry(new RetriableHttpClient(client), config);

            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            assertThat(result.content()).hasSize(expectedMetrics.size());
            verify(asyncInvoker, times(2)).get(any(InvocationCallback.class));
        }
    }

    @Nested
    @DisplayName("getAll")
    class GetAll {

        @Test
        @DisplayName("should return all fetched metrics")
        void shouldReturnAllFetchedMetrics() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            assertThat(result).isNotNull();
            assertThat(result.content()).isNotEmpty();
            assertThat(result.content()).hasSize(expectedMetrics.size());
        }

        @Test
        @DisplayName("should return metrics with correct structure")
        void shouldReturnMetricsWithCorrectStructure() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            for (CommonMetric metric : result.content()) {
                assertThat(metric.id()).isNotBlank();
                assertThat(metric.name()).isNotBlank();
                assertThat(metric.description()).isNotBlank();
                assertThat(metric.scoreParameters()).isNotNull();
                assertThat(metric.initParameters()).isNotNull();
            }
        }

        @Test
        @DisplayName("should include equals metric")
        void shouldIncludeEqualsMetric() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            CommonMetric equalsMetric = result.content().stream()
                    .filter(m -> m.id().equals("equals"))
                    .findFirst()
                    .orElse(null);

            assertThat(equalsMetric).isNotNull();
            assertThat(equalsMetric.name()).isEqualTo("Equals");
            assertThat(equalsMetric.scoreParameters()).hasSize(2);
            assertThat(equalsMetric.initParameters()).hasSize(1);
        }

        @Test
        @DisplayName("should include contains metric with init parameters")
        void shouldIncludeContainsMetricWithInitParameters() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            CommonMetric containsMetric = result.content().stream()
                    .filter(m -> m.id().equals("contains"))
                    .findFirst()
                    .orElse(null);

            assertThat(containsMetric).isNotNull();
            assertThat(containsMetric.name()).isEqualTo("Contains");
            assertThat(containsMetric.initParameters()).hasSize(2);

            // Check case_sensitive init parameter
            CommonMetric.InitParameter caseSensitiveParam = containsMetric.initParameters().stream()
                    .filter(p -> p.name().equals("case_sensitive"))
                    .findFirst()
                    .orElse(null);

            assertThat(caseSensitiveParam).isNotNull();
            assertThat(caseSensitiveParam.type()).isEqualTo("bool");
            assertThat(caseSensitiveParam.defaultValue()).isEqualTo("False");
            assertThat(caseSensitiveParam.required()).isFalse();
        }

        @Test
        @DisplayName("should include regex_match metric with required init parameter")
        void shouldIncludeRegexMatchMetricWithRequiredInitParameter() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            CommonMetric regexMetric = result.content().stream()
                    .filter(m -> m.id().equals("regex_match"))
                    .findFirst()
                    .orElse(null);

            assertThat(regexMetric).isNotNull();
            assertThat(regexMetric.name()).isEqualTo("RegexMatch");

            // Check regex init parameter is required
            CommonMetric.InitParameter regexParam = regexMetric.initParameters().stream()
                    .filter(p -> p.name().equals("regex"))
                    .findFirst()
                    .orElse(null);

            assertThat(regexParam).isNotNull();
            assertThat(regexParam.required()).isTrue();
        }

        @Test
        @DisplayName("should include is_json metric without init parameters")
        void shouldIncludeIsJsonMetricWithoutInitParameters() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            CommonMetric isJsonMetric = result.content().stream()
                    .filter(m -> m.id().equals("is_json"))
                    .findFirst()
                    .orElse(null);

            assertThat(isJsonMetric).isNotNull();
            assertThat(isJsonMetric.name()).isEqualTo("IsJson");
            assertThat(isJsonMetric.initParameters()).isEmpty();
            assertThat(isJsonMetric.scoreParameters()).hasSize(1);
        }

        @Test
        @DisplayName("should include mappable field in score parameters")
        void shouldIncludeMappableFieldInScoreParameters() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            CommonMetric.CommonMetricList result = registry.getAll();

            // Then
            CommonMetric equalsMetric = result.content().stream()
                    .filter(m -> m.id().equals("equals"))
                    .findFirst()
                    .orElse(null);

            assertThat(equalsMetric).isNotNull();

            // Output should be mappable
            CommonMetric.ScoreParameter outputParam = equalsMetric.scoreParameters().stream()
                    .filter(p -> p.name().equals("output"))
                    .findFirst()
                    .orElse(null);
            assertThat(outputParam).isNotNull();
            assertThat(outputParam.mappable()).isTrue();

            // Reference should not be mappable
            CommonMetric.ScoreParameter referenceParam = equalsMetric.scoreParameters().stream()
                    .filter(p -> p.name().equals("reference"))
                    .findFirst()
                    .orElse(null);
            assertThat(referenceParam).isNotNull();
            assertThat(referenceParam.mappable()).isFalse();
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("should return metric when found")
        void shouldReturnMetricWhenFound() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            CommonMetric metric = registry.findById("equals");

            // Then
            assertThat(metric).isNotNull();
            assertThat(metric.id()).isEqualTo("equals");
            assertThat(metric.name()).isEqualTo("Equals");
        }

        @Test
        @DisplayName("should return null when metric not found")
        void shouldReturnNullWhenMetricNotFound() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            CommonMetric metric = registry.findById("non_existent_metric");

            // Then
            assertThat(metric).isNull();
        }

        @Test
        @DisplayName("should find levenshtein_ratio metric")
        void shouldFindLevenshteinRatioMetric() {
            // Given
            List<CommonMetric> expectedMetrics = createSampleMetrics();
            setupRegistryWithMockedResponse(expectedMetrics);

            // When
            CommonMetric metric = registry.findById("levenshtein_ratio");

            // Then
            assertThat(metric).isNotNull();
            assertThat(metric.name()).isEqualTo("LevenshteinRatio");
        }

        @Test
        @DisplayName("should return null when registry is empty")
        void shouldReturnNullWhenRegistryIsEmpty() {
            // Given
            setupRegistryWithMockedResponse(List.of());

            // When
            CommonMetric metric = registry.findById("equals");

            // Then
            assertThat(metric).isNull();
        }
    }
}
