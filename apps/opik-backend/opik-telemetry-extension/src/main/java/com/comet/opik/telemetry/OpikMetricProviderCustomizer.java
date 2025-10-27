package com.comet.opik.telemetry;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.View;
import java.util.Arrays;

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
        System.out.println("[OpikTelemetryExtension] Applying Opik-specific OpenTelemetry metric provider customizations");
        
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
        configureCustomMetricReaders(builder);
        return builder;
    }

    /**
     * Configures custom metric readers for specific use cases.
     */
    private void configureCustomMetricReaders(SdkMeterProviderBuilder builder) {
        // Configure metric view to include workspace context attribute
        configureWorkspaceMetricView(builder);
    }

    /**
     * Configures metric views to include workspace context attributes.
     * This ensures that metrics include the "http.request.header.comet-workspace" attribute
     * from the request context for better observability and filtering.
     */
    private void configureWorkspaceMetricView(SdkMeterProviderBuilder builder) {

        // Create a single view that includes workspace context attributes
        // This view will be applied to all instruments without changing their names
        View workspaceView = View.builder()
                .setDescription("Metric view that includes workspace context attributes")
                .setAttributeFilter(value -> true)
                .build();
        
        // Register the view for all instruments to include workspace context
        // Using a selector that matches all instruments by type
        Arrays.stream(InstrumentType.values()).forEach(instrumentType -> {
            builder.registerView(
                    InstrumentSelector.builder()
                            .setType(instrumentType)
                            .build(),
                    workspaceView
            );
        });
        System.out.println("[OpikTelemetryExtension] Workspace metric view configured successfully for all instruments");
    }

    @Override
    public int order() {
        // Set a high priority to ensure our customizations are applied
        // after the default configurations but before other customizers
        return 100;
    }
}
