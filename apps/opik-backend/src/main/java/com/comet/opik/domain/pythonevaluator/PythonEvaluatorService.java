package com.comet.opik.domain.pythonevaluator;

import com.comet.opik.infrastructure.PythonEvaluatorConfig;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class PythonEvaluatorService {

    private static final String URL_TEMPLATE = "%s/v1/private/evaluators/python";

    private final @NonNull Client client;
    private final @NonNull @Config("pythonEvaluator") PythonEvaluatorConfig pythonEvaluatorConfig;

    public List<PythonScoreResult> evaluate(@NonNull String code, Map<String, String> data) {
        Preconditions.checkArgument(MapUtils.isNotEmpty(data), "Argument 'data' must not be empty");
        var request = PythonEvaluatorRequest.builder()
                .code(code)
                .data(data)
                .build();
        try (var response = client.target(URI.create(URL_TEMPLATE.formatted(pythonEvaluatorConfig.url())))
                .request()
                .post(Entity.json(request))) {
            var result = getAndValidateResult(response);
            return result.scores();
        }
    }

    private PythonEvaluatorResponse getAndValidateResult(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(PythonEvaluatorResponse.class);
        }
        String errorMessage = null;
        if (response.hasEntity() && response.bufferEntity()) {
            try {
                errorMessage = response.readEntity(PythonEvaluatorErrorResponse.class).error();
            } catch (RuntimeException exceptionErrorResponse) {
                log.warn("Failed to parse error response, falling back to parsing string", exceptionErrorResponse);
                try {
                    errorMessage = response.readEntity(String.class);
                } catch (RuntimeException exceptionStringResponse) {
                    log.warn("Failed to parse error string, falling back to default message", exceptionStringResponse);
                }
            }
        }
        // Any other status code is an internal server error from the perspective of the caller
        errorMessage = StringUtils.isNotBlank(errorMessage) ? errorMessage : "Unknown error during Python evaluation";
        throw new InternalServerErrorException(errorMessage);
    }
}
