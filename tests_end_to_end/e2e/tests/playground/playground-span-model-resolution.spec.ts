import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import type { TracePanelPage } from '@e2e/pom/trace-panel.page';

/**
 * "Open in Playground" lands a logged LLM span on ITS OWN model and provider,
 * and the action appears only where it can work (opik#8725, issue-3411).
 *
 * The Play button on the trace-details toolbar loads a span's input messages
 * into the Playground as a chat prompt. `useLoadSpanIntoPlayground` passes the
 * span's `model` as `preferredModel` and its `provider` — translated through
 * `LOGGED_PROVIDER_KEYS` for the two Google names the SDKs use — as
 * `preferredProvider`; `useLoadPlayground.createPromptFromContent` then keeps
 * that model only when a CONFIGURED provider offers it in the dropdown.
 *
 * ## Why this needs an e2e test at all
 *
 * The in-PR unit tests (`useLoadSpanIntoPlayground.test.ts`,
 * `spanPlaygroundMessages.test.ts`, seven `TraceDataToolbar.test.tsx` visibility
 * cases) mock the model catalog — and the catalog is the thing resolution runs
 * against. `playground` reads 19/19 capabilities and not one of those keys names
 * this entry point, so nothing in the estate mentions it; the four existing
 * load-path specs (`playground-smoke`, `playground-header-text-load`,
 * `playground-library-prompt-edits`, `playground-prompt-state-hydration`) drive
 * the shared `useLoadPlayground` this PR refactored but none of them asserts
 * which MODEL a loaded card lands on, so a resolution regression passes.
 *
 * ## The case a label cannot decide
 *
 * The Gemini and Vertex AI providers serve the same bare model id under the same
 * display label, and the SDKs log the two routes as `google_ai` and
 * `google_vertexai`. So a card loaded from either span shows an identical
 * picker trigger, and only the resolved `{model, provider}` pair tells them
 * apart: Vertex AI's dropdown value carries the `vertex_ai/` route prefix its
 * registry row's `qualified_name` supplies. These tests read that pair out of
 * `PLAYGROUND_STATE` for exactly that reason, and assert the two LABELS are
 * equal so the reason stays visible.
 *
 * ## What is deliberately not asserted here
 *
 * The visibility test covers the Logs trace panel on both the Traces and the
 * Threads tab, the trace-selected case, a non-LLM span and an LLM span whose
 * input is not messages. It does NOT assert the annotation-queue panel: the
 * release report expected the action to be absent there and the staging pass for
 * 2.2.142 found it PRESENT — `TraceDetailsToolbar` gates on `hideAnnotateActions`
 * and only `SMEFlowPage` passes it, while `AnnotationQueuePage` does not, despite
 * the comment beside the gate saying the annotation queue has no active project
 * to open. Whether that is a bug or an accepted product choice is open, so
 * asserting it either way would pin a guess. The SME flow is left out for the
 * mirror reason: it is the case the comment describes working, and it is not
 * what this release changed.
 *
 * Driven through the UI only, because the candidate is `ui`: there is no API
 * behind this at all — the resolution happens in the browser and its result
 * lives in localStorage.
 */
