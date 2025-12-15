package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.PromptVersionBatchUpdate;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class PromptVersionResourceClient {

    private static final String PROMPT_PATH = "%s/v1/private/prompts";
    private static final String VERSIONS_PATH = "/versions/%s";
    private static final String PROMPT_VERSIONS_PATH = PROMPT_PATH + VERSIONS_PATH;
    private static final String PROMPT_ID_VERSIONS_PATH = PROMPT_PATH + "/%s" + VERSIONS_PATH;

    private final ClientSupport client;
    private final String baseURI;

    public PromptVersion createPromptVersion(CreatePromptVersion request, String apiKey, String workspaceName) {
        try (var response = client.target(PROMPT_VERSIONS_PATH.formatted(baseURI, ""))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(PromptVersion.class);
        }
    }

    public PromptVersion.PromptVersionPage getPromptVersionsByPromptId(
            UUID promptId,
            String apiKey,
            String workspaceName,
            List<? extends Filter> filters,
            List<SortingField> sortingFields) {
        return getPromptVersionsByPromptId(promptId, apiKey, workspaceName, null, filters, sortingFields);
    }

    public PromptVersion.PromptVersionPage getPromptVersionsByPromptId(
            UUID promptId,
            String apiKey,
            String workspaceName,
            String search,
            List<? extends Filter> filters,
            List<SortingField> sortingFields) {
        var target = client.target(PROMPT_ID_VERSIONS_PATH.formatted(baseURI, promptId, ""));

        if (StringUtils.isNotBlank(search)) {
            target = target.queryParam("search", search);
        }
        if (CollectionUtils.isNotEmpty(filters)) {
            target = target.queryParam("filters", toURLEncodedQueryParam(filters));
        }
        if (CollectionUtils.isNotEmpty(sortingFields)) {
            target = target.queryParam("sorting", toURLEncodedQueryParam(sortingFields));
        }
        try (var response = target.request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(PromptVersion.PromptVersionPage.class);
        }
    }

    public void updatePromptVersions(
            PromptVersionBatchUpdate request,
            String apiKey,
            String workspaceName) {
        try (var response = client.target(PROMPT_VERSIONS_PATH.formatted(baseURI, ""))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }
}
