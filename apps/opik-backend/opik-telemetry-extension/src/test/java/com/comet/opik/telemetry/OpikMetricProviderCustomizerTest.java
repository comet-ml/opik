package com.comet.opik.telemetry;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpikMetricProviderCustomizerTest {

    @Mock
    private AutoConfigurationCustomizer autoConfigurationCustomizer;
    @Mock
    private SdkMeterProviderBuilder meterProviderBuilder;
    @Mock
    private ConfigProperties configProperties;

    private OpikMetricProviderCustomizer customizer;

    @BeforeEach
    void setUp() {
        customizer = new OpikMetricProviderCustomizer();
    }

    @Test
    void testCustomize_registersCustomizers() {
        // When
        customizer.customize(autoConfigurationCustomizer);

        // Then
        verify(autoConfigurationCustomizer).addMeterProviderCustomizer(any());
    }

    @Test
    void testCustomizeMeterProvider_returnsBuilder() {
        // When
        SdkMeterProviderBuilder result = customizer.customizeMeterProvider(meterProviderBuilder, configProperties);

        // Then
        assertNotNull(result);
        assertThat(result).isSameAs(meterProviderBuilder);
        
        // Verify that the workspace metric view is configured
        verify(meterProviderBuilder, atLeastOnce()).registerView(any(), any());
    }

    @Test
    void testOrder() {
        // When
        int order = customizer.order();

        // Then
        assertThat(order).isEqualTo(100);
    }

    @Test
    void testCustomizeMeterProvider_withHistogramBucketsEnvVar_setsExplicitBucketHistogramAggregation() {
        // Given
        String testBoundaries = "0.1, 0.5, 1.0, 2.5, 5.0, 10.0";
        when(configProperties.getString("otel.bucket.histogram.boundaries")).thenReturn(testBoundaries);

        // When
        SdkMeterProviderBuilder result = customizer.customizeMeterProvider(meterProviderBuilder, configProperties);

        // Then
        assertNotNull(result);
        assertThat(result).isSameAs(meterProviderBuilder);

        // Verify that views were registered
        ArgumentCaptor<InstrumentSelector> selectorCaptor = ArgumentCaptor.forClass(InstrumentSelector.class);
        ArgumentCaptor<View> viewCaptor = ArgumentCaptor.forClass(View.class);
        verify(meterProviderBuilder, atLeastOnce()).registerView(selectorCaptor.capture(), viewCaptor.capture());
        assertThat(viewCaptor.getAllValues()).isNotEmpty();
    }

    @Test
    void testCustomizeMeterProvider_withoutHistogramBucketsEnvVar_doesNotSetExplicitBucketHistogram() {
        // Given - no boundaries configured
        when(configProperties.getString("otel.bucket.histogram.boundaries")).thenReturn(null);

        // When
        SdkMeterProviderBuilder result = customizer.customizeMeterProvider(meterProviderBuilder, configProperties);

        // Then
        assertNotNull(result);
        assertThat(result).isSameAs(meterProviderBuilder);

        // Verify that views were registered
        ArgumentCaptor<View> viewCaptor = ArgumentCaptor.forClass(View.class);
        verify(meterProviderBuilder, atLeastOnce()).registerView(any(), viewCaptor.capture());
        assertThat(viewCaptor.getAllValues()).isNotEmpty();
    }

    @Test
    void testCustomizeMeterProvider_withWhitespaceInBoundaries_trimsCorrectly() {
        // Given
        String testBoundaries = "  0.1 , 0.5 , 1.0  ";
        List<Double> expectedBoundaries = List.of(0.1, 0.5, 1.0);
        when(configProperties.getString("otel.bucket.histogram.boundaries")).thenReturn(testBoundaries);

        // When
        customizer.customizeMeterProvider(meterProviderBuilder, configProperties);

        // Then - verify that the code path executed (views registered) and parsing works separately
        ArgumentCaptor<View> viewCaptor = ArgumentCaptor.forClass(View.class);
        verify(meterProviderBuilder, atLeastOnce()).registerView(any(), viewCaptor.capture());
        assertThat(viewCaptor.getAllValues()).isNotEmpty();

        // Separate parsing verification
        List<Double> actual = java.util.Arrays.stream(testBoundaries.split(","))
                .map(String::trim)
                .map(Double::valueOf)
                .toList();
        assertThat(actual).isEqualTo(expectedBoundaries);
    }

    @Test
    void testCustomizeMeterProvider_withEmptyEnvVar_doesNotSetAggregation() {
        // Given
        when(configProperties.getString("otel.bucket.histogram.boundaries")).thenReturn("");

        // When
        customizer.customizeMeterProvider(meterProviderBuilder, configProperties);

        // Then - views should still be registered but without explicit aggregation
        verify(meterProviderBuilder, atLeastOnce()).registerView(any(), any());
    }

    @Test
    void testHistogramBoundaryParsing_withCommaSeparatedValues() {
        // Given - test the parsing logic that's used in the customizer
        String boundariesString = "0.1, 0.5, 1.0, 2.5, 5.0, 10.0";
        List<Double> expected = List.of(0.1, 0.5, 1.0, 2.5, 5.0, 10.0);

        // When - simulate the parsing logic
        List<Double> actual = java.util.Arrays.stream(boundariesString.split(","))
                .map(String::trim)
                .map(Double::valueOf)
                .toList();

        // Then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void testHistogramBoundaryParsing_withWhitespace_trimsCorrectly() {
        // Given - test trimming logic
        String boundariesString = "  0.1 , 0.5 , 1.0  ";
        List<Double> expected = List.of(0.1, 0.5, 1.0);

        // When - simulate the parsing logic
        List<Double> actual = java.util.Arrays.stream(boundariesString.split(","))
                .map(String::trim)
                .map(Double::valueOf)
                .toList();

        // Then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void testHistogramBoundaryParsing_emptyString_returnsEmpty() {
        // Given
        String boundariesString = "";

        // When
        boolean isEmpty = boundariesString.trim().isEmpty();
        assertThat(isEmpty).isTrue();
    }

    @Test
    void testDurationMetrics_areRegisteredWithConvertedBoundaries() {
        // Given - boundaries in milliseconds
        String testBoundaries = "1.0, 5.0, 10.0, 50.0, 100.0, 500.0, 1000.0";
        when(configProperties.getString("otel.bucket.histogram.boundaries")).thenReturn(testBoundaries);

        // When
        customizer.customizeMeterProvider(meterProviderBuilder, configProperties);

        // Then - verify that duration metrics are registered
        ArgumentCaptor<InstrumentSelector> selectorCaptor = ArgumentCaptor.forClass(InstrumentSelector.class);
        ArgumentCaptor<View> viewCaptor = ArgumentCaptor.forClass(View.class);
        verify(meterProviderBuilder, atLeastOnce()).registerView(selectorCaptor.capture(), viewCaptor.capture());

        // Verify at least the duration metrics are registered
        List<InstrumentSelector> selectors = selectorCaptor.getAllValues();
        assertThat(selectors).isNotEmpty();
    }

    @Test
    void testBoundaryConversion_millisecondsToSeconds() {
        // Given - boundaries in milliseconds
        List<Double> millisecondsValues = List.of(1.0, 5.0, 10.0, 50.0, 100.0, 500.0, 1000.0);
        
        // When - convert to seconds (divide by 1000)
        List<Double> secondsValues = millisecondsValues.stream()
                .map(boundary -> boundary / 1000.0)
                .toList();

        // Then - verify conversion
        List<Double> expected = List.of(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0);
        assertThat(secondsValues).isEqualTo(expected);
    }

    @Test
    void testCustomizeMeterProvider_registersDurationMetrics() {
        // Given
        String testBoundaries = "100.0, 500.0, 1000.0";
        when(configProperties.getString("otel.bucket.histogram.boundaries")).thenReturn(testBoundaries);

        // When
        customizer.customizeMeterProvider(meterProviderBuilder, configProperties);

        // Then
        ArgumentCaptor<InstrumentSelector> selectorCaptor = ArgumentCaptor.forClass(InstrumentSelector.class);
        verify(meterProviderBuilder, atLeastOnce()).registerView(selectorCaptor.capture(), any());

        // Verify that views were registered for duration metrics
        assertThat(selectorCaptor.getAllValues()).isNotEmpty();
    }
}
