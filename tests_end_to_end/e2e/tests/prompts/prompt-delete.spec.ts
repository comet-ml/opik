import { test, expect } from '../../fixtures/prompt.fixture';
import { PromptsPage } from '@e2e/pom/prompts.page';

/**
 * Deleting a prompt is destructive and permanent — it removes every associated
 * commit, not just the current version. Prompts are the highest-traffic
 * Development surface in the estate, and delete is the one CRUD action nobody
 * asserted until now.
 *
 * A second prompt is seeded purely as a bystander: asserting only that the
 * target vanished would pass just as happily if the delete wiped the whole
 * library, so the bystander is what pins the blast radius to one row. Its
 * cleanup is registered through `registerPromptCleanup` the moment it exists,
 * so a failure part-way through this test cannot strand it in the workspace.
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
    'Deleting a prompt removes only that prompt, and it stays deleted',
    { tag: ['@cap:prompts.delete-prompt'] },
    async ({ textPrompt, project, sdkClient, registerPromptCleanup, testNamespace, page }) => {
      const prompts = new PromptsPage(page);
      const siblingName = `${testNamespace}-prompt-bystander`;

      await test.step('Seed a second prompt as a bystander', async () => {
        const sibling = await sdkClient.python.createTextPrompt({
          name: siblingName,
          prompt: 'Bystander prompt: {{question}}',
          project_name: project.name,
        });
        // Registered immediately, not after the assertions — the fixture's
        // teardown runs even when a step below fails.
        registerPromptCleanup(sibling.id, sibling.name);
      });

      await test.step('Both prompts are listed', async () => {
        await prompts.goto(project.id);
        await prompts.waitForReady();
        await expect(prompts.promptRow(textPrompt.name)).toBeVisible();
        await expect(prompts.promptRow(siblingName)).toBeVisible();
      });

      await test.step('Delete the target via the row actions menu', async () => {
        await prompts.deletePromptByName(textPrompt.name);
      });

      await test.step('The target is gone and the bystander is untouched', async () => {
        await expect(prompts.promptRow(textPrompt.name)).toHaveCount(0);
        await expect(prompts.promptRow(siblingName)).toBeVisible();
      });

      await test.step('Both facts survive a reload', async () => {
        await page.reload();
        await prompts.waitForReady();
        await expect(prompts.promptRow(textPrompt.name)).toHaveCount(0);
        await expect(prompts.promptRow(siblingName)).toBeVisible();
      });
    },
  );
});
