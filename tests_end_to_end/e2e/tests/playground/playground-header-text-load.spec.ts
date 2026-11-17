import { test, expect } from '@e2e/fixtures/project-prompt-library.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * The variant header's TEXT-prompt load (opik#8759) — `handleImportTextPrompt`.
 *
 * A different door from the one the estate already drives, and the difference
 * is the behaviour: the message row's own Load replaces ONE message
 * (`loadTextPromptVersionFromLibrary`, `loadTextPromptVersionIntoMessage`),
 * while this one replaces EVERY message in the card with a single one built
 * from the version. Nothing reached it before this spec.
 *
 * ## Why this is worth a test rather than a release note
 *
 * It is destructive and irreversible — the other messages in the card are
 * discarded with no confirmation and no undo — and the role it rebuilds the one
 * surviving message with is `parsePromptVersionRole(version) ?? user`. The
 * fallback is the silent half: a prompt saved as System coming back as User
 * changes what the card does to the model without looking wrong, and neither
 * the body nor the message count would notice.
 *
 * ## Why the failure branch is the other half
 *
 * The load is a fetch, and the catch around it only toasts — it does not roll
 * anything back, because nothing has been written yet. That is the correct
 * behaviour and it is worth pinning precisely because a future refactor that
 * cleared the card first and filled it second would still pass the happy path.
 * The failure is induced on `GET /v1/private/prompts/{id}`, which is the
 * request a row click makes (no `versionId`, so `handleImportTextPrompt` takes
 * the `fetchPrompt(...).latest_version` arm), scoped to this prompt's own id so
 * the menu's own list request still answers and the row stays clickable.
 *
 * NOT covered: loading a specific older version through the header's version
 * submenu (the `fetchPromptVersion` arm), and the chat arm of the same menu,
 * which six existing playground/prompts specs already drive.
 */
test.describe(
  "Playground → the variant header's Text-prompt load",
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 1000 } });

    test(
      'it collapses the whole card to one message and keeps the saved role',
      { tag: ['@cap:playground.header-text-prompt-load'] },
      async ({ page, project, promptLibrary, providerKeys, testNamespace }) => {
        test.setTimeout(150_000);

        const savedBody = 'You answer only in haiku.';
        const kept = ['KEEP-SYSTEM', 'KEEP-USER', 'KEEP-THIRD'];
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the setup dialog stays shut', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        // A SYSTEM role, which is the whole point: `user` is the fallback, so a
        // prompt saved as user could not tell "the role was preserved" from
        // "the role was lost".
        const textPrompt = await test.step('Seed a SYSTEM-role text prompt — the only one in this library', async () =>
          promptLibrary.seedTextPrompt({
            suffix: 'system-role-text',
            role: 'system',
            bodies: [savedBody],
          }));

        await test.step('Build a card with three filled messages', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            systemPrompt: kept[0],
            userPrompt: kept[1],
          });
          await playground.addMessage(0);
          await expect(playground.messageRows()).toHaveCount(3);
          await playground.fillMessageAt(2, kept[2]);
          await expect
            .poll(async () => playground.variantMessageBodies(), { timeout: 15_000 })
            .toEqual(kept);
        });

        await test.step('Load the text prompt through the variant header', async () => {
          const menu = await playground.openHeaderLibraryMenu();
          // Only text prompts in this project's library, so no type choice.
          await menu.expectOpensOnList();
          await menu.selectPrompt(textPrompt.name);
        });

        await test.step('The card is now that one message, under the role it was saved with', async () => {
          await expect(
            playground.messageRows(),
            'the other two messages are discarded',
          ).toHaveCount(1);
          await expect
            .poll(async () => playground.variantMessageBodies(), { timeout: 30_000 })
            .toEqual([savedBody]);
          expect(
            await playground.messageRoleAt(0),
            'the role the prompt was saved with, not the `user` fallback',
          ).toBe('System');
        });
      },
    );

    test(
      'a failed load leaves the existing messages intact and says so',
      { tag: ['@cap:playground.header-text-prompt-load'] },
      async ({ page, project, promptLibrary, providerKeys, testNamespace }) => {
        test.setTimeout(150_000);

        const intact = ['INTACT-SYSTEM', 'INTACT-USER'];
        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the setup dialog stays shut', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        const textPrompt = await test.step('Seed a text prompt nothing will succeed in loading', async () =>
          promptLibrary.seedTextPrompt({
            suffix: 'failing-text',
            role: 'user',
            bodies: ['This body must never reach the card.'],
          }));

        await test.step('Open the Playground with two filled messages', async () => {
          await playground.startRecordingToasts();
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            systemPrompt: intact[0],
            userPrompt: intact[1],
          });
          expect(await playground.variantMessageBodies()).toEqual(intact);
        });

        await test.step("Make this prompt's own fetch answer 500", async () => {
          // Scoped to the prompt id, not to `**/prompts*`: the menu's own list
          // request shares that prefix, and killing it would make the row
          // unreachable — a load that never started is not the failure under
          // test.
          await page.route(`**/v1/private/prompts/${textPrompt.id}`, (route) =>
            route.fulfill({
              status: 500,
              contentType: 'application/json',
              body: JSON.stringify({ code: 500, message: 'induced by playground-header-text-load' }),
            }),
          );
        });

        await test.step('Try to load it from the header', async () => {
          const menu = await playground.openHeaderLibraryMenu();
          await menu.expectOpensOnList();
          await menu.selectPrompt(textPrompt.name);
        });

        await test.step('A failure toast is raised and the card is untouched', async () => {
          await expect
            .poll(async () => playground.recordedToasts(), {
              timeout: 60_000,
              intervals: [500, 1_000, 2_000],
              message: 'the load failure raises PROMPT_LOAD_FAILED_TOAST',
            })
            .toEqual(expect.arrayContaining([expect.stringContaining('Prompt not loaded')]));

          await expect(playground.messageRows(), 'both messages survive').toHaveCount(2);
          expect(
            await playground.variantMessageBodies(),
            'a failed load clears nothing',
          ).toEqual(intact);
        });
      },
    );
  },
);
