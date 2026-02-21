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
      test.info().annotations.push({
        type: 'description',
        description: `Tests that threads (conversations) created via the @track decorator are correctly displayed with proper message ordering in the UI.

Steps:
1. Create a project and 3 threads via @track decorator (handled by fixture)
2. Navigate to the project and switch to threads page
3. Verify the expected number of threads (3) are visible
4. For each thread:
   - Open the thread content
   - Verify all input messages are present in correct order
   - Verify all output messages are present in correct order
   - Close the thread content

This test ensures threads created via decorator maintain proper message ordering and are visible in the UI.`
      });

      await verifyThreads(page, projectName, createThreadsDecorator);
    });

    test('Threads created via low-level client API are correctly displayed with proper message ordering @fullregression @tracing @threads', async ({
      page,
      projectName,
      createThreadsClient,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that threads (conversations) created via the low-level client API are correctly displayed with proper message ordering in the UI.

Steps:
1. Create a project and 3 threads via low-level client (handled by fixture)
2. Navigate to the project and switch to threads page
3. Verify the expected number of threads (3) are visible
4. For each thread:
   - Open the thread content
   - Verify all input messages are present in correct order
   - Verify all output messages are present in correct order
   - Close the thread content

This test ensures threads created via low-level client maintain proper message ordering and are visible in the UI.`
      });

      await verifyThreads(page, projectName, createThreadsClient);
    });
  });

  test.describe('Thread removal', () => {
    test('Threads can be deleted via UI and removal is properly reflected @fullregression @tracing @threads', async ({
      page,
      projectName,
      createThreadsClient,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that threads can be deleted via the UI and the removal is properly reflected.

Steps:
1. Create a project and 3 threads via low-level client (handled by fixture)
2. Navigate to the project and switch to threads page
3. Verify initial thread count is 3
4. Delete 2 threads via UI:
   - Search for each thread by ID
   - Delete the thread from the table
   - Verify the thread is deleted and no longer appears
5. Clear search and verify remaining thread count

This test ensures threads can be properly deleted via the UI.`
      });

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
          await threadsPage.deleteThreadFromTable(threadId);
          await threadsPage.checkThreadIsDeleted(threadId);
          await threadsPage.clearThreadSearch();
        }
      });
    });
  });
});
