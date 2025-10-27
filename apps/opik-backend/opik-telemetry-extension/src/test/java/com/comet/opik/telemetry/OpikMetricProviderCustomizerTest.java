package com.comet.opik.telemetry;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
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
        assertEquals(meterProviderBuilder, result);
        
        // Verify that the workspace metric view is configured
        verify(meterProviderBuilder, atLeastOnce()).registerView(any(), any());
    }

    @Test
    void testOrder() {
        // When
        int order = customizer.order();

        // Then
        assertEquals(100, order);
    }
}