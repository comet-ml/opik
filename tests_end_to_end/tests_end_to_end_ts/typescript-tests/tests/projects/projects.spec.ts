import { test, expect } from '../../fixtures/projects.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';

test.describe('Projects CRUD Tests', () => {
  test.describe('with API-created projects', () => {
    test('should verify project visibility', async ({ page, helperClient, createProjectApi }) => {
      await helperClient.waitForProjectVisible(createProjectApi, 10);
      const projects = await helperClient.findProject(createProjectApi);

      expect(projects.length).toBeGreaterThan(0);
      expect(projects[0].name).toBe(createProjectApi);

      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(createProjectApi, 5000);
    });

    test('should update project name via SDK', async ({ page, helperClient, createProjectApi }) => {
      const newName = 'updated_test_project_name';
      let nameUpdated = false;

      try {
        const updatedProject = await helperClient.updateProject(createProjectApi, newName);
        nameUpdated = true;
        const projectId = updatedProject.id;

        expect(projectId).toBeDefined();

        await helperClient.waitForProjectVisible(newName, 10);
        const projects = await helperClient.findProject(newName);
        const projectIdUpdatedName = projects[0].id;

        expect(projectIdUpdatedName).toBe(projectId);

        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(newName, 5000);
        await projectsPage.checkProjectNotExists(createProjectApi);
      } finally {
        if (nameUpdated) {
          await helperClient.deleteProject(newName);
        } else {
          await helperClient.deleteProject(createProjectApi);
        }
      }
    });

    test('should delete project via SDK', async ({ page, helperClient, createProjectApi }) => {
      await helperClient.deleteProject(createProjectApi);

      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectNotExists(createProjectApi);

      const projectsFound = await helperClient.findProject(createProjectApi);
      expect(projectsFound.length).toBe(0);
    });

    test('should delete project via UI', async ({ page, helperClient, createProjectApi }) => {
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.deleteProjectByName(createProjectApi);

      const projectsFound = await helperClient.findProject(createProjectApi);
      expect(projectsFound.length).toBe(0);
    });
  });

  test.describe('with UI-created projects', () => {
    test('should verify project visibility', async ({ page, helperClient, createProjectUi }) => {
      await helperClient.waitForProjectVisible(createProjectUi, 10);
      const projects = await helperClient.findProject(createProjectUi);

      expect(projects.length).toBeGreaterThan(0);
      expect(projects[0].name).toBe(createProjectUi);

      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(createProjectUi, 5000);
    });

    test('should update project name via SDK', async ({ page, helperClient, createProjectUi }) => {
      const newName = 'updated_test_project_name';
      let nameUpdated = false;

      try {
        const updatedProject = await helperClient.updateProject(createProjectUi, newName);
        nameUpdated = true;
        const projectId = updatedProject.id;

        expect(projectId).toBeDefined();

        await helperClient.waitForProjectVisible(newName, 10);
        const projects = await helperClient.findProject(newName);
        const projectIdUpdatedName = projects[0].id;

        expect(projectIdUpdatedName).toBe(projectId);

        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(newName, 5000);
        await projectsPage.checkProjectNotExists(createProjectUi);
      } finally {
        if (nameUpdated) {
          await helperClient.deleteProject(newName);
        } else {
          await helperClient.deleteProject(createProjectUi);
        }
      }
    });

    test('should delete project via SDK', async ({ page, helperClient, createProjectUi }) => {
      await helperClient.deleteProject(createProjectUi);

      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectNotExists(createProjectUi);

      const projectsFound = await helperClient.findProject(createProjectUi);
      expect(projectsFound.length).toBe(0);
    });

    test('should delete project via UI', async ({ page, helperClient, createProjectUi }) => {
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.deleteProjectByName(createProjectUi);

      const projectsFound = await helperClient.findProject(createProjectUi);
      expect(projectsFound.length).toBe(0);
    });
  });
});
