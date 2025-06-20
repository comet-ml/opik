package com.comet.opik.infrastructure.llm.vllm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class VllmModelNameChecker {

    public static boolean isVllmModel(String model, String baseUrl) {
        try {
            String[] vllmModelNames = getVllmModelNames(baseUrl);
            return Arrays.asList(vllmModelNames).contains(model);
        } catch (Exception e) {
            log.warn("Failed to check if model {} is VLLM model for baseUrl {}: {}",
                    model, baseUrl, e.getMessage());
            return false;
        }
    }

    private static String[] getVllmModelNames(String baseUrl) {
        Client client = ClientBuilder.newClient();
        ObjectMapper objectMapper = new ObjectMapper();

        try (Response response = client.target(baseUrl)
                .path("models")
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .get()) {

            if (response.getStatus() != 200) {
                log.warn("Failed to fetch VLLM models from {}/models, status: {}",
                        baseUrl, response.getStatus());
                return new String[0];
            }

            String jsonResponse = response.readEntity(String.class);
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            JsonNode dataNode = rootNode.get("data");

            if (dataNode == null || !dataNode.isArray()) {
                log.warn("Invalid response format from VLLM models endpoint: missing or invalid 'data' field");
                return new String[0];
            }

            return dataNode.findValuesAsText("id").toArray(new String[0]);

        } catch (Exception e) {
            log.error("Error fetching VLLM model names from {}: {}", baseUrl, e.getMessage(), e);
            return new String[0];
        }
    }
}