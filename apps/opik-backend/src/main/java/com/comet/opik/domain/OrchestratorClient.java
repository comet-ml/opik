package com.comet.opik.domain;

import com.comet.opik.infrastructure.ReportGenerationConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Singleton
public class OrchestratorClient {

    private final @NonNull Client httpClient;
    private final @NonNull ReportGenerationConfig config;

    @Inject
    public OrchestratorClient(@NonNull Client httpClient,
            @NonNull @Config("reportGeneration") ReportGenerationConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    public boolean isEnabled() {
        return StringUtils.isNotBlank(config.getUrl());
    }

    public void triggerReportGeneration(@NonNull String reportId, @NonNull String projectId,
            @NonNull String projectName, @NonNull String workspaceName,
            String customPrompt, @NonNull Runnable onFailure) {

        var payload = new HashMap<>(Map.of(
                "report_id", reportId,
                "project_id", projectId,
                "project_name", projectName,
                "workspace_name", workspaceName));
        payload.put("custom_prompt", customPrompt);

        httpClient.target(config.getUrl())
                .request(MediaType.APPLICATION_JSON)
                .async()
                .post(Entity.json(payload), new InvocationCallback<Response>() {
                    @Override
                    public void completed(Response response) {
                        if (response.getStatus() >= 300) {
                            log.error("Report generation trigger returned {} for report '{}'",
                                    response.getStatus(), reportId);
                            onFailure.run();
                        } else {
                            log.info("Report generation accepted for report '{}'", reportId);
                        }
                        response.close();
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        log.error("Failed to trigger report generation for report '{}'", reportId, throwable);
                        onFailure.run();
                    }
                });
    }
}
