package com.comet.opik.domain.cost;

import com.comet.opik.domain.IdGenerator;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
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
    private final WorkspaceOrganizationLookup organizationLookup = mock(WorkspaceOrganizationLookup.class);
    private final ModelPriceService service = new ModelPriceServiceImpl(transactionTemplate, mock(IdGenerator.class),
            organizationLookup);
    private final String workspaceId = RandomStringUtils.secure().nextAlphanumeric(10);
    private final String organizationId = RandomStringUtils.secure().nextAlphanumeric(10);

    @Test
    void unknownOrganizationPricesWithBuiltInOnly() {
        when(organizationLookup.organizationOf(workspaceId)).thenReturn(Mono.empty());

        assertThat(service.layersFor(workspaceId).block()).isSameAs(PriceLayers.BUILT_IN_ONLY);

        verifyNoInteractions(transactionTemplate);
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
        when(organizationLookup.organizationOf(workspaceId)).thenReturn(Mono.just(organizationId));
        doReturn(list).when(transactionTemplate).inTransaction(any(TxConfig.class), any());

        var layers = service.layersFor(workspaceId).block();

        assertThat(layers).usingRecursiveComparison().isEqualTo(list.toLayers());
    }

    @Test
    void failedLoadPricesWithBuiltInOnly() {
        when(organizationLookup.organizationOf(workspaceId)).thenReturn(Mono.just(organizationId));
        doThrow(new IllegalStateException("MySQL is down")).when(transactionTemplate)
                .inTransaction(any(TxConfig.class), any());

        assertThat(service.layersFor(workspaceId).block()).isSameAs(PriceLayers.BUILT_IN_ONLY);
    }

    @Test
    void failedOrganizationLookupPricesWithBuiltInOnly() {
        when(organizationLookup.organizationOf(workspaceId))
                .thenReturn(Mono.error(new IllegalStateException("Comet is down")));

        assertThat(service.layersFor(workspaceId).block()).isSameAs(PriceLayers.BUILT_IN_ONLY);

        verifyNoInteractions(transactionTemplate);
    }
}
