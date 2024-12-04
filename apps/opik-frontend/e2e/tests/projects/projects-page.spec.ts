import { expect, test } from "@e2e/fixtures";
import { PROJECT_NAME, PROJECT_NAME_DEFAULT } from "@e2e/test-data";

test.describe("Projects page", () => {
  test("Check search", async ({ projectsPage, project }) => {
    await projectsPage.goto();
    await expect(projectsPage.title).toBeVisible();

    // check if Default project exists
    await projectsPage.table.checkIsExist(PROJECT_NAME_DEFAULT);

    await projectsPage.table.hasRowCount(2);

    await projectsPage.search.search(project.name);
    await projectsPage.table.hasRowCount(1);

    await projectsPage.search.search("e");
    await projectsPage.table.hasRowCount(2);

    await projectsPage.search.search("invalid_search_string");
    await projectsPage.table.hasNoData();
  });

  test("Check moving to trace page", async ({
    project,
    projectsPage,
    tracesPage,
  }) => {
    await projectsPage.goto();
    await projectsPage.goToProject(project.name);
    await expect(tracesPage.title).toBeVisible();
  });

  test("Check adding/deleting of projects", async ({ projectsPage }) => {
    await projectsPage.goto();

    await projectsPage.addProject(PROJECT_NAME);
    await projectsPage.table.checkIsExist(PROJECT_NAME);

    await projectsPage.deleteProject(PROJECT_NAME);
    await projectsPage.table.checkIsNotExist(PROJECT_NAME);
  });
});
