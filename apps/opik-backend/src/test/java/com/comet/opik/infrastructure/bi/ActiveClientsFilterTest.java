package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.UsageReportConfig;
import com.comet.opik.infrastructure.bi.ActiveClientsService.ClientType;
import com.comet.opik.infrastructure.http.CorsFactory;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActiveClientsFilterTest {

    @Mock
    private OpikConfiguration config;

    @Mock
    private ActiveClientsService activeClientsService;

    @Mock
    private ContainerRequestContext requestContext;

    private static final String SDK_CLIENT_ID = "a".repeat(64);
    private static final String BROWSER_ID = UUID.randomUUID().toString();

    @Test
    void usageReportDisabled__clientsNotCounted() {
        filterWithUsageReport(false);

        verify(activeClientsService, never()).record(any(), any());
    }

    @Test
    void usageReportEnabled__clientsCounted() {
        filterWithUsageReport(true);

        verify(activeClientsService).record(ClientType.SDK, SDK_CLIENT_ID);
        verify(activeClientsService).record(ClientType.UI, BROWSER_ID);
    }

    private void filterWithUsageReport(boolean enabled) {
        var usageReport = new UsageReportConfig();
        usageReport.setEnabled(enabled);
        when(config.getUsageReport()).thenReturn(usageReport);
        when(requestContext.getHeaderString(CorsFactory.OPIK_CLIENT_ID_HEADER)).thenReturn(SDK_CLIENT_ID);
        when(requestContext.getHeaderString(CorsFactory.OPIK_BROWSER_ID_HEADER)).thenReturn(BROWSER_ID);

        new ActiveClientsFilter(config, activeClientsService).filter(requestContext);
    }
}
