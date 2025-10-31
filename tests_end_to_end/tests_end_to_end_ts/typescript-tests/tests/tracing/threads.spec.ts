import { test, expect } from '../../fixtures/tracing.fixture';
import type { Page } from '@playwright/test';
import { ProjectsPage } from '../../page-objects/projects.page';
import { ThreadsPage } from '../../page-objects/threads.page';
import type { ThreadConfig } from '../../helpers/test-helper-client';

test.describe('Threads (Conversations) Tests', () => {
  test.describe('Thread creation and verification', () => {
    const verifyThreads = async (page: Page, projectName: string, threadConfigs: ThreadConfig[]) => {
      await test.step('Navigate to project and switch to threads page', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
        await projectsPage.clickProject(projectName);

        const threadsPage = new ThreadsPage(page);
        await threadsPage.switchToPage();
      });

      await test.step('Verify expected number of threads are visible', async () => {
        const threadsPage = new ThreadsPage(page);
        const numberOfThreads = await threadsPage.getNumberOfThreadsOnPage();
        expect(numberOfThreads).toBe(3);
      });

      await test.step('Verify thread content and message order for each thread', async () => {
        const threadsPage = new ThreadsPage(page);
        for (const thread of threadConfigs) {
          await threadsPage.openThreadContent(thread.thread_id);

          for (let i = 0; i < thread.inputs.length; i++) {
            await threadsPage.checkMessageInThread(thread.inputs[i], false);
            await threadsPage.checkMessageInThread(thread.outputs[i], true);
          }

          await threadsPage.closeThreadContent();
        }
      });
    };

    test('Threads created via @track decorator are correctly displayed with proper message ordering @sanity @happypaths @fullregression @tracing @threads', async ({
      page,
      projectName,
      createThreadsDecorator,
    }) => {
      await verifyThreads(page, projectName, createThreadsDecorator);
    });

    test('Threads created via low-level client API are correctly displayed with proper message ordering @fullregression @tracing @threads', async ({
      page,
      projectName,
      createThreadsClient,
    }) => {
      await verifyThreads(page, projectName, createThreadsClient);
    });
  });

  test.describe('Thread removal', () => {
    test('Threads can be deleted via UI and removal is properly reflected @fullregression @tracing @threads', async ({
      page,
      projectName,
      createThreadsClient,
    }) => {
      const threadConfigs = createThreadsClient;

      await test.step('Navigate to project and switch to threads page', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
        await projectsPage.clickProject(projectName);

        const threadsPage = new ThreadsPage(page);
        await threadsPage.switchToPage();
      });

      await test.step('Verify initial thread count', async () => {
        const threadsPage = new ThreadsPage(page);
        let numberOfThreads = await threadsPage.getNumberOfThreadsOnPage();
        expect(numberOfThreads).toBe(3);
      });

      await test.step('Delete threads via UI and verify removal', async () => {
        const threadsPage = new ThreadsPage(page);
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
});
