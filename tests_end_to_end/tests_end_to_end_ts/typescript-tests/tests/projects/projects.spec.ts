import { test, expect } from '../../fixtures/projects.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';

test.describe('Projects CRUD Tests', () => {
  test.describe('with API-created projects', () => {
    test('Projects created via SDK are visible in both UI and SDK @sanity @happypaths @fullregression @projects', async ({ page, helperClient, createProjectApi }) => {
      await test.step('Verify project is retrievable via SDK', async () => {
        await helperClient.waitForProjectVisible(createProjectApi, 10);
        const projects = await helperClient.findProject(createProjectApi);

        expect(projects.length).toBeGreaterThan(0);
        expect(projects[0].name).toBe(createProjectApi);
      });

      await test.step('Verify project is visible in UI', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(createProjectApi, 5000);
      });
    });

    test('SDK-created projects can be renamed via SDK with changes reflected in UI @fullregression @projects', async ({ page, helperClient, createProjectApi }) => {
      const newName = 'updated_test_project_name';
      let nameUpdated = false;
      let projectId: string | undefined;

      try {
        await test.step('Update project name via SDK', async () => {
          const updatedProject = await helperClient.updateProject(createProjectApi, newName);
          nameUpdated = true;
          projectId = updatedProject.id;
          expect(projectId).toBeDefined();
        });

        await test.step('Verify updated name is reflected in SDK', async () => {
          await helperClient.waitForProjectVisible(newName, 10);
          const projects = await helperClient.findProject(newName);
          const projectIdUpdatedName = projects[0].id;
          expect(projectIdUpdatedName).toBe(projectId);
        });

        await test.step('Verify updated name is reflected in UI', async () => {
          const projectsPage = new ProjectsPage(page);
          await projectsPage.goto();
          await projectsPage.checkProjectExistsWithRetry(newName, 5000);
          await projectsPage.checkProjectNotExists(createProjectApi);
        });
      } finally {
        if (nameUpdated) {
          await helperClient.deleteProject(newName);
        } else {
          await helperClient.deleteProject(createProjectApi);
        }
      }
    });

    test('SDK-created projects can be deleted via SDK with changes reflected in UI @fullregression @projects', async ({ page, helperClient, createProjectApi }) => {
      await test.step('Delete project via SDK', async () => {
        await helperClient.deleteProject(createProjectApi);
      });

      await test.step('Verify project is not visible in UI', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectNotExists(createProjectApi);
      });

      await test.step('Verify project is not retrievable via SDK', async () => {
        const projectsFound = await helperClient.findProject(createProjectApi);
        expect(projectsFound.length).toBe(0);
      });
    });

    test('SDK-created projects can be deleted via UI with changes reflected in SDK @happypaths @fullregression @projects', async ({ page, helperClient, createProjectApi }) => {
      await test.step('Delete project via UI', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.deleteProjectByName(createProjectApi);
      });

      await test.step('Verify project is not retrievable via SDK', async () => {
        const projectsFound = await helperClient.findProject(createProjectApi);
        expect(projectsFound.length).toBe(0);
      });
    });
  });

  test.describe('with UI-created projects', () => {
    test('Projects created via UI are visible in both UI and SDK @sanity @happypaths @fullregression @projects', async ({ page, helperClient, createProjectUi }) => {
      await test.step('Verify project is retrievable via SDK', async () => {
        await helperClient.waitForProjectVisible(createProjectUi, 10);
        const projects = await helperClient.findProject(createProjectUi);

        expect(projects.length).toBeGreaterThan(0);
        expect(projects[0].name).toBe(createProjectUi);
      });

      await test.step('Verify project is visible in UI', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(createProjectUi, 5000);
      });
    });

    test('UI-created projects can be renamed via SDK with changes reflected in UI @fullregression @projects', async ({ page, helperClient, createProjectUi }) => {
      const newName = 'updated_test_project_name';
      let nameUpdated = false;
      let projectId: string | undefined;

      try {
        await test.step('Update project name via SDK', async () => {
          const updatedProject = await helperClient.updateProject(createProjectUi, newName);
          nameUpdated = true;
          projectId = updatedProject.id;
          expect(projectId).toBeDefined();
        });

        await test.step('Verify updated name is reflected in SDK', async () => {
          await helperClient.waitForProjectVisible(newName, 10);
          const projects = await helperClient.findProject(newName);
          const projectIdUpdatedName = projects[0].id;
          expect(projectIdUpdatedName).toBe(projectId);
        });

        await test.step('Verify updated name is reflected in UI', async () => {
          const projectsPage = new ProjectsPage(page);
          await projectsPage.goto();
          await projectsPage.checkProjectExistsWithRetry(newName, 5000);
          await projectsPage.checkProjectNotExists(createProjectUi);
        });
      } finally {
        if (nameUpdated) {
          await helperClient.deleteProject(newName);
        } else {
          await helperClient.deleteProject(createProjectUi);
        }
      }
    });

    test('UI-created projects can be deleted via SDK with changes reflected in UI @fullregression @projects', async ({ page, helperClient, createProjectUi }) => {
      await test.step('Delete project via SDK', async () => {
        await helperClient.deleteProject(createProjectUi);
      });

      await test.step('Verify project is not visible in UI', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectNotExists(createProjectUi);
      });

      await test.step('Verify project is not retrievable via SDK', async () => {
        const projectsFound = await helperClient.findProject(createProjectUi);
        expect(projectsFound.length).toBe(0);
      });
    });

    test('UI-created projects can be deleted via UI with changes reflected in SDK @happypaths @fullregression @projects', async ({ page, helperClient, createProjectUi }) => {
      await test.step('Delete project via UI', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.deleteProjectByName(createProjectUi);
      });

      await test.step('Verify project is not retrievable via SDK', async () => {
        const projectsFound = await helperClient.findProject(createProjectUi);
        expect(projectsFound.length).toBe(0);
      });
    });
  });
});
