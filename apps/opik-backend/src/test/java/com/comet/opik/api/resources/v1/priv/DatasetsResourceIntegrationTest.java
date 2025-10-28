package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DatasetExpansion;
import com.comet.opik.api.DatasetExpansionResponse;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.domain.DatasetExpansionService;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.Streamer;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.workspaces.WorkspaceMetadataService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.IdGeneratorImpl;
import com.comet.opik.infrastructure.json.JsonNodeMessageBodyWriter;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import org.glassfish.jersey.client.ChunkedInput;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static com.comet.opik.domain.ProjectService.DEFAULT_USER;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
class DatasetsResourceIntegrationTest {

    private static final DatasetService service = Mockito.mock(DatasetService.class);
    private static final DatasetItemService itemService = Mockito.mock(DatasetItemService.class);
    private static final DatasetExpansionService expansionService = Mockito.mock(DatasetExpansionService.class);
    private static final RequestContext requestContext = Mockito.mock(RequestContext.class);
    private static final WorkspaceMetadataService workspaceMetadataService = Mockito
            .mock(WorkspaceMetadataService.class);
    private static final TimeBasedEpochGenerator timeBasedGenerator = Generators.timeBasedEpochGenerator();
    public static final SortingFactoryDatasets sortingFactory = new SortingFactoryDatasets();

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .addResource(new DatasetsResource(
                    service, itemService, expansionService, () -> requestContext,
                    new FiltersFactory(new FilterQueryBuilder()),
                    new IdGeneratorImpl(), new Streamer(), sortingFactory, workspaceMetadataService))
            .addProvider(JsonNodeMessageBodyWriter.class)
            .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
            .build();

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    @Test
    void testStreamErrorHandling() {
        var datasetName = "test";
        var workspaceId = UUID.randomUUID().toString();

        when(requestContext.getUserName())
                .thenReturn(DEFAULT_USER);

        when(requestContext.getWorkspaceId())
                .thenReturn(workspaceId);

        when(requestContext.getVisibility())
                .thenReturn(Visibility.PRIVATE);

        var items = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class);

        Flux<DatasetItem> itemFlux = Flux.create(sink -> {
            items.forEach(sink::next);

            sink.error(new TimeoutException("Connection timed out"));
        });

        var request = DatasetItemStreamRequest.builder().datasetName(datasetName).steamLimit(500).build();

        when(itemService.getItems(workspaceId, request, Visibility.PRIVATE))
                .thenReturn(Flux.defer(() -> itemFlux));

        try (var response = EXT.target("/v1/private/datasets/items/stream")
                .request()
                .header("workspace", DEFAULT_WORKSPACE_NAME)
                .post(Entity.json(request))) {

            try (var inputStream = response.readEntity(new GenericType<ChunkedInput<String>>() {
            })) {
                for (int i = 0; i < 5; i++) {
                    var line = inputStream.read();
                    TypeReference<DatasetItem> typeReference = new TypeReference<>() {
                    };

                    var datasetItem = JsonUtils.readValue(line, typeReference);
                    assertThat(datasetItem).isIn(items);
                }

                TypeReference<ErrorMessage> typeReference = new TypeReference<>() {
                };
                String line = inputStream.read();
                var errorMessage = JsonUtils.readValue(line, typeReference);

                assertThat(errorMessage.getMessage()).isEqualTo("Streaming operation timed out");
                assertThat(errorMessage.getCode()).isEqualTo(500);
            }
        }
    }

    @Test
    void testDatasetExpansion() {
        // Given
        var datasetId = UUID.randomUUID();
        var workspaceId = UUID.randomUUID().toString();

        when(requestContext.getUserName())
                .thenReturn(DEFAULT_USER);

        when(requestContext.getWorkspaceId())
                .thenReturn(workspaceId);

        when(requestContext.getVisibility())
                .thenReturn(Visibility.PRIVATE);

        var request = DatasetExpansion.builder()
                .model("gpt-4")
                .sampleCount(2)
                .build();

        var mockResponse = DatasetExpansionResponse.builder()
                .generatedSamples(List.of(
                        DatasetItem.builder()
                                .id(UUID.randomUUID())
                                .datasetId(datasetId)
                                .data(createTestData())
                                .source(com.comet.opik.api.DatasetItemSource.MANUAL)
                                .build(),
                        DatasetItem.builder()
                                .id(UUID.randomUUID())
                                .datasetId(datasetId)
                                .data(createTestData())
                                .source(com.comet.opik.api.DatasetItemSource.MANUAL)
                                .build()))
                .model("gpt-4")
                .totalGenerated(2)
                .generationTime(java.time.Instant.now())
                .build();

        when(expansionService.expandDataset(datasetId, request))
                .thenReturn(mockResponse);

        // When
        try (var response = EXT.target("/v1/private/datasets/" + datasetId + "/expansions")
                .request()
                .header("workspace", DEFAULT_WORKSPACE_NAME)
                .post(Entity.json(request))) {

            // Then
            assertThat(response.getStatus()).isEqualTo(200);

            var expansionResponse = response.readEntity(DatasetExpansionResponse.class);
            assertThat(expansionResponse).isNotNull();
            assertThat(expansionResponse.generatedSamples()).hasSize(2);
            assertThat(expansionResponse.model()).isEqualTo("gpt-4");
            assertThat(expansionResponse.totalGenerated()).isEqualTo(2);
            assertThat(expansionResponse.generationTime()).isNotNull();
        }
    }

    private Map<String, JsonNode> createTestData() {
        ObjectMapper objectMapper = new ObjectMapper();
        return Map.of(
                "field1", objectMapper.valueToTree("test1"),
                "field2", objectMapper.valueToTree("test2"));
    }
}