test.describe(
  'Playground — a span loaded from the trace toolbar',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    /** A trace+span seed, several full page loads, and the model catalog. */
    test.setTimeout(240_000);

    test(
      'lands on the span\'s own model when a configured provider offers it',
      { tag: ['@cap:playground.select-model-provider', '@cap:playground.open-span-in-playground'] },
      async ({ playgroundSpanLoads, page }) => {
        const seed = playgroundSpanLoads;
        const span = seed.span('configured-model');
        const logs = new LogsPage(page);
        const playground = new PlaygroundPage(page, seed.projectId);

        const panel = await test.step('Open the LLM span in the Logs trace panel', async () => {
          const opened = await logs.openSpanById(seed.projectId, seed.traceId, span.spanId);
          await opened.waitForFullyLoaded();
          await opened.waitForToolbarReady();
          return opened;
        });

        await test.step('Press Play — a fresh Playground holds nothing, so it loads at once', async () => {
          await panel.clickOpenInPlayground();
          // No confirm dialog: `isPlaygroundEmpty` is true for a context that has
          // never composed anything, which is this test's starting state. Stated
          // as an assertion rather than assumed, because the dialog appearing
          // here would leave the load un-triggered and every assertion below
          // would be about the card that was never replaced.
          await expect(
            panel.loadSpanConfirmDialog,
            'an empty Playground is loaded over without asking',
          ).toHaveCount(0);
          await playground.waitForReady();
        });

        await test.step('The card carries the span\'s messages', async () => {
          // Asserted before the model, because it is what says the LOAD
          // happened: a model assertion over a card that was never replaced
          // could pass on a coincidence.
          expect(
            (await playground.storedMessages()).map((m) => ({
              role: m.role,
              content: m.content,
            })),
            'the span\'s one user message became the card\'s one message',
          ).toEqual([{ role: 'user', content: span.userMessage }]);
        });

        await test.step('And the span\'s own model, on the provider that offers it', async () => {
          expect(
            await playground.selectedModelLabel(0),
            'the picker trigger reads the span\'s model',
          ).toBe(span.expectedModelLabel);
          expect(
            await playground.readStoredModel(),
            'and the card was stored on that model and its provider',
          ).toEqual(span.expectedStored);
        });
      },
    );

    test(
      'resolves google_ai and google_vertexai to different providers behind one label',
      { tag: ['@cap:playground.select-model-provider', '@cap:playground.open-span-in-playground'] },
      async ({ playgroundSpanLoads, page }) => {
        const seed = playgroundSpanLoads;
        const logs = new LogsPage(page);
        const playground = new PlaygroundPage(page, seed.projectId);

        /**
         * Load one span and report what the card ended up on.
         *
         * The Playground is reset first when it already holds a card, so each
         * load starts from the empty state and no confirm dialog intervenes —
         * the dialog is the subject of
         * `playground-span-load-over-unsaved-work.spec.ts`, not of this test.
         */
        const loadAndRead = async (
          key: 'google-ai' | 'google-vertexai',
        ): Promise<{ label: string; stored: { model?: string; provider?: string } }> => {
          const span = seed.span(key);
          return test.step(`Load the ${span.provider} span`, async () => {
            const panel = await logs.openSpanById(seed.projectId, seed.traceId, span.spanId);
            await panel.waitForFullyLoaded();
            await panel.waitForToolbarReady();
            await panel.clickOpenInPlayground();
            await expect(
              panel.loadSpanConfirmDialog,
              'the Playground was empty, so no confirmation was needed',
            ).toHaveCount(0);
            await playground.waitForReady();
            expect(
              (await playground.storedMessages()).map((m) => m.content),
              `the ${span.provider} span's message is the one on the card`,
            ).toEqual([span.userMessage]);
            return {
              label: await playground.selectedModelLabel(0),
              stored: await playground.readStoredModel(),
            };
          });
        };

        const direct = await loadAndRead('google-ai');

        await test.step('Each resolves to the provider its logged name names', async () => {
          expect(
            direct.stored,
            'a span logged as google_ai lands on the Gemini provider, on the bare model id',
          ).toEqual(seed.span('google-ai').expectedStored);
        });

        await test.step('Reset the Playground so the next load needs no confirmation', async () => {
          await playground.resetPlayground();
          await expect(
            playground.variantCards(),
            'the reset left exactly one card',
          ).toHaveCount(1);
        });

        const vertex = await loadAndRead('google-vertexai');

        await test.step('The Vertex AI span lands on Vertex AI, on the route-qualified id', async () => {
          expect(
            vertex.stored,
            'a span logged as google_vertexai lands on the Vertex AI provider',
          ).toEqual(seed.span('google-vertexai').expectedStored);
        });

        await test.step('The two are indistinguishable by label, and distinguishable only by provider', async () => {
          // This is the whole point of the pair. Without it, the two assertions
          // above read as two unrelated facts; together they say that the only
          // thing separating the two cards is the thing a user cannot see, which
          // is why the test reads the store at all.
          expect(
            direct.label,
            'both cards show the same model name',
          ).toBe(vertex.label);
          expect(
            direct.label,
            'which is the label both providers publish for this model',
          ).toBe(seed.sharedGoogleLabel);
          expect(
            direct.stored.provider,
            'yet the providers differ — a label-only assertion could not see this',
          ).not.toBe(vertex.stored.provider);
          expect(
            direct.stored.model,
            'and so do the stored model values, because Vertex AI qualifies the id',
          ).not.toBe(vertex.stored.model);
        });
      },
    );

    test(
      'leaves the last picked model in place when no provider offers the span\'s',
      { tag: ['@cap:playground.select-model-provider', '@cap:playground.open-span-in-playground'] },
      async ({ playgroundSpanLoads, page }) => {
        const seed = playgroundSpanLoads;
        const span = seed.span('unknown-model');
        const logs = new LogsPage(page);
        const playground = new PlaygroundPage(page, seed.projectId);

        await test.step(`Pick ${seed.pickerModel.label} in the Playground, so there is a last picked model`, async () => {
          await playground.goto();
          await playground.waitForReady();
          // Through the picker, from a named provider group: choosing a model is
          // what writes `playground-last-picked-model`, and that key is the
          // fallback under test. Planting it in localStorage would test the
          // fallback over a value no gesture produced.
          await playground.selectModelFromProvider(
            0,
            seed.pickerModel.providerGroup,
            seed.pickerModel.label,
          );
          expect(
            await playground.readStoredModel(),
            'the pick landed on the card, so it is also the last picked model',
          ).toEqual({ model: seed.pickerModel.id, provider: seed.pickerModel.provider });
        });

        await test.step('Load the span whose model no provider offers', async () => {
          const panel = await logs.openSpanById(seed.projectId, seed.traceId, span.spanId);
          await panel.waitForFullyLoaded();
          await panel.waitForToolbarReady();
          await panel.clickOpenInPlayground();
          // Picking a model leaves the card's MESSAGES blank, so the Playground
          // is still "empty" by `isPlaygroundEmpty`'s own rule and no
          // confirmation is raised. Asserted, because a dialog here would stall
          // the load and the model assertion below would pass over a card the
          // span never touched.
          await expect(
            panel.loadSpanConfirmDialog,
            'a card with a model but no messages still counts as an empty Playground',
          ).toHaveCount(0);
          await playground.waitForReady();
        });

        await test.step('The load happened', async () => {
          // The control for the assertion below. "The picker still shows the
          // last picked model" is trivially true of a load that never ran, so
          // the messages have to prove it did.
          expect(
            (await playground.storedMessages()).map((m) => ({
              role: m.role,
              content: m.content,
            })),
            'the unresolvable-model span\'s message is on the card',
          ).toEqual([{ role: 'user', content: span.userMessage }]);
        });

        await test.step('And the picker kept the last picked model rather than emptying', async () => {
          expect(
            await playground.selectedModelLabel(0),
            'the card is still on the model the user last chose',
          ).toBe(seed.pickerModel.label);
          expect(
            await playground.readStoredModel(),
            'stored as that model and provider — not as the span\'s unresolvable one',
          ).toEqual({ model: seed.pickerModel.id, provider: seed.pickerModel.provider });
          // Named explicitly, because it is the rejected answer: a build that
          // adopted an unoffered model would store it here and the picker would
          // have nothing to render.
          expect(
            (await playground.readStoredModel()).model,
            'the span\'s own model was NOT adopted',
          ).not.toBe(span.model);
        });
      },
    );

    test(
      'offers the Play action only on an LLM span whose input is messages',
      { tag: ['@cap:playground.open-span-in-playground'] },
      async ({ playgroundSpanLoads, page }) => {
        const seed = playgroundSpanLoads;
        const logs = new LogsPage(page);

        /**
         * Open one selection and report how many Play actions the toolbar holds.
         *
         * The toolbar is waited on through its always-present "Add to" control
         * first, so a count of 0 is a verdict about the Play button rather than
         * about a toolbar that had not rendered — `TraceDetailsToolbar` renders a
         * `Skeleton` and nothing else while `isLoading`.
         */
        const playActionsFor = async (
          what: string,
          open: () => Promise<TracePanelPage>,
        ): Promise<number> =>
          test.step(`Count the Play actions with ${what} selected`, async () => {
            const panel = await open();
            await panel.waitForFullyLoaded();
            await panel.waitForToolbarReady();
            return panel.openInPlaygroundButton.count();
          });

        await test.step('Present on an LLM span with messages, over the Traces tab', async () => {
          const span = seed.span('configured-model');
          expect(
            await playActionsFor('an llm span over the Traces tab', () =>
              logs.openSpanById(seed.projectId, seed.traceId, span.spanId, {
                logsType: 'traces',
              }),
            ),
            'exactly one Play action — this is the case the feature exists for',
          ).toBe(1);
        });

        await test.step('Present on the same span over the Threads tab', async () => {
          // The other layout that mounts this toolbar. The trace is seeded with
          // a `thread_id`, so this is the panel a user reaches from a
          // conversation rather than a trace-shaped URL dressed up as one.
          const span = seed.span('configured-model');
          expect(
            await playActionsFor('an llm span over the Threads tab', () =>
              logs.openSpanById(seed.projectId, seed.traceId, span.spanId, {
                logsType: 'threads',
              }),
            ),
            'the action does not depend on which table the panel was opened over',
          ).toBe(1);
        });

        await test.step('Absent when only the TRACE is selected', async () => {
          // `canOpenSpanInPlayground` gates on `"type" in data && data.type ===
          // llm`, and a trace has no `type`. The trace's input IS messages, so
          // this is a statement about that gate rather than about an unreadable
          // payload.
          expect(
            await playActionsFor('the trace itself', () => logs.openTraceById(seed.traceId)),
            'a trace is not a span, however loadable its payload looks',
          ).toBe(0);
        });

        await test.step('Absent on a span that is not an LLM span', async () => {
          const span = seed.span('not-an-llm-span');
          expect(
            await playActionsFor('a general span carrying messages', () =>
              logs.openSpanById(seed.projectId, seed.traceId, span.spanId),
            ),
            'the type gate hides the action even for a perfectly readable messages input',
          ).toBe(0);
        });

        await test.step('Absent on an LLM span whose input is not messages', async () => {
          const span = seed.span('llm-without-messages');
          expect(
            await playActionsFor('an llm span with a bare prompt input', () =>
              logs.openSpanById(seed.projectId, seed.traceId, span.spanId),
            ),
            'the payload gate hides the action even on an llm span — there is nothing to load',
          ).toBe(0);
        });
      },
    );
  },
);
