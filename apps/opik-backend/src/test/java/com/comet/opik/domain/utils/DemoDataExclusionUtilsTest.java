package com.comet.opik.domain.utils;

import com.comet.opik.api.BiInformationResponse.BiInformation;
import com.comet.opik.api.UsageByWorkspaceProjectUserResponse.WorkspaceProjectUserCount;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.utils.DemoDataExclusionUtils.WorkspaceProjectCount;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exclusion moved out of the usage SQL and into these folds, so what the query text used to guarantee is now
 * their job: demo projects contribute nothing, and everything else aggregates back to exactly the per-workspace and
 * per-workspace-user totals the previous queries returned.
 */
class DemoDataExclusionUtilsTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String OTHER_USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    @Nested
    class FoldByWorkspace {

        @Test
        void foldByWorkspace__whenAWorkspaceSpansSeveralProjects__thenSumsThemIntoOneEntry() {
            var firstCount = randomCount();
            var secondCount = randomCount();
            var otherWorkspaceCount = randomCount();
            var rows = List.of(
                    WorkspaceProjectCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .count(firstCount)
                            .build(),
                    WorkspaceProjectCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .count(secondCount)
                            .build(),
                    WorkspaceProjectCount.builder()
                            .workspaceId(OTHER_WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .count(otherWorkspaceCount)
                            .build());
            var expectedCounts = Map.of(
                    WORKSPACE_ID, firstCount + secondCount,
                    OTHER_WORKSPACE_ID, otherWorkspaceCount);

            var actualCounts = DemoDataExclusionUtils.foldByWorkspace(rows, Set.of());

            assertThat(actualCounts).isEqualTo(expectedCounts);
        }

        @Test
        void foldByWorkspace__whenAWorkspaceHasDemoProjects__thenOnlyTheirCountsAreDropped() {
            var regularCount = randomCount();
            var demoProjectId = ID_GENERATOR.generateId();
            var rows = List.of(
                    WorkspaceProjectCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .count(regularCount)
                            .build(),
                    WorkspaceProjectCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(demoProjectId)
                            .count(randomCount())
                            .build());
            var expectedCounts = Map.of(WORKSPACE_ID, regularCount);

            var actualCounts = DemoDataExclusionUtils.foldByWorkspace(rows, Set.of(demoProjectId));

            assertThat(actualCounts).isEqualTo(expectedCounts);
        }

        @Test
        void foldByWorkspace__whenAWorkspaceOnlyHasDemoProjects__thenItIsOmitted() {
            var demoProjectId = ID_GENERATOR.generateId();
            var rows = List.of(WorkspaceProjectCount.builder()
                    .workspaceId(WORKSPACE_ID)
                    .projectId(demoProjectId)
                    .count(randomCount())
                    .build());

            var actualCounts = DemoDataExclusionUtils.foldByWorkspace(rows, Set.of(demoProjectId));

            assertThat(actualCounts).isEmpty();
        }

        @Test
        void foldByWorkspace__whenFolding__thenWorkspacesKeepTheOrderTheirRowsArrivedIn() {
            var rows = List.of(
                    WorkspaceProjectCount.builder()
                            .workspaceId(OTHER_WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .count(randomCount())
                            .build(),
                    WorkspaceProjectCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .count(randomCount())
                            .build(),
                    WorkspaceProjectCount.builder()
                            .workspaceId(OTHER_WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .count(randomCount())
                            .build());

            var actualWorkspaces = DemoDataExclusionUtils.foldByWorkspace(rows, Set.of()).keySet();

            assertThat(actualWorkspaces).containsExactly(OTHER_WORKSPACE_ID, WORKSPACE_ID);
        }

        /**
         * The lookup producing the demo set is scoped to the projects present in the rows, so ids for projects with
         * no activity are expected to be absent rather than exceptional.
         */
        @Test
        void foldByWorkspace__whenADemoProjectHadNoActivity__thenItsIdIsIgnored() {
            var regularCount = randomCount();
            var rows = List.of(WorkspaceProjectCount.builder()
                    .workspaceId(WORKSPACE_ID)
                    .projectId(ID_GENERATOR.generateId())
                    .count(regularCount)
                    .build());
            var demoProjectIds = Set.of(ID_GENERATOR.generateId());
            var expectedCounts = Map.of(WORKSPACE_ID, regularCount);

            var actualCounts = DemoDataExclusionUtils.foldByWorkspace(rows, demoProjectIds);

            assertThat(actualCounts).isEqualTo(expectedCounts);
        }

        @Test
        void foldByWorkspace__whenNoRows__thenReturnsEmpty() {
            var demoProjectIds = Set.of(ID_GENERATOR.generateId());

            var actualCounts = DemoDataExclusionUtils.foldByWorkspace(List.of(), demoProjectIds);

            assertThat(actualCounts).isEmpty();
        }
    }

    @Nested
    class FoldByWorkspaceAndUser {

        @Test
        void foldByWorkspaceAndUser__whenAUserSpansSeveralProjects__thenSumsThemKeepingUsersApart() {
            var firstCount = randomCount();
            var secondCount = randomCount();
            var otherUserCount = randomCount();
            var projectId = ID_GENERATOR.generateId();
            var rows = List.of(
                    WorkspaceProjectUserCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(projectId)
                            .user(USER)
                            .count(firstCount)
                            .build(),
                    WorkspaceProjectUserCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .user(USER)
                            .count(secondCount)
                            .build(),
                    WorkspaceProjectUserCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(projectId)
                            .user(OTHER_USER)
                            .count(otherUserCount)
                            .build());
            var expectedBiInformation = List.of(
                    BiInformation.builder()
                            .workspaceId(WORKSPACE_ID)
                            .user(USER)
                            .count(firstCount + secondCount)
                            .build(),
                    BiInformation.builder()
                            .workspaceId(WORKSPACE_ID)
                            .user(OTHER_USER)
                            .count(otherUserCount)
                            .build());

            var actualBiInformation = DemoDataExclusionUtils.foldByWorkspaceAndUser(rows, Set.of());

            assertThat(actualBiInformation).isEqualTo(expectedBiInformation);
        }

        @Test
        void foldByWorkspaceAndUser__whenTheSameUserIsInSeveralWorkspaces__thenTheyStayApart() {
            var count = randomCount();
            var otherWorkspaceCount = randomCount();
            var rows = List.of(
                    WorkspaceProjectUserCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .user(USER)
                            .count(count)
                            .build(),
                    WorkspaceProjectUserCount.builder()
                            .workspaceId(OTHER_WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .user(USER)
                            .count(otherWorkspaceCount)
                            .build());
            var expectedBiInformation = List.of(
                    BiInformation.builder()
                            .workspaceId(WORKSPACE_ID)
                            .user(USER)
                            .count(count)
                            .build(),
                    BiInformation.builder()
                            .workspaceId(OTHER_WORKSPACE_ID)
                            .user(USER)
                            .count(otherWorkspaceCount)
                            .build());

            var actualBiInformation = DemoDataExclusionUtils.foldByWorkspaceAndUser(rows, Set.of());

            assertThat(actualBiInformation).isEqualTo(expectedBiInformation);
        }

        @Test
        void foldByWorkspaceAndUser__whenAUserHasDemoProjects__thenOnlyTheirCountsAreDropped() {
            var regularCount = randomCount();
            var demoProjectId = ID_GENERATOR.generateId();
            var rows = List.of(
                    WorkspaceProjectUserCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(ID_GENERATOR.generateId())
                            .user(USER)
                            .count(regularCount)
                            .build(),
                    WorkspaceProjectUserCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(demoProjectId)
                            .user(USER)
                            .count(randomCount())
                            .build(),
                    WorkspaceProjectUserCount.builder()
                            .workspaceId(WORKSPACE_ID)
                            .projectId(demoProjectId)
                            .user(OTHER_USER)
                            .count(randomCount())
                            .build());
            var expectedBiInformation = List.of(BiInformation.builder()
                    .workspaceId(WORKSPACE_ID)
                    .user(USER)
                    .count(regularCount)
                    .build());

            var actualBiInformation = DemoDataExclusionUtils.foldByWorkspaceAndUser(rows, Set.of(demoProjectId));

            assertThat(actualBiInformation).isEqualTo(expectedBiInformation);
        }

        @Test
        void foldByWorkspaceAndUser__whenNoRows__thenReturnsEmpty() {
            var demoProjectIds = Set.of(ID_GENERATOR.generateId());

            var actualBiInformation = DemoDataExclusionUtils.foldByWorkspaceAndUser(List.of(), demoProjectIds);

            assertThat(actualBiInformation).isEmpty();
        }
    }

    /** Still used by the span usage queries, which carry the exclusion in SQL. */
    @Nested
    class CalculateDemoDataCreatedAt {

        @Test
        void calculateDemoDataCreatedAt__whenDemoProjectsExist__thenReturnsTheLatestCreationPlusAMinute() {
            var latest = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var excludedProjectIds = Map.of(
                    ID_GENERATOR.generateId(), latest.minus(2, ChronoUnit.DAYS),
                    ID_GENERATOR.generateId(), latest);
            var expectedCutoff = latest.plus(1, ChronoUnit.MINUTES);

            var actualCutoff = DemoDataExclusionUtils.calculateDemoDataCreatedAt(excludedProjectIds);

            assertThat(actualCutoff).contains(expectedCutoff);
        }

        @Test
        void calculateDemoDataCreatedAt__whenNoDemoProjects__thenReturnsEmpty() {
            var actualCutoff = DemoDataExclusionUtils.calculateDemoDataCreatedAt(Map.of());

            assertThat(actualCutoff).isEmpty();
        }
    }

    private long randomCount() {
        return RandomUtils.secure().randomLong(1, 1_000);
    }
}
