package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DatasetExpansion;
import com.comet.opik.api.DatasetExpansionResponse;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.domain.CsvDatasetExportService;
import com.comet.opik.domain.CsvDatasetItemProcessor;
import com.comet.opik.domain.DatasetExpansionService;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.Streamer;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.IdGeneratorImpl;
import com.comet.opik.infrastructure.json.JsonNodeMessageBodyWriter;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import org.glassfish.jersey.client.ChunkedInput;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Flux;
import uk.co.jemos.podam.api.PodamFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static com.comet.opik.domain.ProjectService.DEFAULT_USER;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
class DatasetsResourceIntegrationTest {

    private static final DatasetService service = mock(DatasetService.class);
    private static final DatasetItemService itemService = mock(DatasetItemService.class);
    private static final DatasetExpansionService expansionService = mock(DatasetExpansionService.class);
    private static final DatasetVersionService versionService = mock(DatasetVersionService.class);
    private static final RequestContext requestContext = mock(RequestContext.class);
    private static final CsvDatasetItemProcessor csvProcessor = mock(CsvDatasetItemProcessor.class);
    private static final FeatureFlags featureFlags = mock(FeatureFlags.class);
    public static final SortingFactoryDatasets sortingFactory = new SortingFactoryDatasets();
    private static final CsvDatasetExportService csvExportService = mock(CsvDatasetExportService.class);
    private static final ResourceExtension EXT;

    static {

        EXT = ResourceExtension.builder()
                .addResource(new DatasetsResource(
                        service, itemService, expansionService, versionService, () -> requestContext,
                        new FiltersFactory(new FilterQueryBuilder()),
                        new IdGeneratorImpl(), new Streamer(), sortingFactory, csvProcessor,
                        featureFlags, csvExportService))
                .addProvider(JsonNodeMessageBodyWriter.class)
                .addProvider(MultiPartFeature.class)
                .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
                .build();
    }

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

    @Test
    void testCsvUploadFeatureToggleDisabled() {
        // Given: Feature toggle is disabled
        when(featureFlags.isCsvUploadEnabled()).thenReturn(false);

        UUID datasetId = UUID.randomUUID();
        String csvContent = "input,output\nQuestion,Answer\n";
        byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
        InputStream csvInputStream = new ByteArrayInputStream(csvBytes);

        FormDataMultiPart multiPart = new FormDataMultiPart();
        multiPart.field("dataset_id", datasetId.toString());
        multiPart.bodyPart(new FormDataBodyPart("file", csvInputStream, MediaType.APPLICATION_OCTET_STREAM_TYPE));

        // When: Attempt to upload CSV
        try (var response = EXT.target("/v1/private/datasets/items/from-csv")
                .register(MultiPartFeature.class)
                .request()
                .header("workspace", DEFAULT_WORKSPACE_NAME)
                .post(Entity.entity(multiPart, multiPart.getMediaType()))) {

            // Then: Should return 404 Not Found
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }

    private Map<String, JsonNode> createTestData() {
        ObjectMapper objectMapper = new ObjectMapper();
        return Map.of(
                "field1", objectMapper.valueToTree("test1"),
                "field2", objectMapper.valueToTree("test2"));
    }
}