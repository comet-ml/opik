import { test as base, BaseFixtures } from './base.fixture';
import { ProjectsPage } from '../page-objects/projects.page';

export type ProjectsFixtures = {
  createProjectApi: string;
  createProjectUi: string;
  projectsPage: ProjectsPage;
};

export const test = base.extend<BaseFixtures & ProjectsFixtures>({
  createProjectApi: async ({ helperClient, projectName }, use) => {
    const existingProjects = await helperClient.findProject(projectName);
    if (existingProjects.length > 0) {
      await helperClient.deleteProject(projectName);
    }

    await helperClient.createProject(projectName);
    await use(projectName);

    try {
      const projects = await helperClient.findProject(projectName);
      if (projects.length > 0) {
        await helperClient.deleteProject(projectName);
      }
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },

  createProjectUi: async ({ page, helperClient, projectName }, use) => {
    const existingProjects = await helperClient.findProject(projectName);
    if (existingProjects.length > 0) {
      await helperClient.deleteProject(projectName);
    }

    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await projectsPage.createNewProject(projectName);
    await use(projectName);

    try {
      const projectsToCleanup = await helperClient.findProject(projectName);
      if (projectsToCleanup.length > 0) {
        await projectsPage.goto();
        await projectsPage.deleteProjectByName(projectName);
        await helperClient.waitForProjectDeleted(projectName);
      }
    } catch (error) {
      console.warn(`Failed to cleanup project ${projectName}:`, error);
    }
  },

  projectsPage: async ({ page }, use) => {
    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await use(projectsPage);
  },
});

export { expect } from '@playwright/test';
