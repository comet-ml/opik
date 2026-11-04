package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.modelprice.CustomModelPriceReplace;
import com.comet.opik.api.modelprice.CustomModelPriceResolveRequest;
import com.comet.opik.domain.cost.ModelPriceService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.OrganizationResolver;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ModelPricesResourceToggleTest {

    private final ModelPriceService modelPriceService = mock(ModelPriceService.class);
    private final OrganizationResolver organizationResolver = mock(OrganizationResolver.class);
    private final ModelPricesResource resource = new ModelPricesResource(modelPriceService, organizationResolver,
            RequestContext::new, new OpikConfiguration());

    static Stream<Named<Function<ModelPricesResource, Response>>> endpoints() {
        return Stream.of(
                Named.of("find", ModelPricesResource::find),
                Named.of("replace", resource -> resource.replace(false, new CustomModelPriceReplace(List.of(), 0L))),
                Named.of("history", resource -> resource.findHistory(1, 100)),
                Named.of("resolve", resource -> resource.resolve(
                        new CustomModelPriceResolveRequest("gpt-5-mini", "openai", null))));
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void everyEndpointAnswers503WhileTheToggleIsOff(Function<ModelPricesResource, Response> endpoint) {
        try (var response = endpoint.apply(resource)) {
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(((ErrorMessage) response.getEntity()).getMessage()).isEqualTo(ModelPricesResource.DISABLED);
        }
        verifyNoInteractions(modelPriceService, organizationResolver);
    }
}
