package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.PromptField;
import com.comet.opik.api.filter.PromptFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptVersionResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DESCRIPTION;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Find Project Prompts")
@ExtendWith(DropwizardAppExtensionProvider.class)
class PromptResourceFindProjectPromptsTest {

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
    private PromptVersionResourceClient promptVersionResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.promptResourceClient = new PromptResourceClient(client, baseURI, factory);
        this.promptVersionResourceClient = new PromptVersionResourceClient(client, baseURI);
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

    private CreatePromptVersion createPromptVersionRequest(String name, PromptVersion version,
            TemplateStructure templateStructure) {
        return CreatePromptVersion.builder()
                .name(name)
                .version(version)
                .templateStructure(templateStructure)
                .build();
    }

    private PromptVersion createPromptVersion(CreatePromptVersion promptVersion, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(promptVersion))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            return response.readEntity(PromptVersion.class);
        }
    }

    private void findProjectPromptsAndAssertPage(List<Prompt> expectedPrompts, String apiKey, String workspaceName,
            int expectedTotal, int page, String nameSearch, List<SortingField> sortingFields,
            List<PromptFilter> filters, UUID projectId) {
        var promptPage = promptResourceClient.getProjectPrompts(projectId, page, nameSearch, sortingFields, filters,
                apiKey, workspaceName);
        assertPromptsPage(promptPage, expectedPrompts, expectedTotal, page);
    }

    private void assertPromptsPage(Prompt.PromptPage promptPage, List<Prompt> expectedPrompts, int expectedTotal,
            int page) {
        assertThat(promptPage.total()).isEqualTo(expectedTotal);
        assertThat(promptPage.content()).hasSize(expectedPrompts.size());
        assertThat(promptPage.page()).isEqualTo(page);
        assertThat(promptPage.size()).isEqualTo(expectedPrompts.size());

        assertSortableFields(promptPage);

        assertThat(promptPage.content())
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withIgnoredFields(PROMPT_IGNORED_FIELDS)
                                .withComparatorForType(
                                        PromptResourceFindProjectPromptsTest::comparatorForCreateAtAndUpdatedAt,
                                        Instant.class)
                                .build())
                .isEqualTo(expectedPrompts);
    }

    private static void assertSortableFields(Prompt.PromptPage promptPage) {
        assertThat(promptPage.sortableBy()).contains(
                ID,
                NAME,
                DESCRIPTION,
                CREATED_AT,
                LAST_UPDATED_AT,
                CREATED_BY,
                LAST_UPDATED_BY,
                TAGS);
    }

    public static int comparatorForCreateAtAndUpdatedAt(Instant actual, Instant expected) {
        var now = Instant.now();

        if (actual.isAfter(now) || actual.equals(now))
            return 1;
        if (actual.isBefore(expected))
            return -1;

        Assertions.assertThat(actual).isBetween(expected, now);
        return 0;
    }

    @Test
    @DisplayName("Success: should find prompt")
    void shouldFindPrompt() {

        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var prompt = buildPrompt()
                .projectId(projectId)
                .lastUpdatedBy(USER)
                .createdBy(USER)
                .versionCount(1L)
                .build();

        createPrompt(prompt, apiKey, workspaceName);

        List<Prompt> expectedPrompts = List.of(prompt);

        findProjectPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null,
                null, null, projectId);
    }

    @Test
    @DisplayName("when search by name, then return prompt matching name")
    void when__searchByName__thenReturnPromptMatchingName() {

        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var prompt = buildPrompt()
                .projectId(projectId)
                .lastUpdatedBy(USER)
                .createdBy(USER)
                .versionCount(1L)
                .build();

        createPrompt(prompt, apiKey, workspaceName);

        List<Prompt> expectedPrompts = List.of(prompt);

        findProjectPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1,
                prompt.name(), null, null, projectId);
    }

    @Test
    @DisplayName("when search by name with mismatched partial name, then return empty page")
    void when__searchByNameWithMismatchedPartialName__thenReturnEmptyPage() {

        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        String name = RandomStringUtils.randomAlphanumeric(10);

        String partialSearch = name.substring(0, 5) + "@" + RandomStringUtils.randomAlphanumeric(2);

        var prompt = buildPrompt()
                .name(name)
                .projectId(projectId)
                .lastUpdatedBy(USER)
                .createdBy(USER)
                .versionCount(1L)
                .build();

        createPrompt(prompt, apiKey, workspaceName);

        List<Prompt> expectedPrompts = List.of();

        findProjectPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1,
                partialSearch, null, null, projectId);
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("when search by partial name, then return prompt matching name")
    void when__searchByPartialName__thenReturnPromptMatchingName(String promptName, String partialSearch) {

        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        IntStream.range(0, 4).forEach(i -> {
            var prompt = buildPrompt()
                    .projectId(projectId)
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .versionCount(0L)
                    .template(null)
                    .build();

            Prompt updatedPrompt = prompt.toBuilder()
                    .name(prompt.name().replaceAll("(?i)" + partialSearch, ""))
                    .build();

            createPrompt(updatedPrompt, apiKey, workspaceName);

        });

        var prompt = buildPrompt()
                .name(promptName)
                .projectId(projectId)
                .lastUpdatedBy(USER)
                .createdBy(USER)
                .versionCount(1L)
                .build();

        createPrompt(prompt, apiKey, workspaceName);

        List<Prompt> expectedPrompts = List.of(prompt);
        findProjectPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1,
                partialSearch, null, null, projectId);
    }

    static Stream<Arguments> when__searchByPartialName__thenReturnPromptMatchingName() {
        return Stream.of(
                arguments("prompt", "pro"),
                arguments("prompt", "pt"),
                arguments("prompt", "om"));
    }

    @Test
    @DisplayName("when fetch prompts, then return prompts sorted by creation time")
    void when__fetchPrompts__thenReturnPromptsSortedByCreationTime() {

        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var prompts = PromptResourceClient.buildPromptList(factory).stream()
                .map(prompt -> prompt.toBuilder()
                        .projectId(projectId)
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .versionCount(0L)
                        .template(null)
                        .build())
                .toList();

        prompts.forEach(prompt -> createPrompt(prompt, apiKey, workspaceName));

        List<Prompt> expectedPrompts = prompts.reversed();

        findProjectPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null,
                null, null, projectId);
    }

    @Test
    @DisplayName("when fetch prompts using pagination, then return prompts paginated")
    void when__fetchPromptsUsingPagination__thenReturnPromptsPaginated() {

        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var prompts = IntStream.range(0, 20)
                .mapToObj(i -> buildPrompt()
                        .projectId(projectId)
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .versionCount(1L)
                        .build())
                .toList();

        prompts.forEach(prompt -> createPrompt(prompt, apiKey, workspaceName));

        List<Prompt> promptPage1 = prompts.reversed().subList(0, 10);
        List<Prompt> promptPage2 = prompts.reversed().subList(10, 20);

        findProjectPromptsAndAssertPage(promptPage1, apiKey, workspaceName, prompts.size(), 1, null, null, null,
                projectId);
        findProjectPromptsAndAssertPage(promptPage2, apiKey, workspaceName, prompts.size(), 2, null, null, null,
                projectId);
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("when sorting prompts by valid fields, then return sorted prompts")
    void getPrompts__whenSortingByValidFields__thenReturnTracePromptsSorted(Comparator<Prompt> comparator,
            SortingField sorting) {

        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var random = new Random();

        var prompts = PromptResourceClient.buildPromptList(factory).stream()
                .map(prompt -> prompt.toBuilder()
                        .projectId(projectId)
                        // Only alphanumeric to avoid flakiness with special characters when sorting by name
                        .name(RandomStringUtils.secure().nextAlphanumeric(10))
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .versionCount(random.nextLong(5))
                        .template(null)
                        .templateStructure(TemplateStructure.TEXT)
                        .build())
                .toList();

        prompts.forEach(prompt -> {
            createPrompt(prompt, apiKey, workspaceName);
            for (int i = 0; i < prompt.versionCount(); i++) {
                var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                        .createdBy(USER)
                        .build();
                var request = createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure());
                createPromptVersion(request, apiKey, workspaceName);
            }
        });

        List<Prompt> expectedPrompts = prompts.stream().sorted(comparator).toList();

        findProjectPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null,
                List.of(sorting), null, projectId);
    }

    static Stream<Arguments> getPrompts__whenSortingByValidFields__thenReturnTracePromptsSorted() {
        // Comparators for all sortable fields
        Comparator<Prompt> idComparator = Comparator.comparing(Prompt::id);
        Comparator<Prompt> nameComparator = Comparator.comparing(Prompt::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<Prompt> descriptionComparator = Comparator.comparing(
                prompt -> prompt.description() != null ? prompt.description().toLowerCase() : "",
                String.CASE_INSENSITIVE_ORDER);
        Comparator<Prompt> createdAtComparator = Comparator.comparing(Prompt::createdAt);
        Comparator<Prompt> lastUpdatedAtComparator = Comparator.comparing(Prompt::lastUpdatedAt);
        Comparator<Prompt> createdByComparator = Comparator.comparing(Prompt::createdBy,
                String.CASE_INSENSITIVE_ORDER);
        Comparator<Prompt> lastUpdatedByComparator = Comparator.comparing(Prompt::lastUpdatedBy,
                String.CASE_INSENSITIVE_ORDER);
        Comparator<Prompt> tagsComparator = Comparator.comparing(prompt -> prompt.tags().toString().toLowerCase());
        Comparator<Prompt> versionCountComparator = Comparator.comparing(Prompt::versionCount);

        Comparator<Prompt> idComparatorReversed = Comparator.comparing(Prompt::id).reversed();

        return Stream.of(
                // ID field sorting
                Arguments.of(
                        idComparator,
                        SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                Arguments.of(
                        idComparator.reversed(),
                        SortingField.builder().field(SortableFields.ID).direction(Direction.DESC).build()),

                // NAME field sorting
                Arguments.of(
                        nameComparator,
                        SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                Arguments.of(
                        nameComparator.reversed(),
                        SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),

                // DESCRIPTION field sorting
                Arguments.of(
                        descriptionComparator,
                        SortingField.builder().field(SortableFields.DESCRIPTION).direction(Direction.ASC).build()),
                Arguments.of(
                        descriptionComparator.reversed(),
                        SortingField.builder().field(SortableFields.DESCRIPTION).direction(Direction.DESC).build()),

                // CREATED_AT field sorting
                Arguments.of(
                        createdAtComparator,
                        SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC).build()),
                Arguments.of(
                        createdAtComparator.reversed(),
                        SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()),

                // LAST_UPDATED_AT field sorting
                Arguments.of(
                        lastUpdatedAtComparator,
                        SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.ASC)
                                .build()),
                Arguments.of(
                        lastUpdatedAtComparator.reversed(),
                        SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.DESC)
                                .build()),

                // CREATED_BY field sorting
                Arguments.of(
                        createdByComparator.thenComparing(Prompt::lastUpdatedAt).reversed(),
                        SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.ASC).build()),
                Arguments.of(
                        createdByComparator.reversed().thenComparing(Prompt::lastUpdatedAt).reversed(),
                        SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.DESC).build()),

                // LAST_UPDATED_BY field sorting
                Arguments.of(
                        lastUpdatedByComparator.thenComparing(Prompt::lastUpdatedAt).reversed(),
                        SortingField.builder().field(SortableFields.LAST_UPDATED_BY).direction(Direction.ASC)
                                .build()),
                Arguments.of(
                        lastUpdatedByComparator.reversed().thenComparing(Prompt::lastUpdatedAt).reversed(),
                        SortingField.builder().field(SortableFields.LAST_UPDATED_BY).direction(Direction.DESC)
                                .build()),

                // VERSION_COUNT field sorting
                Arguments.of(
                        versionCountComparator.thenComparing(idComparatorReversed),
                        SortingField.builder().field(SortableFields.VERSION_COUNT).direction(Direction.ASC)
                                .build()),
                Arguments.of(
                        versionCountComparator.reversed().thenComparing(idComparatorReversed),
                        SortingField.builder().field(SortableFields.VERSION_COUNT).direction(Direction.DESC)
                                .build()),

                // TAGS field sorting
                Arguments.of(
                        tagsComparator,
                        SortingField.builder().field(SortableFields.TAGS).direction(Direction.ASC).build()),
                Arguments.of(
                        tagsComparator.reversed(),
                        SortingField.builder().field(SortableFields.TAGS).direction(Direction.DESC).build()));
    }

    @ParameterizedTest
    @MethodSource("getValidFilters")
    @DisplayName("when filter prompts by valid fields, then return filtered prompts")
    void whenFilterPrompts__thenReturnPromptsFiltered(Function<List<Prompt>, PromptFilter> getFilter,
            Function<List<Prompt>, List<Prompt>> getExpectedPrompts) {

        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var random = new Random();

        var prompts = PromptResourceClient.buildPromptList(factory).stream()
                .map(prompt -> prompt.toBuilder()
                        .projectId(projectId)
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .versionCount(random.nextLong(5))
                        .template(null)
                        .templateStructure(TemplateStructure.TEXT)
                        .build())
                .toList();

        prompts.forEach(prompt -> {
            createPrompt(prompt, apiKey, workspaceName);
            for (int i = 0; i < prompt.versionCount(); i++) {
                var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                        .createdBy(USER)
                        .build();
                var request = createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure());
                createPromptVersion(request, apiKey, workspaceName);
            }
        });

        List<Prompt> expectedPrompts = getExpectedPrompts.apply(prompts);
        PromptFilter filter = getFilter.apply(prompts);

        findProjectPromptsAndAssertPage(expectedPrompts.reversed(), apiKey, workspaceName, expectedPrompts.size(),
                1, null, null, List.of(filter), projectId);
    }

    static Stream<Arguments> getValidFilters() {
        Integer random = new Random().nextInt(5);
        return Stream.of(
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.TAGS)
                                .operator(Operator.CONTAINS)
                                .value(prompts.getFirst().tags().iterator().next())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> List.of(prompts.getFirst())),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.TAGS)
                                .operator(Operator.NOT_CONTAINS)
                                .value(prompts.getFirst().tags().iterator().next())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts.subList(1, prompts.size())),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.ID)
                                .operator(Operator.EQUAL)
                                .value(prompts.getFirst().id().toString())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> List.of(prompts.getFirst())),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.ID)
                                .operator(Operator.NOT_EQUAL)
                                .value(prompts.getFirst().id().toString())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts.subList(1, prompts.size())),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.NAME)
                                .operator(Operator.STARTS_WITH)
                                .value(prompts.getFirst().name().substring(0, 3))
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> List.of(prompts.getFirst())),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.NAME)
                                .operator(Operator.ENDS_WITH)
                                .value(prompts.getFirst().name().substring(3))
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> List.of(prompts.getFirst())),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.VERSION_COUNT)
                                .operator(Operator.GREATER_THAN_EQUAL)
                                .value(String.valueOf(random))
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts.stream()
                                .filter(prompt -> prompt.versionCount() >= random)
                                .toList()),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.VERSION_COUNT)
                                .operator(Operator.LESS_THAN_EQUAL)
                                .value(String.valueOf(random))
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts.stream()
                                .filter(prompt -> prompt.versionCount() <= random)
                                .toList()),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.CREATED_BY)
                                .operator(Operator.STARTS_WITH)
                                .value(USER.substring(0, 3))
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.CREATED_BY)
                                .operator(Operator.EQUAL)
                                .value(USER)
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.LAST_UPDATED_BY)
                                .operator(Operator.NOT_EQUAL)
                                .value(USER)
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> List.of()),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.LAST_UPDATED_BY)
                                .operator(Operator.CONTAINS)
                                .value(USER.substring(0, 3))
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.DESCRIPTION)
                                .operator(Operator.EQUAL)
                                .value(prompts.getFirst().description())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> List.of(prompts.getFirst())),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.DESCRIPTION)
                                .operator(Operator.NOT_EQUAL)
                                .value(prompts.getFirst().description())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts.subList(1, prompts.size())),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.CREATED_AT)
                                .operator(Operator.EQUAL)
                                .value(prompts.getFirst().createdAt().toString())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> List.of()),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.CREATED_AT)
                                .operator(Operator.NOT_EQUAL)
                                .value(Instant.now().toString())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.CREATED_AT)
                                .operator(Operator.GREATER_THAN)
                                .value(Instant.now().minus(5, ChronoUnit.SECONDS).toString())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.LAST_UPDATED_AT)
                                .operator(Operator.GREATER_THAN_EQUAL)
                                .value(Instant.now().toString())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> List.of()),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.LAST_UPDATED_AT)
                                .operator(Operator.LESS_THAN)
                                .value(Instant.now().toString())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                Arguments.of(
                        (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                .field(PromptField.LAST_UPDATED_AT)
                                .operator(Operator.LESS_THAN_EQUAL)
                                .value(Instant.now().minus(5, ChronoUnit.SECONDS).toString())
                                .build(),
                        (Function<List<Prompt>, List<Prompt>>) prompts -> List.of()));
    }
}
