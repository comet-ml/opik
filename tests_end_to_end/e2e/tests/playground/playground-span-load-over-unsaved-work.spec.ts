import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * Loading a span into the Playground asks before clobbering unsaved work, and
 * Cancel leaves that work exactly as it was (opik#8725, issue-3411).
 *
 * `OpenSpanInPlaygroundButton` loads straight away when `isPlaygroundEmpty`, and
 * otherwise raises a `ConfirmDialog` titled "Load span messages" whose confirm
 * action is `Load messages`. The dialog's own copy says the action cannot be
 * undone, and it is right: the load calls `setPromptMap` with a freshly built
 * prompt, so a composed-but-unsaved prompt is gone the moment it runs.
 *
 * ## Why this is its own spec
 *
 * `playground.keep-unsaved-prompt-edits` exists for the prompt-LIBRARY entry
 * point — an unsaved edit to a loaded library prompt surviving a navigation, a
 * reload and a newer version being published. opik#8725 added a SECOND entry
 * point that can overwrite the same state, from a different page, and nothing
 * covers it. The dialog is the only thing standing between a user and losing a
 * prompt they have been composing.
 *
 * ## What the assertion has to be
 *
 * Not "the dialog appeared". A dialog that appeared and then let the load
 * through anyway, or one whose Cancel navigated to the Playground and reset it,
 * would satisfy that and still lose the work. So the test pins the prompt
 * message for message across the Cancel — read out of `PLAYGROUND_STATE`, which
 * is the only place a message's ROLE and content are both observable — and
 * checks the browser did not navigate off the Logs page while it was at it.
 *
 * Then it confirms, so the dialog is shown to be a real gate rather than a
 * dead-end: the span's messages must actually replace the composed ones. A spec
 * that only tested Cancel would pass against a button that never loaded
 * anything.
 *
 * Driven through the UI only, because the candidate is `ui`: the state at stake
 * lives in localStorage and the gate is a browser dialog.
 */
test.describe(
  'Playground — loading a span over unsaved work',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    /** A trace+span seed, a composed prompt, and three full page loads. */
    test.setTimeout(240_000);

    /**
     * The prompt the test composes and then defends.
     *
     * Two messages, with distinct bodies and two different roles: the load
     * replaces the whole `messages` array, so a build that kept the right number
     * of rows while rebuilding their contents — or that kept the contents and
     * flattened the roles — differs from this in a way one message could not
     * show. The run namespace is folded in by the test so nothing else in the
     * workspace can produce the same text.
     */
    const COMPOSED = {
      system: 'answer only with the word MARMALADE and nothing else',
      user: 'what is the preferred spread for this test',
    } as const;

    test(
      'asks first, and Cancel leaves the composed prompt untouched',
      {
        tag: [
          '@cap:playground.keep-unsaved-prompt-edits',
          '@cap:playground.open-span-in-playground',
        ],
      },
      async ({ playgroundSpanLoads, testNamespace, page }) => {
        const seed = playgroundSpanLoads;
        const span = seed.span('configured-model');
        const logs = new LogsPage(page);
        const playground = new PlaygroundPage(page, seed.projectId);

        const composed = {
          system: `${COMPOSED.system} (${testNamespace})`,
          user: `${COMPOSED.user} (${testNamespace})`,
        };

        const before = await test.step('Compose a prompt in the Playground and leave it unsaved', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            systemPrompt: composed.system,
            userPrompt: composed.user,
          });
          // Read back as the store holds it, which is the form the comparison
          // after the Cancel is made in. Asserted here rather than only
          // recorded: if the compose had not landed, "the prompt is unchanged"
          // would be a comparison of two empty cards and would pass having
          // defended nothing.
          const stored = (await playground.storedMessages()).map((m) => ({
            role: m.role,
            content: m.content,
          }));
          expect(
            stored,
            'the composed system and user messages are both on the card, with their roles',
          ).toEqual([
            { role: 'system', content: composed.system },
            { role: 'user', content: composed.user },
          ]);
          return stored;
        });

        const panel = await test.step('Open the trace\'s LLM span in the Logs panel', async () => {
          const opened = await logs.openSpanById(seed.projectId, seed.traceId, span.spanId);
          await opened.waitForFullyLoaded();
          await opened.waitForToolbarReady();
          return opened;
        });

        await test.step('Press Play — the Playground is not empty, so it asks', async () => {
          await panel.clickOpenInPlayground();
          await expect(
            panel.loadSpanConfirmDialog,
            'the "Load span messages" confirmation is raised rather than the load running',
          ).toHaveCount(1);
          await expect(
            panel.loadSpanConfirmDialog,
            'and it is on screen',
          ).toBeVisible();
        });

        await test.step('Cancel it', async () => {
          const dialog = panel.loadSpanConfirmDialog;
          await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
          await expect(dialog, 'the confirmation closed').toHaveCount(0);
          // The page did not navigate. `loadPlayground` ends in a
          // `navigate({ to: '…/playground' })`, so staying on the Logs URL is
          // the observable proof the load did not run at all — separate from,
          // and stronger than, the store still holding the old prompt.
          expect(
            new URL(page.url()).pathname,
            'Cancel left the user where they were — the Playground was never navigated to',
          ).toMatch(/\/logs$/);
          expect(
            new URL(page.url()).searchParams.get('span'),
            'with the same span still selected',
          ).toBe(span.spanId);
        });

        await test.step('Back in the Playground, the composed prompt is unchanged', async () => {
          await playground.goto();
          await playground.waitForReady();
          expect(
            (await playground.storedMessages()).map((m) => ({
              role: m.role,
              content: m.content,
            })),
            'message for message, role for role, exactly what was composed',
          ).toEqual(before);
          // And on screen, not only in the store: the two are written by the
          // same code path, so a card that had been rebuilt from the store
          // would show whatever the store held — but a card whose EDITORS had
          // been blanked while the store kept the old value would pass the
          // comparison above alone.
          expect(
            await playground.messageBodies(),
            'and the editors render them',
          ).toEqual([composed.system, composed.user]);
        });
      },
    );

    test(
      'replaces the composed prompt once the load is confirmed',
      {
        tag: [
          '@cap:playground.keep-unsaved-prompt-edits',
          '@cap:playground.open-span-in-playground',
        ],
      },
      async ({ playgroundSpanLoads, testNamespace, page }) => {
        const seed = playgroundSpanLoads;
        const span = seed.span('configured-model');
        const logs = new LogsPage(page);
        const playground = new PlaygroundPage(page, seed.projectId);

        const composed = {
          system: `${COMPOSED.system} (${testNamespace})`,
          user: `${COMPOSED.user} (${testNamespace})`,
        };

        await test.step('Compose a prompt in the Playground and leave it unsaved', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            systemPrompt: composed.system,
            userPrompt: composed.user,
          });
          expect(
            (await playground.storedMessages()).map((m) => m.content),
            'the composed prompt is on the card, so there is something to replace',
          ).toEqual([composed.system, composed.user]);
        });

        await test.step('Press Play and confirm the load', async () => {
          const panel = await logs.openSpanById(seed.projectId, seed.traceId, span.spanId);
          await panel.waitForFullyLoaded();
          await panel.waitForToolbarReady();
          await panel.clickOpenInPlayground();
          const dialog = panel.loadSpanConfirmDialog;
          await expect(dialog, 'the confirmation is raised').toHaveCount(1);
          await dialog.getByRole('button', { name: 'Load messages', exact: true }).click();
          await playground.waitForReady();
        });

        await test.step('The span\'s messages replaced the composed ones', async () => {
          const stored = (await playground.storedMessages()).map((m) => ({
            role: m.role,
            content: m.content,
          }));
          expect(
            stored,
            'the card now holds the span\'s one user message',
          ).toEqual([{ role: 'user', content: span.userMessage }]);
          // Named as the thing that is gone, not merely as "the new value is
          // there": the dialog's own copy promises this is destructive, and a
          // build that appended the span's messages beside the composed ones
          // would satisfy a containment check while silently changing what a
          // Run would send.
          const bodies = await playground.messageBodies();
          expect(
            bodies,
            'and the composed prompt is gone from the editors entirely',
          ).toEqual([span.userMessage]);
        });
      },
    );
  },
);
