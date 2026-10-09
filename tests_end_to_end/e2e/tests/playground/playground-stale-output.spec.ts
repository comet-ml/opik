// `prompt.fixture` and not `@e2e/fixtures`: the second test saves a prompt to
// the library through the UI, and `registerPromptCleanup` lives on the last
// link of the chain rather than on the re-exported middle of it. Prompts do not
// cascade with the project and the run-prefix sweep in `global-teardown` does
// not know about them, so one has to be registered explicitly. The same import
// the other playground-plus-library specs use (`prompt-playground-save.spec.ts`).
import { test, expect } from '../../fixtures/prompt.fixture';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * A finished playground output is dimmed and explained when the prompt that
 * produced it changes, and left alone when it does not (opik#8758).
 *
 * Before this change `updatePrompt` greyed the output on ANY update, with no
 * explanation — and "any update" included a great deal the user never did.
 * Loading a prompt from the library re-applies its messages with FRESH ids, so
 * a plain reload of a page with a library link set was enough to grey a run the
 * user had just read, with nothing on screen to say why. The store now compares
 * only what a run actually sends (`toRunMessages` drops the ids) and records
 * WHICH inputs changed.
 *
 * The two tests below are the two sides of that one gate, kept in one file
 * because neither is evidence without the other: a build that never dims would
 * pass the second test, a build that always dims would pass the first, and only
 * the pair pins the condition.
 *
 * This covers the FREE-MODE surface — the single-prompt `PlaygroundPromptOutput`
 * panel. `playground-stale-output-dataset-grid.spec.ts` covers the grid cell,
 * which is a different component with its own `hasOutput` and its own `compact`
 * note, so the two regress apart. Same split, and the same reason, as
 * `playground-failed-run-output.spec.ts` vs `playground-dataset-run-failure.spec.ts`.
 *
 * ## Deterministic, with no provider involved
 *
 * The run is made to fail client-side with a `{{variable}}` no data defines:
 * `transformMessageIntoProviderMessage` throws before a request is written, so
 * nothing here depends on a provider key, a reachable model, a network, or
 * anything an LLM says. The unreachable custom provider is seeded only so the
 * model picker has something to select. That the output under test is an ERROR
 * rather than an answer is incidental to the staleness gate — `stale` is set on
 * the output entry, and the panel dims whichever of the two it is holding — but
 * it is what makes the test free of an LLM call, and it is the half a user is
 * most likely to lose, since a failure is what you re-read while editing.
 *
 * ## One trap, which this change itself created
 *
 * Neither test runs twice, and that is deliberate rather than a gap:
 * `PlaygroundPage.runFreeMode` treats the "No runs yet" placeholder
 * disappearing as "the run finished", and a stale output keeps the placeholder
 * gone — so after the first run it would return the instant Run is pressed.
 * These tests use `clickRun` + `waitForPromptOutputErrors`, which waits on the
 * output itself. A spec that does need a second run must do the same.
 */

/** A variable no item data defines, so the run throws before any request. */
const MISSING_VARIABLE = 'missing_column';
const EXPECTED_FAILURE = `Run failed: ${MISSING_VARIABLE} not defined`;
const ORIGINAL_PROMPT = `Summarise {{${MISSING_VARIABLE}}}`;
const EDITED_PROMPT = `Summarise, briefly, {{${MISSING_VARIABLE}}}`;

/**
 * The whole sentence the panel renders, which is the point of the change: the
 * previous behaviour greyed the output and said nothing at all.
 *
 * "Prompt" and not "Prompt and parameters": the edit touches only the message
 * body, and `getRunInputChanges` must report just that one input. Asserted as
 * the exact string rather than a substring, so a build that listed every input
 * on every edit fails here.
 */
const PROMPT_CHANGED_NOTE =
  'Prompt changed since the last run. Re-run to update results.';

test.describe(
  'Playground — output freshness in free mode',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'Editing the prompt after a run dims the output and names what changed',
      { tag: ['@cap:playground.output-staleness'] },
      async ({ page, project, providerKeys, testNamespace }) => {
        // A project, a REST-seeded provider key and a cold Playground load. The
        // failure itself is instant; the preamble is not, and the 90s default is
        // the odd one out in this directory.
        test.setTimeout(120_000);

        const modelId = await test.step('Seed an unreachable custom provider', async () =>
          providerKeys.createUnreachable({
            providerName: `${testNamespace}-unreachable`,
            modelName: `${testNamespace}-dead-model`,
          }));

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Run a prompt that fails client-side', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            userPrompt: ORIGINAL_PROMPT,
            modelDisplayName: modelId.split('/').pop(),
          });
          await playground.clickRun();
          await playground.waitForPromptOutputErrors(1);
        });

        await test.step('The fresh output is undimmed and carries no note', async () => {
          // The baseline, and not a formality: every assertion after the edit is
          // about a CHANGE, so a panel that was already dimmed and already noted
          // would satisfy all of them while proving nothing.
          await expect(
            playground.promptOutputErrorTags(),
            'the run failed and says which variable was missing',
          ).toHaveText([EXPECTED_FAILURE]);
          expect(
            await playground.promptOutputErrorOpacities(),
            'a fresh output is at full opacity',
          ).toEqual([1]);
          await expect(
            playground.promptStaleOutputNotes(),
            'nothing has changed yet, so there is nothing to explain',
          ).toHaveCount(0);
        });

        await test.step('Edit the message body', async () => {
          await playground.editUserMessage(EDITED_PROMPT);
        });

        await test.step('The output is still there, dimmed, and says why', async () => {
          // Still there is the first half of the claim. The behaviour this
          // replaced cleared the panel back to its empty state, which loses the
          // failure the user was reading.
          await expect(
            playground.promptOutputErrorTags(),
            'the edit did not take the failure off the screen',
          ).toHaveText([EXPECTED_FAILURE]);
          await expect(
            playground.noRunsYetPlaceholders(),
            'a stale output is not the same as one that never happened',
          ).toHaveCount(0);

          const opacities = await playground.promptOutputErrorOpacities();
          expect(opacities, 'exactly one output tag to read').toHaveLength(1);
          expect(
            opacities[0],
            'the stale output is visibly dimmed (the level itself is a design token)',
          ).toBeLessThan(1);

          await expect(
            playground.promptStaleOutputNotes(),
            'one note, naming only the input that actually changed',
          ).toHaveText([PROMPT_CHANGED_NOTE]);
        });
      },
    );

    test(
      'A reload, a library load and a detach leave a finished output live',
      { tag: ['@cap:playground.output-staleness'] },
      async ({
        page,
        project,
        providerKeys,
        backendClient,
        registerPromptCleanup,
        testNamespace,
      }) => {
        // Four page loads and a library round-trip on top of the usual preamble.
        test.setTimeout(180_000);

        const promptName = `${testNamespace}-stale-probe`;

        const modelId = await test.step('Seed an unreachable custom provider', async () =>
          providerKeys.createUnreachable({
            providerName: `${testNamespace}-unreachable`,
            modelName: `${testNamespace}-dead-model`,
          }));

        const playground = new PlaygroundPage(page, project.id);

        /** Assert the output is on screen, undimmed, with nothing to explain. */
        const expectOutputStillLive = async (after: string): Promise<void> => {
          await test.step(`The output is still live after ${after}`, async () => {
            await expect(
              playground.promptOutputErrorTags(),
              `the output survived ${after}`,
            ).toHaveText([EXPECTED_FAILURE]);
            expect(
              await playground.promptOutputErrorOpacities(),
              `${after} did not dim the output`,
            ).toEqual([1]);
            await expect(
              playground.promptStaleOutputNotes(),
              `${after} is not a change to the run inputs, so there is no note`,
            ).toHaveCount(0);
          });
        };

        await test.step('Run a prompt that fails client-side', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            userPrompt: ORIGINAL_PROMPT,
            modelDisplayName: modelId.split('/').pop(),
          });
          await playground.clickRun();
          await playground.waitForPromptOutputErrors(1);
        });

        await expectOutputStillLive('the run itself');

        const idsBeforeLibrary = await playground.storedMessageIds();
        // A floor and not a fixed count: what the comparison below needs is that
        // the read returned real ids, and pinning the composer's default message
        // count would couple this spec to a shape it does not care about.
        expect(
          idsBeforeLibrary.length,
          'the store really holds the ids the run was composed from',
        ).toBeGreaterThan(0);

        await test.step('Reload the page', async () => {
          // The plainest case, and the one a user hits most: the output is
          // rehydrated from the persisted store, which is also where `stale`
          // lives — so a reload is part of this surface rather than a reset of it.
          await playground.goto();
          await playground.waitForReady();
        });

        await expectOutputStillLive('a plain reload');

        await test.step('Save the prompt to the library as a new chat prompt', async () => {
          await playground.saveNewChatPromptToLibrary(promptName);
          const promptId = await backendClient.findPromptIdByName(promptName, project.id);
          // Resolved by name, not taken from the UI: `DELETE /v1/private/prompts/{id}`
          // answers 404 for a version id, so teardown registered with the wrong
          // one leaks the prompt silently behind its catch.
          expect(promptId, `the saved prompt "${promptName}" is in the library`).not.toBeNull();
          registerPromptCleanup(promptId as string, promptName);
        });

        await expectOutputStillLive('saving to the library');

        await test.step('Reload again, so the library link re-applies the prompt on mount', async () => {
          // This is the regression the change exists for. With a prompt linked,
          // mount re-applies it through `updatePrompt`, and the re-applied
          // messages are rebuilt with new ids — an update with no user edit
          // behind it at all.
          await playground.goto();
          await playground.waitForReady();
          await playground.waitForLoadedPromptVersion(promptName, 'v1');
        });

        const idsAfterLibraryLoad = await playground.storedMessageIds();
        // "Same messages, new ids" is the exact payload this change is about, so
        // both halves are asserted: the count is unchanged, and not one id is.
        expect(
          idsAfterLibraryLoad,
          'the re-applied prompt holds the same messages it did before',
        ).toHaveLength(idsBeforeLibrary.length);
        expect(
          idsAfterLibraryLoad,
          'the library load really did rebuild the messages with fresh ids — ' +
            'without that, "no note" below would be true of a load that did nothing',
        ).not.toEqual(idsBeforeLibrary);
        expect(
          idsAfterLibraryLoad.filter((id) => idsBeforeLibrary.includes(id)),
          'every id was regenerated, not just one of them',
        ).toEqual([]);

        await expectOutputStillLive('a reload that re-applied the library prompt');

        await test.step('Detach the loaded prompt', async () => {
          await playground.detachLoadedPrompt();
        });

        await expectOutputStillLive('detaching the loaded prompt');

        await test.step('Load the same version back from the library', async () => {
          await playground.loadPromptVersionFromLibrary(promptName, 'v1');
          await playground.waitForLoadedPromptVersion(promptName, 'v1');
        });

        const idsAfterExplicitLoad = await playground.storedMessageIds();
        expect(
          idsAfterExplicitLoad,
          'the explicit load holds the same messages again',
        ).toHaveLength(idsAfterLibraryLoad.length);
        expect(
          idsAfterExplicitLoad,
          'the explicit load regenerated the ids again',
        ).not.toEqual(idsAfterLibraryLoad);

        await expectOutputStillLive('an explicit library load');

        await test.step('And the output never fell back to the empty state', async () => {
          // The failure mode that would satisfy "no stale note" without the
          // output surviving: a panel cleared to "No runs yet" has no note
          // either.
          await expect(
            playground.noRunsYetPlaceholders(),
            'the panel is showing the run, not the empty state',
          ).toHaveCount(0);
        });
      },
    );
  },
);
