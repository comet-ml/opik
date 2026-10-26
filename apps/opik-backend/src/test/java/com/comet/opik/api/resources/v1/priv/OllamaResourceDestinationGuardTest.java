package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.OllamaInstanceBaseUrlRequest;
import com.comet.opik.domain.OllamaService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.net.DestinationGuard;
import jakarta.ws.rs.BadRequestException;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("Ollama Resource: strict destination guard")
class OllamaResourceDestinationGuardTest {

    private final OllamaService ollamaService = mock(OllamaService.class);
    private final OllamaResource resource;

    {
        var config = TestConfigUtils.loadConfigTest();
        config.getLlmProviderClient().setDestinationGuard(DestinationGuard.Mode.STRICT);
        resource = new OllamaResource(ollamaService,
                () -> RequestContext.builder().workspaceName("test-workspace").build(), config);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://169.254.1.1", "http://192.168.1.10:8080/v1"})
    @DisplayName("test-connection and models reject non-public destinations")
    void rejectsNonPublicDestination(String baseUrl) {
        var request = OllamaInstanceBaseUrlRequest.builder().baseUrl(baseUrl).build();

        assertThatThrownBy(() -> resource.testConnection(request))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getResponse().getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST));
        assertThatThrownBy(() -> resource.listModels(request))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getResponse().getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST));
        verifyNoInteractions(ollamaService);
    }
}
