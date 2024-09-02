import { expect, test } from "@e2e/fixtures";

test.describe("Create project", () => {
  test("Create a new project", async ({
    project,
    projectsPage,
    tracesPage,
  }) => {
    await projectsPage.goto();
    await expect(projectsPage.title).toBeVisible();

    await projectsPage.goToProject(project.name);
    await expect(tracesPage.title).toBeVisible();
  });
});
