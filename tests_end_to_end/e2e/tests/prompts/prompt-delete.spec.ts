import { test, expect } from '../../fixtures/prompt.fixture';
import { PromptsPage } from '@e2e/pom/prompts.page';

/**
 * Deleting a prompt removes every associated commit, not just the current
 * version. There is no success toast, so the reload is what distinguishes a
 * real delete from a cache eviction; the bystander bounds the blast radius.
 *
 * Deliberately not asserted: what the detail route renders for a deleted id.
 * It currently hangs on loading skeletons because the page never reads the
 * query's error state — a bug, so pinning it here would make the fix fail.
 */
test.describe('Prompt library — delete', { tag: ['@t2-cuj', '@area:prompts'] }, () => {
  test(
    'Deleting a prompt removes only that prompt, and it stays deleted',
    { tag: ['@cap:prompts.delete-prompt'] },
    async ({
      textPrompt,
      project,
      sdkClient,
      backendClient,
      registerPromptCleanup,
      testNamespace,
      page,
    }) => {
      const prompts = new PromptsPage(page);
      const siblingName = `${testNamespace}-prompt-bystander`;

      await test.step('Seed a second prompt as a bystander', async () => {
        await sdkClient.python.createTextPrompt({
          name: siblingName,
          prompt: 'Bystander prompt: {{question}}',
          project_name: project.name,
        });
        // Resolve the id by name rather than using the one create returned:
        // the SDK hands back the prompt VERSION id, which DELETE answers 404
        // for, so registering it would silently leak the prompt.
        const siblingId = await backendClient.findPromptIdByName(siblingName);
        expect(siblingId, 'bystander prompt id resolves by name').not.toBeNull();
        registerPromptCleanup(siblingId as string, siblingName);
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

      await test.step('Server-side: the target is gone, the bystander remains', async () => {
        // The UI list is a project-scoped projection, so an absent row would
        // also be consistent with the prompt surviving outside that view.
        expect(await backendClient.promptExistsByName(textPrompt.name)).toBe(false);
        expect(await backendClient.promptExistsByName(siblingName)).toBe(true);
      });
    },
  );
});
