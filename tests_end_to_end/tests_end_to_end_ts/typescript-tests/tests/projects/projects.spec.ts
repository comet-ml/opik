import { test, expect } from '../../fixtures/projects.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';

test.describe('Projects CRUD Tests', () => {
  test('should verify project visibility created via API', async ({
    page,
    helperClient,
    createProjectApi,
  }) => {
    const projectName = createProjectApi;

    await helperClient.waitForProjectVisible(projectName, 10);
    const projects = await helperClient.findProject(projectName);

    expect(projects.length).toBeGreaterThan(0);
    expect(projects[0].name).toBe(projectName);

    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
  });

  test('should verify project visibility created via UI', async ({
    page,
    helperClient,
    createProjectUi,
  }) => {
    const projectName = createProjectUi;

    await helperClient.waitForProjectVisible(projectName, 10);
    const projects = await helperClient.findProject(projectName);

    expect(projects.length).toBeGreaterThan(0);
    expect(projects[0].name).toBe(projectName);

    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
  });

  test('should update project name via API', async ({
    page,
    helperClient,
    createProjectApi,
  }) => {
    const projectName = createProjectApi;
    const newName = 'updated_test_project_name';

    let nameUpdated = false;

    try {
      const updatedProject = await helperClient.updateProject(projectName, newName);
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
      await projectsPage.checkProjectNotExists(projectName);
    } finally {
      if (nameUpdated) {
        await helperClient.deleteProject(newName);
      } else {
        await helperClient.deleteProject(projectName);
      }
    }
  });

  test('should update project name via API for UI-created project', async ({
    page,
    helperClient,
    createProjectUi,
  }) => {
    const projectName = createProjectUi;
    const newName = 'updated_test_project_name';

    let nameUpdated = false;

    try {
      const updatedProject = await helperClient.updateProject(projectName, newName);
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
      await projectsPage.checkProjectNotExists(projectName);
    } finally {
      if (nameUpdated) {
        await helperClient.deleteProject(newName);
      } else {
        await helperClient.deleteProject(projectName);
      }
    }
  });

  test('should delete project via SDK created via API', async ({
    page,
    helperClient,
    createProjectApi,
  }) => {
    const projectName = createProjectApi;

    await helperClient.deleteProject(projectName);

    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await projectsPage.checkProjectNotExists(projectName);

    const projectsFound = await helperClient.findProject(projectName);
    expect(projectsFound.length).toBe(0);
  });

  test('should delete project via SDK created via UI', async ({
    page,
    helperClient,
    createProjectUi,
  }) => {
    const projectName = createProjectUi;

    await helperClient.deleteProject(projectName);

    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await projectsPage.checkProjectNotExists(projectName);

    const projectsFound = await helperClient.findProject(projectName);
    expect(projectsFound.length).toBe(0);
  });

  test('should delete project via UI created via API', async ({
    page,
    helperClient,
    createProjectApi,
  }) => {
    const projectName = createProjectApi;

    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await projectsPage.deleteProjectByName(projectName);

    const projectsFound = await helperClient.findProject(projectName);
    expect(projectsFound.length).toBe(0);
  });

  test('should delete project via UI created via UI', async ({
    page,
    helperClient,
    createProjectUi,
  }) => {
    const projectName = createProjectUi;

    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await projectsPage.deleteProjectByName(projectName);

    const projectsFound = await helperClient.findProject(projectName);
    expect(projectsFound.length).toBe(0);
  });
});
