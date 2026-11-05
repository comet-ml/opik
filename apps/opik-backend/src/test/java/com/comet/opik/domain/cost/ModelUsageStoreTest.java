package com.comet.opik.domain.cost;

import com.comet.opik.api.modelprice.CustomModelPricesUsed;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelUsageStoreTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final UsedModel GPT_5_MINI = new UsedModel("gpt-5-mini", "openai");
    private static final UsedModel LLAMA = new UsedModel("custom-llm/local/llama3.2", "ollama:local");

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private Jdbi jdbi;

    @BeforeAll
    void setUp() {
        mysql.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        jdbi = Jdbi.create(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .installPlugin(new SqlObjectPlugin());
    }

    private void save(String organizationId, LocalDate day, String workspaceId, String workspaceName,
            UsedModel... models) {
        jdbi.useTransaction(handle -> ModelUsageStore.save(handle, organizationId, day, workspaceId, workspaceName,
                List.of(models)));
    }

    private ModelUsageStore.Found find(String organizationId) {
        return jdbi.inTransaction(handle -> ModelUsageStore.find(handle, organizationId, TODAY));
    }

    private static CustomModelPricesUsed.Workspace workspace(String id, String name) {
        return CustomModelPricesUsed.Workspace.builder().id(id).name(name).build();
    }

    @Test
    void findOfAnOrganizationWithNoRowsIsEmpty() {
        var found = find(UUID.randomUUID().toString());

        assertThat(found.usages()).isEmpty();
        assertThat(found.truncated()).isFalse();
    }

    @Test
    void findCountsTheDaysAndListsTheWorkspacesMostUsedFirst() {
        String organizationId = UUID.randomUUID().toString();
        save(organizationId, TODAY.minusDays(2), "ws-a", "team-a", GPT_5_MINI);
        save(organizationId, TODAY.minusDays(1), "ws-b", "team-b", GPT_5_MINI);
        save(organizationId, TODAY, "ws-a", "team-a", GPT_5_MINI, LLAMA);

        var found = find(organizationId);

        assertThat(found.usages()).containsExactly(
                ModelUsageStore.Usage.builder().model(GPT_5_MINI).lastSeen(TODAY).daysUsed(3)
                        .workspaces(List.of(workspace("ws-a", "team-a"), workspace("ws-b", "team-b"))).build(),
                ModelUsageStore.Usage.builder().model(LLAMA).lastSeen(TODAY).daysUsed(1)
                        .workspaces(List.of(workspace("ws-a", "team-a"))).build());
    }

    @Test
    void saveOfARowAlreadyThereKeepsOneRow() {
        String organizationId = UUID.randomUUID().toString();
        save(organizationId, TODAY, "ws-a", "team-a", GPT_5_MINI);
        save(organizationId, TODAY, "ws-a", "team-a", GPT_5_MINI);

        assertThat(rowCount(organizationId)).isEqualTo(1);
        assertThat(find(organizationId).usages()).singleElement()
                .extracting(ModelUsageStore.Usage::daysUsed).isEqualTo(1);
    }

    @Test
    void findCoversTheLastThirtyDaysOnly() {
        String organizationId = UUID.randomUUID().toString();
        save(organizationId, TODAY.minusDays(ModelUsageStore.DAYS - 1), "ws-a", "team-a", GPT_5_MINI);
        save(organizationId, TODAY.minusDays(ModelUsageStore.DAYS), "ws-a", "team-a", LLAMA);

        assertThat(find(organizationId).usages()).extracting(ModelUsageStore.Usage::model)
                .containsExactly(GPT_5_MINI);
    }

    @Test
    void deleteOldDaysKeepsTheLastThirtyOneDays() {
        String organizationId = UUID.randomUUID().toString();
        save(organizationId, TODAY.minusDays(ModelUsageStore.DAYS), "ws-a", "team-a", GPT_5_MINI);
        save(organizationId, TODAY.minusDays(ModelUsageStore.DAYS + 1), "ws-a", "team-a", LLAMA);

        int deleted = jdbi.inTransaction(handle -> ModelUsageStore.deleteOldDays(handle, organizationId, TODAY));

        assertThat(deleted).isEqualTo(1);
        assertThat(rowCount(organizationId)).isEqualTo(1);
    }

    @Test
    void findOfOneOrganizationLeavesAnotherOut() {
        String organizationId = UUID.randomUUID().toString();
        String otherOrganizationId = UUID.randomUUID().toString();
        save(organizationId, TODAY, "ws-a", "team-a", GPT_5_MINI);
        save(otherOrganizationId, TODAY, "ws-z", "team-z", LLAMA);

        assertThat(find(organizationId).usages()).extracting(ModelUsageStore.Usage::model)
                .containsExactly(GPT_5_MINI);
    }

    @Test
    void findReturnsAtMostTheCapAndSaysItIsTruncated() {
        String organizationId = UUID.randomUUID().toString();
        var models = IntStream.rangeClosed(0, ModelUsageStore.MAX_MODELS)
                .mapToObj(i -> new UsedModel("model-" + i, "openai"))
                .toArray(UsedModel[]::new);
        save(organizationId, TODAY, "ws-a", "team-a", models);

        var found = find(organizationId);

        assertThat(found.usages()).hasSize(ModelUsageStore.MAX_MODELS);
        assertThat(found.truncated()).isTrue();
    }

    @Test
    void findWorkspacesReadsOnlyTheModelsFindModelsReturns() {
        String organizationId = UUID.randomUUID().toString();
        save(organizationId, TODAY.minusDays(1), "ws-a", "team-a", GPT_5_MINI);
        save(organizationId, TODAY, "ws-a", "team-a", GPT_5_MINI);
        save(organizationId, TODAY, "ws-b", "team-b", LLAMA);

        var rows = jdbi.withExtension(ModelUsageDAO.class,
                dao -> dao.findWorkspaces(organizationId, TODAY.minusDays(ModelUsageStore.DAYS - 1), 1));

        assertThat(rows).containsExactly(new ModelUsageDAO.UsedModelWorkspaceRow(GPT_5_MINI.modelName(),
                GPT_5_MINI.provider(), "ws-a", "team-a"));
    }

    @Test
    void aRenamedWorkspaceIsListedUnderItsLatestName() {
        String organizationId = UUID.randomUUID().toString();
        save(organizationId, TODAY.minusDays(2), "ws-a", "zulu", GPT_5_MINI);
        save(organizationId, TODAY, "ws-a", "alpha", LLAMA);

        var found = find(organizationId);

        assertThat(found.usages()).extracting(ModelUsageStore.Usage::workspaces)
                .containsOnly(List.of(workspace("ws-a", "alpha")));
    }

    private int rowCount(String organizationId) {
        return jdbi.withHandle(handle -> handle.createQuery(
                "SELECT COUNT(*) FROM model_prices_used WHERE organization_id = :organizationId")
                .bind("organizationId", organizationId)
                .mapTo(Integer.class)
                .one());
    }
}
