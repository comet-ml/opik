import { test, expect } from '../../fixtures/tracing.fixture';
import type { Page } from '@playwright/test';
import { ProjectsPage } from '../../page-objects/projects.page';
import { ThreadsPage } from '../../page-objects/threads.page';
import type { ThreadConfig } from '../../helpers/test-helper-client';

test.describe('Threads (Conversations) Tests', () => {
  test.describe('Thread creation and verification', () => {
    const verifyThreads = async (page: Page, projectName: string, threadConfigs: ThreadConfig[]) => {
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);

      const threadsPage = new ThreadsPage(page);
      await threadsPage.switchToPage();

      const numberOfThreads = await threadsPage.getNumberOfThreadsOnPage();
      expect(numberOfThreads).toBe(3);

      for (const thread of threadConfigs) {
        await threadsPage.openThreadContent(thread.thread_id);

        for (let i = 0; i < thread.inputs.length; i++) {
          await threadsPage.checkMessageInThread(thread.inputs[i], false);
          await threadsPage.checkMessageInThread(thread.outputs[i], true);
        }

        await threadsPage.closeThreadContent();
      }
    };

    test('should verify threads created via decorator', async ({
      page,
      projectName,
      createThreadsDecorator,
    }) => {
      await verifyThreads(page, projectName, createThreadsDecorator);
    });

    test('should verify threads created via low-level client', async ({
      page,
      projectName,
      createThreadsClient,
    }) => {
      await verifyThreads(page, projectName, createThreadsClient);
    });
  });

  test.describe('Thread removal', () => {
    test('should remove threads via UI actions', async ({
      page,
      projectName,
      createThreadsClient,
    }) => {
      const threadConfigs = createThreadsClient;

      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);

      const threadsPage = new ThreadsPage(page);
      await threadsPage.switchToPage();

      let numberOfThreads = await threadsPage.getNumberOfThreadsOnPage();
      expect(numberOfThreads).toBe(3);

      for (let i = 0; i < 2; i++) {
        const threadId = threadConfigs[i].thread_id;
        await threadsPage.searchForThread(threadId);
        await threadsPage.deleteThreadFromTable();
        await threadsPage.checkThreadIsDeleted(threadId);

        await page.getByTestId('search-input').clear();
        await page.waitForTimeout(500);
      }
    });
  });
});
