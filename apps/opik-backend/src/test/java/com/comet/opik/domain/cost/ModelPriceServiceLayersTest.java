package com.comet.opik.domain.cost;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.OrganizationResolver;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import io.opentelemetry.api.common.Attributes;
import org.apache.commons.lang3.RandomStringUtils;
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
    private final String organizationId = RandomStringUtils.secure().nextAlphanumeric(10);

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
        var list = ModelPriceList.builder()
                .organizationId(organizationId)
                .version(3)
                .rows(List.of(CustomModelPrice.builder()
                        .modelName(RandomStringUtils.secure().nextAlphanumeric(10))
                        .provider(CustomModelPrice.ANY_PROVIDER)
                        .kind(CustomModelPrice.Kind.FIXED)
                        .build()))
                .build();
        when(organizationResolver.organizationId()).thenReturn(Mono.just(organizationId));
        doReturn(list).when(transactionTemplate).inTransaction(any(TxConfig.class), any());

        var layers = service(true).layersFor().block();

        assertThat(layers).usingRecursiveComparison().isEqualTo(list.toLayers());
    }

    @Test
    void failedLoadPricesWithBuiltInAndSaysSo() {
        when(organizationResolver.organizationId()).thenReturn(Mono.just(organizationId));
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
        var workspaceId = RandomStringUtils.secure().nextAlphanumeric(10);

        var attributes = ModelPriceServiceImpl.loadFailureAttributes(new IllegalStateException("down"),
                Context.of(RequestContext.WORKSPACE_ID, workspaceId, RequestContext.WORKSPACE_NAME, ""));

        assertThat(attributes).isEqualTo(Attributes.of(
                ErrorMetricsResolver.WORKSPACE_ID_KEY, workspaceId,
                ErrorMetricsResolver.WORKSPACE_NAME_KEY, workspaceId,
                ErrorMetricsResolver.ERROR_TYPE_KEY, "IllegalStateException"));
    }

    @Test
    void loadFailureWithoutAWorkspaceIsLabelledUnknown() {
        var attributes = ModelPriceServiceImpl.loadFailureAttributes(new IllegalStateException("down"),
                Context.empty());

        assertThat(attributes).isEqualTo(Attributes.of(
                ErrorMetricsResolver.WORKSPACE_ID_KEY, ErrorMetricsResolver.UNKNOWN,
                ErrorMetricsResolver.WORKSPACE_NAME_KEY, ErrorMetricsResolver.UNKNOWN,
                ErrorMetricsResolver.ERROR_TYPE_KEY, "IllegalStateException"));
    }
}
