package com.comet.opik.domain.cost;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.OrganizationResolver;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelPriceServiceLayersTest {

    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final OrganizationResolver organizationResolver = mock(OrganizationResolver.class);
    private final ServiceTogglesConfig serviceToggles = new ServiceTogglesConfig();

    private ModelPriceService service(boolean enabled) {
        serviceToggles.setModelPricesEnabled(enabled);
        return new ModelPriceServiceImpl(transactionTemplate, mock(IdGenerator.class), organizationResolver,
                serviceToggles);
    }

    @Test
    void switchedOffPricesWithBuiltInOnlyWithoutLookingAnythingUp() {
        assertThat(service(false).layersFor().block()).isSameAs(PriceLayers.BUILT_IN_ONLY);

        verifyNoInteractions(organizationResolver, transactionTemplate);
    }

    @Test
    void unknownOrganizationPricesWithBuiltIn() {
        when(organizationResolver.organizationId()).thenReturn(Mono.empty());

        assertThat(service(true).layersFor().block()).isSameAs(PriceLayers.NO_ORGANIZATION);
    }

    @Test
    void knownOrganizationPricesWithItsList() {
        when(organizationResolver.organizationId()).thenReturn(Mono.just("org-1"));
        doReturn(new ModelPriceList("org-1", 3, List.of())).when(transactionTemplate)
                .inTransaction(any(TxConfig.class), any());

        var layers = service(true).layersFor().block();

        assertThat(layers.getOrigin()).isEqualTo(PriceLayers.Origin.ORGANIZATION);
        assertThat(layers.getOrganizationId()).isEqualTo("org-1");
        assertThat(layers.getListVersion()).isEqualTo(3);
    }

    @Test
    void failedLoadPricesWithBuiltInAndSaysSo() {
        when(organizationResolver.organizationId()).thenReturn(Mono.just("org-1"));
        doThrow(new IllegalStateException("MySQL is down")).when(transactionTemplate)
                .inTransaction(any(TxConfig.class), any());

        assertThat(service(true).layersFor().block()).isSameAs(PriceLayers.LOAD_FAILED);
    }

    @Test
    void slowLoadPricesWithBuiltInAfterTheTimeout() {
        when(organizationResolver.organizationId()).thenReturn(Mono.never());
        var service = service(true);

        StepVerifier.withVirtualTime(service::layersFor)
                .thenAwait(ModelPriceServiceImpl.LOAD_TIMEOUT)
                .expectNext(PriceLayers.LOAD_FAILED)
                .verifyComplete();
    }

    @Test
    void loadFailureWithABlankWorkspaceNameIsLabelledWithTheWorkspaceId() {
        var attributes = ModelPriceServiceImpl.loadFailureAttributes(new IllegalStateException("down"),
                Context.of(RequestContext.WORKSPACE_ID, "workspace-1", RequestContext.WORKSPACE_NAME, ""));

        assertThat(attributes.get(ErrorMetricsResolver.WORKSPACE_ID_KEY)).isEqualTo("workspace-1");
        assertThat(attributes.get(ErrorMetricsResolver.WORKSPACE_NAME_KEY)).isEqualTo("workspace-1");
        assertThat(attributes.get(ErrorMetricsResolver.ERROR_TYPE_KEY)).isEqualTo("IllegalStateException");
    }

    @Test
    void loadFailureWithoutAWorkspaceIsLabelledUnknown() {
        var attributes = ModelPriceServiceImpl.loadFailureAttributes(new IllegalStateException("down"),
                Context.empty());

        assertThat(attributes.get(ErrorMetricsResolver.WORKSPACE_ID_KEY)).isEqualTo(ErrorMetricsResolver.UNKNOWN);
        assertThat(attributes.get(ErrorMetricsResolver.WORKSPACE_NAME_KEY)).isEqualTo(ErrorMetricsResolver.UNKNOWN);
    }
}
