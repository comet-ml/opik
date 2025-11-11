package com.comet.opik.telemetry;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.ViewBuilder;

import java.util.Arrays;
import java.util.List;

/**
 * Customizer for OpenTelemetry metric provider configuration.
 * 
 * This customizer allows for fine-grained control over the OpenTelemetry metrics
 * configuration, including custom resource attributes, metric readers, and export settings.
 * 
 * This extension is designed to be packaged as a separate JAR and loaded by the
 * OpenTelemetry Java agent during runtime.
 */
public class OpikMetricProviderCustomizer implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        System.out.println("Applying Opik-specific OpenTelemetry metric provider customizations");
        
        // Customize the meter provider
        customizer.addMeterProviderCustomizer(this::customizeMeterProvider);
    }

    /**
     * Customizes the SdkMeterProviderBuilder with Opik-specific configurations.
     * 
     * @param builder the meter provider builder
     * @param config the configuration properties
     * @return the customized builder
     */
    SdkMeterProviderBuilder customizeMeterProvider(SdkMeterProviderBuilder builder, ConfigProperties config) {
        // Configure custom metric readers if needed
        configureCustomMetricReaders(builder, config);
        return builder;
    }

    /**
     * Configures custom metric readers for specific use cases.
     */
    private void configureCustomMetricReaders(SdkMeterProviderBuilder builder, ConfigProperties config) {
        // Configure metric view to include workspace context attribute
        configureWorkspaceMetricView(builder, config);
    }

    /**
     * Configures metric views to include workspace context attributes.
     * This ensures that metrics include the "http.request.header.comet-workspace" attribute
     * from the request context for better observability and filtering.
     */
    private void configureWorkspaceMetricView(SdkMeterProviderBuilder builder, ConfigProperties config) {

        // Create a single view that includes workspace context attributes
        // This view will be applied to all instruments without changing their names
        String histogramBoundaries = null;
        if (config != null) {
            // OTEL agent maps env var OTEL_BUCKET_HISTOGRAM_BOUNDARIES -> otel.bucket.histogram.boundaries
            histogramBoundaries = config.getString("otel.bucket.histogram.boundaries");
        }
        if (histogramBoundaries == null) {
            histogramBoundaries = System.getenv().getOrDefault("OTEL_BUCKET_HISTOGRAM_BOUNDARIES", "");
        }

        ViewBuilder viewBuilder = View.builder()
                .setDescription("Metric view that includes workspace context attributes")
                .setAttributeFilter(value -> true);

        // Only apply custom aggregation if OTEL_BUCKET_HISTOGRAM_BOUNDARIES is set
        if (!histogramBoundaries.trim().isEmpty()) {
            try {
                List<Double> baseBoundaries = Arrays.stream(histogramBoundaries.split(","))
                        .map(String::trim)
                        .map(Double::valueOf)
                        .toList();

                // Register duration metrics with unit conversion (milliseconds to seconds)
                registerDurationMetricsWithConversion(builder, baseBoundaries);
            } catch (NumberFormatException e) {
                System.out.printf("Invalid OTEL_BUCKET_HISTOGRAM_BOUNDARIES: '%s': %s. Using defaults (no explicit buckets).%n", histogramBoundaries, e.getMessage());
            }
        }

        View workspaceView = viewBuilder.build();
        
        // Register workspace view for all other instruments (generic, matched after specific views)
        Arrays.stream(InstrumentType.values()).forEach(instrumentType -> {
            builder.registerView(
                    InstrumentSelector.builder()
                            .setType(instrumentType)
                            .build(),
                    workspaceView);
        });
        System.out.println("Workspace metric view configured successfully for all instruments");
    }

    /**
     * Registers histogram views for duration metrics with unit conversion.
     * Duration metrics are in seconds, so boundaries provided in milliseconds
     * are divided by 1000 to match the metric unit.
     *
     * @param builder the meter provider builder
     * @param baseBoundaries the base boundaries (typically in milliseconds)
     */
    private void registerDurationMetricsWithConversion(SdkMeterProviderBuilder builder, List<Double> baseBoundaries) {
        // Guard: ensure boundaries are provided and valid
        if (baseBoundaries.isEmpty()) {
            System.out.println("No histogram boundaries provided; skipping duration metric aggregation configuration");
            return;
        }

        // List of metrics that represent durations in seconds
        List<String> durationMetrics = Arrays.asList("http_server_request_duration");

        // Convert boundaries from milliseconds to seconds for duration metrics
        List<Double> convertedBoundaries = baseBoundaries.stream()
                .map(boundary -> boundary / 1000.0)
                .toList();

        for (String metricName : durationMetrics) {
            ViewBuilder histogramViewBuilder = View.builder()
                    .setDescription("Histogram view for duration metric: " + metricName)
                    .setAttributeFilter(value -> true)
                    .setAggregation(Aggregation.explicitBucketHistogram(convertedBoundaries));

            builder.registerView(
                    InstrumentSelector.builder()
                            .setType(InstrumentType.HISTOGRAM)
                            .setName(metricName)
                            .build(),
                    histogramViewBuilder.build());
        }

        System.out.println("Duration metrics aggregation configured with converted boundaries");
    }

    @Override
    public int order() {
        // Set a high priority to ensure our customizations are applied
        // after the default configurations but before other customizers
        return 100;
    }
}
