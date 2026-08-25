import { test, expect } from '../../fixtures/prompt.fixture';
import { PromptsPage } from '@e2e/pom/prompts.page';

/**
 * Deleting a prompt is destructive and permanent — it removes every associated
 * commit, not just the current version. Prompts are the highest-traffic
 * Development surface in the estate, and delete is the one CRUD action nobody
 * asserted until now.
 *
 * Deliberately NOT asserted: what the prompt's detail route renders afterwards.
 * The route currently hangs on loading skeletons for a deleted id (the page
 * never reads the query's error state), which is a bug rather than intended
 * behaviour — pinning it here would turn the fix into a test failure. This spec
 * asserts the durable fact instead: the prompt leaves the library and stays
 * gone. Reloading is what separates a real delete from a cache eviction, since
 * the UI shows no success toast to confirm either way.
 */
test.describe('Prompt library — delete', { tag: ['@t2-cuj', '@area:prompts'] }, () => {
  test(
    'Deleting a prompt removes it from the library and it stays deleted',
    { tag: ['@cap:prompts.delete-prompt'] },
    async ({ textPrompt, project, page }) => {
      const prompts = new PromptsPage(page);

      await test.step('The seeded prompt is listed', async () => {
        await prompts.goto(project.id);
        await prompts.waitForReady();
        await expect(prompts.promptRow(textPrompt.name)).toBeVisible();
      });

      await test.step('Delete it via the row actions menu', async () => {
        await prompts.deletePromptByName(textPrompt.name);
      });

      await test.step('The row is gone from the library', async () => {
        await expect(prompts.promptRow(textPrompt.name)).toHaveCount(0);
      });

      await test.step('It is still gone after a reload', async () => {
        await page.reload();
        await prompts.waitForReady();
        await expect(prompts.promptRow(textPrompt.name)).toHaveCount(0);
      });
    },
  );
});
