package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Prompt;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.api.filter.PromptFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DESCRIPTION;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Project-scoped prompt operations")
@ExtendWith(DropwizardAppExtensionProvider.class)
class PromptResourceProjectScopedPromptsTest {

    private static final String RESOURCE_PATH = "%s/v1/private/prompts";
    public static final String[] PROMPT_IGNORED_FIELDS = {"latestVersion", "requestedVersion", "template", "metadata",
            "changeDescription", "type", "projectName"};

    private static final String USER = UUID.randomUUID().toString();

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private PromptResourceClient promptResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.promptResourceClient = new PromptResourceClient(client, baseURI, factory);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        setup.wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(setup.wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private Prompt.PromptBuilder buildPrompt() {
        return PromptResourceClient.buildPrompt(factory).toBuilder();
    }

    private UUID createPrompt(Prompt prompt, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(prompt))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);

            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    private void findPromptsAndAssertPage(List<Prompt> expectedPrompts, String apiKey, String workspaceName,
            int expectedTotal, int page, String nameSearch, List<SortingField> sortingFields,
            List<PromptFilter> filters, UUID projectId) {

        var target = client.target(RESOURCE_PATH.formatted(baseURI));

        if (nameSearch != null) {
            target = target.queryParam("name", nameSearch);
        }

        if (page > 1) {
            target = target.queryParam("page", page);
        }

        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }

        if (sortingFields != null && !sortingFields.isEmpty()) {
            target = target.queryParam("sorting",
                    URLEncoder.encode(com.comet.opik.utils.JsonUtils.writeValueAsString(sortingFields),
                            StandardCharsets.UTF_8));
        }

        if (filters != null && !filters.isEmpty()) {
            target = target.queryParam("filters", toURLEncodedQueryParam(filters));
        }

        try (var response = target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            var promptPage = response.readEntity(Prompt.PromptPage.class);
            assertPromptsPage(promptPage, expectedPrompts, expectedTotal, page);
        }
    }

    private void assertPromptsPage(Prompt.PromptPage promptPage, List<Prompt> expectedPrompts, int expectedTotal,
            int page) {
        assertThat(promptPage.total()).isEqualTo(expectedTotal);
        assertThat(promptPage.content()).hasSize(expectedPrompts.size());
        assertThat(promptPage.page()).isEqualTo(page);
        assertThat(promptPage.size()).isEqualTo(expectedPrompts.size());

        assertThat(promptPage.sortableBy()).contains(
                ID,
                NAME,
                DESCRIPTION,
                CREATED_AT,
                LAST_UPDATED_AT,
                CREATED_BY,
                LAST_UPDATED_BY,
                TAGS);

        assertThat(promptPage.content())
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withIgnoredFields(PROMPT_IGNORED_FIELDS)
                                .withComparatorForType(
                                        PromptResourceProjectScopedPromptsTest::comparatorForCreateAtAndUpdatedAt,
                                        Instant.class)
                                .build())
                .isEqualTo(expectedPrompts);
    }

    private static int comparatorForCreateAtAndUpdatedAt(Instant actual, Instant expected) {
        var now = Instant.now();

        if (actual.isAfter(now) || actual.equals(now))
            return 1;
        if (actual.isBefore(expected))
            return -1;

        Assertions.assertThat(actual).isBetween(expected, now);
        return 0;
    }

    private void assertPrompt(Prompt fetchedPrompt, Prompt prompt) {
        assertThat(fetchedPrompt)
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withIgnoredFields(PROMPT_IGNORED_FIELDS)
                                .withComparatorForType(
                                        PromptResourceProjectScopedPromptsTest::comparatorForCreateAtAndUpdatedAt,
                                        Instant.class)
                                .build())
                .isEqualTo(prompt);
    }

    @Test
    @DisplayName("Create prompt with project_id persists and returns project_id")
    void createPromptWithProjectId() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var prompt = buildPrompt()
                .projectId(projectId)
                .lastUpdatedBy(USER)
                .createdBy(USER)
                .template(null)
                .versionCount(0L)
                .templateStructure(TemplateStructure.TEXT)
                .build();

        var id = createPrompt(prompt, apiKey, workspaceName);
        var fetchedPrompt = promptResourceClient.getPrompt(id, apiKey, workspaceName);

        assertPrompt(fetchedPrompt, prompt.toBuilder().id(id).build());
    }

    @Test
    @DisplayName("Create prompt with non-existing project_id returns not found")
    void createPromptWithNonExistingProjectId() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var prompt = buildPrompt()
                .projectId(factory.manufacturePojo(UUID.class))
                .build();

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(prompt))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("Create prompt with project_name of existing project resolves project_id")
    void createPromptWithExistingProjectName() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

        var prompt = buildPrompt()
                .projectName(projectName)
                .lastUpdatedBy(USER)
                .createdBy(USER)
                .template(null)
                .versionCount(0L)
                .templateStructure(TemplateStructure.TEXT)
                .build();

        var id = createPrompt(prompt, apiKey, workspaceName);
        var fetchedPrompt = promptResourceClient.getPrompt(id, apiKey, workspaceName);

        assertPrompt(fetchedPrompt, prompt.toBuilder().id(id).projectId(projectId).build());
    }

    @Test
    @DisplayName("Create prompt with project_name of non-existing project creates project and resolves project_id")
    void createPromptWithNonExistingProjectName() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String projectName = "new-project-" + UUID.randomUUID();

        var prompt = buildPrompt()
                .projectName(projectName)
                .lastUpdatedBy(USER)
                .createdBy(USER)
                .template(null)
                .versionCount(0L)
                .templateStructure(TemplateStructure.TEXT)
                .build();

        var id = createPrompt(prompt, apiKey, workspaceName);
        var fetchedPrompt = promptResourceClient.getPrompt(id, apiKey, workspaceName);

        // Verify the project was created and the projectId was resolved
        assertThat(fetchedPrompt.projectId()).isNotNull();

        assertPrompt(fetchedPrompt, prompt.toBuilder().id(id).projectId(fetchedPrompt.projectId()).build());
    }

    @Test
    @DisplayName("Find prompts filtered by project_id returns only project prompts")
    void findPromptsByProjectId() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);
        var otherProjectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        var projectPrompt = buildPrompt()
                .projectId(projectId)
                .lastUpdatedBy(USER)
                .createdBy(USER)
                .template(null)
                .versionCount(0L)
                .templateStructure(TemplateStructure.TEXT)
                .build();
        createPrompt(projectPrompt, apiKey, workspaceName);

        var otherProjectPrompt = buildPrompt()
                .projectId(otherProjectId)
                .lastUpdatedBy(USER)
                .createdBy(USER)
                .template(null)
                .versionCount(0L)
                .templateStructure(TemplateStructure.TEXT)
                .build();
        createPrompt(otherProjectPrompt, apiKey, workspaceName);

        var workspacePrompt = buildPrompt()
                .projectId(null)
                .lastUpdatedBy(USER)
                .createdBy(USER)
                .template(null)
                .versionCount(0L)
                .templateStructure(TemplateStructure.TEXT)
                .build();
        createPrompt(workspacePrompt, apiKey, workspaceName);

        List<Prompt> expectedPrompts = List.of(projectPrompt);
        findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null, null,
                null, projectId);
    }
}
