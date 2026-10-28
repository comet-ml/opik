import { test, expect } from '@e2e/fixtures/project-prompt-library.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * The variant header's Text-vs-Chat chooser, and the same control's empty state
 * (opik#8759).
 *
 * `isChoosingStructure` keys off `hasBothStructures` — whether the LIBRARY the
 * menu is listing happens to hold prompts of both types — so which of the two
 * entry states a user meets is a fact about their data, not about the control.
 * That is why nothing pinned it before: the estate's own helper
 * (`PlaygroundPage.chooseChatPromptsInLibraryMenu`) asserts
 * `chatOption.or(Search)` and clicks the option only `if (await
 * chatOption.isVisible())`, with the comment "The workspace is shared, so
 * either can happen". A regression where the chooser never appeared, or always
 * appeared, leaves every playground spec green.
 *
 * It is pinnable here because the menu lists prompts for the ACTIVE PROJECT
 * (`useProjectPromptsList`), and a per-test project makes that library's
 * contents a thing the test owns. Each case therefore seeds the exact library
 * state it is about, and the fixture proves the stored `template_structure`
 * before the browser opens — a menu asserted over prompts that were not the
 * types asked for could not fail.
 *
 * ## Ordering, which is load-bearing
 *
 * The menu renders a spinner while `isChoosingStructure` is still
 * `(isLoading || hasBothStructures)`, and never shows the choice and the list
 * together. So "it did NOT ask for a type" has to settle the list first and
 * only then assert the choice is absent — otherwise it passes against the
 * spinner. `PromptLibraryMenu.expectOpensOnList` is that assertion written the
 * right way round.
 *
 * The last test is the same control with nothing to list: Load disables itself
 * off `useProjectPromptsList(...).total === 0`. It rides here rather than in a
 * file of its own because it is the same button in a third state, and because
 * on its own it is cosmetic.
 *
 * NOT covered: the chooser on the message-row library door, which does not have
 * one — only `PlaygroundPrompt.tsx` passes `chooseTemplateStructure`, and it
 * defaults to false, so the Optimization Studio and Explain menus keep listing
 * everything.
 */
test.describe(
  "Playground → the variant header library menu's entry states",
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 1000 } });

    test(
      'a library holding BOTH types asks for the type first, and the chevron goes back',
      { tag: ['@cap:playground.prompt-library-structure-chooser'] },
      async ({ page, project, promptLibrary, providerKeys, testNamespace }) => {
        test.setTimeout(150_000);

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the setup dialog stays shut', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        // Exactly two prompts in this project, one of each type — so the
        // per-type assertions below are the WHOLE answer about each list, not
        // just a membership check.
        const { textPrompt, chatPrompt } = await test.step('Seed one text prompt and one chat prompt', async () => ({
          textPrompt: await promptLibrary.seedTextPrompt({
            suffix: 'both-text',
            role: 'user',
            bodies: ['A text prompt body.'],
          }),
          chatPrompt: await promptLibrary.seedChatPrompt({
            suffix: 'both-chat',
            messages: [{ role: 'user', content: 'A chat prompt body.' }],
          }),
        }));

        await playground.goto();
        await playground.waitForReady();
        const menu = await playground.openHeaderLibraryMenu();

        await test.step('The menu opens on the type choice, with no list behind it', async () => {
          await expect(menu.structureOptions).toBeVisible();
          await expect(menu.structureOption('text')).toContainText('Text prompt');
          await expect(menu.structureOption('chat')).toContainText('Chat prompt');
          await expect(menu.searchBox, 'no list until a type is chosen').toHaveCount(0);
          await expect(menu.back, 'nothing to go back to yet').toHaveCount(0);
        });

        await test.step('Chat prompts lists the chat prompt and only that', async () => {
          await menu.chooseStructure('chat');
          await expect(menu.back).toContainText('Chat prompts');
          await expect(menu.promptRow(chatPrompt.name)).toHaveCount(1);
          await expect(
            menu.promptRow(textPrompt.name),
            'the text prompt is filtered out',
          ).toHaveCount(0);
        });

        await test.step('The back chevron returns to the type choice', async () => {
          await menu.back.click();
          await expect(menu.structureOptions).toBeVisible();
          await expect(menu.back).toHaveCount(0);
          await expect(menu.searchBox).toHaveCount(0);
        });

        await test.step('Text prompts lists the text prompt and only that', async () => {
          await menu.chooseStructure('text');
          await expect(menu.back).toContainText('Text prompts');
          await expect(menu.promptRow(textPrompt.name)).toHaveCount(1);
          await expect(
            menu.promptRow(chatPrompt.name),
            'the chat prompt is filtered out',
          ).toHaveCount(0);
        });
      },
    );

    for (const only of ['text', 'chat'] as const) {
      test(
        `a library holding only ${only} prompts opens straight on the list`,
        { tag: ['@cap:playground.prompt-library-structure-chooser'] },
        async ({ page, project, promptLibrary, providerKeys, testNamespace }) => {
          test.setTimeout(120_000);

          const playground = new PlaygroundPage(page, project.id);

          await test.step('Give the workspace a provider so the setup dialog stays shut', async () => {
            await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
          });

          // Both halves are driven, because `hasBothStructures` is an `every`
          // over the two options: a bug that read only one of them would make
          // one of these two cases pass and the other fail.
          const seeded = await test.step(`Seed one ${only} prompt and nothing else`, async () =>
            only === 'text'
              ? promptLibrary.seedTextPrompt({
                  suffix: 'only-text',
                  role: 'user',
                  bodies: ['The only prompt in this library.'],
                })
              : promptLibrary.seedChatPrompt({
                  suffix: 'only-chat',
                  messages: [{ role: 'user', content: 'The only prompt in this library.' }],
                }));

          await playground.goto();
          await playground.waitForReady();
          const menu = await playground.openHeaderLibraryMenu();

          await test.step('No type choice, and the prompt is listed', async () => {
            await menu.expectOpensOnList();
            await expect(menu.promptRow(seeded.name)).toHaveCount(1);
          });
        },
      );
    }

    test(
      'an empty library leaves Load disabled and says why',
      { tag: ['@cap:playground.prompt-library-structure-chooser'] },
      async ({ page, project, providerKeys, testNamespace }) => {
        test.setTimeout(120_000);

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Give the workspace a provider so the setup dialog stays shut', async () => {
          await providerKeys.createUnreachable({ providerName: `${testNamespace}-unreachable` });
        });

        // Nothing is seeded: a fresh project's library is empty by
        // construction, which is what makes `total === 0` a state this test
        // owns rather than one it has to wait for.
        await playground.goto();
        await playground.waitForReady();

        await test.step('Load renders, disabled', async () => {
          // Auto-retried rather than read once: the button mounts enabled and
          // disables itself when `useProjectPromptsList` answers, so a
          // point-in-time read can catch the pre-answer frame.
          await expect(playground.headerLoadButton()).toBeVisible();
          await expect(
            playground.headerLoadButton(),
            'Load disables itself on an empty library',
          ).toBeDisabled({ timeout: 30_000 });
        });

        await test.step('…and says why', async () => {
          expect(await playground.headerLoadTooltipText()).toBe('No saved prompts to load');
        });

        await test.step('…and really does not open a menu', async () => {
          // Forced, so the click is dispatched past Playwright's own enabled
          // check: the claim is that the BROWSER raises no click on a disabled
          // control, not that the test runner declined to try.
          await playground.headerLoadButton().click({ force: true });
          await expect(playground.promptLibraryMenu().root).toHaveCount(0);
        });
      },
    );
  },
);
