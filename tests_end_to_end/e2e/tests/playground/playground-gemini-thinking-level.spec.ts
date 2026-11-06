import { test, expect, PROVIDER_GROUP, REGISTRY_MODEL } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { captureCompletionBody, expectPlaygroundIdle } from '@e2e/core/playground-completions';

/**
 * The Gemini and Vertex AI "Thinking level" control: what it offers, that it
 * keeps what was picked, and that the request carries it (opik#9198 /
 * OPIK-8927).
 *
 * The level is Opik's own field, and no provider accepts it at the top level of
 * a chat-completions body — the backend deserialises the request into
 * langchain4j's `ChatCompletionRequest`, which drops unknown top-level fields
 * silently. So `sanitizeConfigForRequest` has to delete the flat
 * `thinkingLevel` and re-nest it under `custom_parameters.thinking.level`, the
 * only free-form slot the request captures. Three things can go wrong there and
 * each is invisible on screen:
 *
 *  - the level is sent flat, is dropped, and the model thinks at its own
 *    default while the panel says otherwise;
 *  - `Auto` sends a level anyway, pinning a budget over Google's own dynamic
 *    default — thinking tokens nobody asked for, billed;
 *  - the panel forgets the pick between openings, so a user who turned thinking
 *    off watches it come back.
 *
 * All three are silent and all three cost money in one direction or the other.
 *
 * ## Why nothing here was covered
 *
 * `playground.configure-model-settings` has specs for the OpenAI reasoning
 * effort, the OpenRouter nested `reasoning` object and the Anthropic thinking
 * effort — three different providers' versions of the same control, each with
 * its own wire shape — and none for the Gemini family's.
 * `playground-reasoning-model-parameters.spec.ts` is the one spec that drives
 * Gemini 2.5 Flash on both provider groups, and its subject is temperature and
 * `top_p`: it never opens the thinking control, so a drifted level set or a
 * level that never reaches the body passes it.
 *
 * ## Why both provider groups
 *
 * The two offer this model under ONE display name ("Gemini 2.5 Flash") and
 * differ in the id the request must name — `gemini-2.5-flash` against
 * `vertex_ai/gemini-2.5-flash`. Every helper deciding the control's shape keys
 * off that id (`isVertexModel` is a `startsWith('vertex_ai/')` test, and the
 * panel presentation is `GEMINI_PANEL` or `VERTEX_AI_PANEL`), so the two can
 * regress apart — which is also why each case names its provider group
 * explicitly rather than searching for the display name.
 *
 * ## Why the option list is pinned literally
 *
 * For a Gemini 2.5 model the levels do NOT come from the curated catalog, so
 * there is no catalog oracle to compare against the way
 * `playground-anthropic-effort-options-match-catalog.spec.ts` has one: the
 * catalog's `gemini-2.5-flash` row curates `params` with no `effort` block at
 * all, and `getThinkingLevelOptions` therefore falls through to the frontend's
 * own `THINKING_LEVELS_BY_MODEL`. That table is a transcription of Google's two
 * published tables and carries its reasoning inline — 2.5 takes a numeric
 * `thinking_budget` rather than a level, so these five are translated
 * server-side, and they lead with `auto` because an unset budget is how
 * Google's default works. Comparing the panel against the catalog here would
 * assert `[] === []`.
 *
 * ## Cost
 *
 * Nothing reaches a provider. `captureCompletionBody` answers
 * `POST /v1/private/chat/completions` in the browser, so every Run below is
 * observed as a request and never generates anything; the provider keys the
 * `modelRegistryProviders` fixture ensures exist are only what makes the picker
 * offer these models at all.
 */

/** The five levels Gemini 2.5 Flash offers, in panel order, as `THINKING_LEVEL_LABELS` renders them. */
const EXPECTED_LEVELS = ['Auto', 'Off', 'Low', 'Medium', 'High'] as const;

/**
 * The level the control shows before anything is picked.
 *
 * Not a guess and not merely "the first option": `DEFAULT_THINKING_LEVEL_BY_MODEL`
 * declares `auto` for both spellings of 2.5 Flash, and the request builder sends
 * whatever the control DISPLAYS — so a default that drifted to a named level
 * would start charging for thinking on a model a user never configured.
 */
const DEFAULT_LEVEL = 'Auto';

/** The two picks driven below: one that must be sent, and one that proves it is not a constant. */
const OFF = { label: 'Off', wire: 'off' } as const;
const LOW = { label: 'Low', wire: 'low' } as const;

/**
 * The `thinking` object the body nests under `custom_parameters`, or
 * `undefined` when the body carries none.
 *
 * Returns the whole object rather than just its `level`, so an assertion can
 * say the block holds EXACTLY the level and nothing else — a stray
 * `budget_tokens` beside it outranks the level server-side, which is the
 * failure `sanitizeConfigForRequest` strips it to prevent.
 */
function nestedThinking(body: Record<string, unknown>): unknown {
  const custom = body['custom_parameters'];
  if (custom === null || typeof custom !== 'object') return undefined;
  return (custom as Record<string, unknown>)['thinking'];
}

for (const [groupLabel, group, provider, modelId] of [
  ['Gemini', PROVIDER_GROUP.gemini, 'gemini', 'gemini-2.5-flash'],
  ['Vertex AI', PROVIDER_GROUP.vertexAi, 'vertex-ai', 'vertex_ai/gemini-2.5-flash'],
] as const) {
  test.describe(
    `Playground — the ${groupLabel} thinking level control`,
    { tag: ['@t2-cuj', '@area:playground'] },
    () => {
      test(
        `offers Google's five levels, defaults to Auto, keeps a pick across reopening, and sends it nested under custom_parameters`,
        { tag: ['@cap:playground.configure-model-settings'] },
        async ({ modelRegistryProviders, project, page }) => {
          // Three Runs with a panel edit between each, plus the picker's own
          // remount-retry loop — past the 90s default.
          test.setTimeout(240_000);

          expect(
            modelRegistryProviders.providers,
            `the ${groupLabel} registry must be selectable, or the picker offers this model at all`,
          ).toContain(provider);

          const playground = new PlaygroundPage(page, project.id);

          await test.step(`Open the playground on ${REGISTRY_MODEL.gemini2} under ${groupLabel}`, async () => {
            await playground.goto();
            await playground.waitForReady();
            // By provider group, not by display name: both groups offer this
            // model under the same label, and the id the body must name is the
            // only thing that differs.
            await playground.selectModelFromProvider(0, group, REGISTRY_MODEL.gemini2);
          });

          await test.step('The control is labelled "Thinking level" and offers exactly the five levels', async () => {
            await playground.openModelParameters(0);

            // The LABEL, asserted in its own right. The same single combobox
            // serves three providers' controls and each panel titles it
            // differently — "Reasoning effort" for OpenAI, "Effort" or
            // "Thinking effort" for Anthropic, "Thinking level" here — so the
            // title is what says the Gemini-family presentation was selected.
            // Exact, so a longer label cannot satisfy it.
            await expect(
              playground.modelParameterLabel('Thinking level'),
              `the ${groupLabel} panel titles this control "Thinking level"`,
            ).toHaveCount(1);

            // The whole list and its order, not a membership check: Off and
            // Auto mean different things on the wire (Auto sends nothing, Off
            // sends a level), and the two assertions further down depend on
            // both being offered. A build that had lost one of them would
            // otherwise fail later, as a missing option in a dropdown, rather
            // than here.
            expect(
              await playground.thinkingLevelOptions(),
              `the ${groupLabel} control must offer Google's documented set for 2.5 Flash, in order`,
            ).toEqual([...EXPECTED_LEVELS]);

            expect(
              await playground.readThinkingLevel(),
              'an untouched panel displays the model’s own default, and the request sends what ' +
                'the panel displays — so a drifted default bills for thinking nobody configured',
            ).toBe(DEFAULT_LEVEL);

            await playground.closeModelParameters();
          });

          await test.step('On the untouched Auto the body carries no thinking setting at all', async () => {
            await playground.fillUserMessage('Reply with the single word OK.');
            const body = await captureCompletionBody(page, () => playground.clickRun());

            expect(
              body.model,
              `the request names this provider's spelling of the model, which is what every ` +
                'thinking-level helper keys off',
            ).toBe(modelId);
            expect(
              nestedThinking(body),
              'Auto leaves how much to think to the model, so it must send neither a level nor ' +
                'a budget — sending one pins a budget over Google’s dynamic default and bills ' +
                'for thinking the user did not ask for',
            ).toBeUndefined();
            expect(
              Object.keys(body),
              'and never a flat thinking_level, which langchain4j drops silently — the level ' +
                'would be ignored while the panel claimed it was set',
            ).not.toContain('thinking_level');

            await expectPlaygroundIdle(page);
          });

          await test.step(`Picking ${OFF.label} survives closing and reopening the panel`, async () => {
            // The persistence half, and a separate failure from the wire shape:
            // `PromptModelSettings` remounts on every open, and a pick that was
            // not written through to the stored config reads back as the
            // model's default — so a user who turned thinking off sees it come
            // back with no indication anything was lost.
            await playground.openModelParameters(0);
            await playground.selectThinkingLevel(OFF.label);
            await playground.closeModelParameters();

            await playground.openModelParameters(0);
            expect(
              await playground.readThinkingLevel(),
              `the panel must still read ${OFF.label} after being closed and reopened`,
            ).toBe(OFF.label);
            await playground.closeModelParameters();
          });

          await test.step(`…and the body then nests { level: "${OFF.wire}" } under custom_parameters`, async () => {
            const body = await captureCompletionBody(page, () => playground.clickRun());

            expect(body.model, 'still the same model').toBe(modelId);
            // The whole object, so a `budget_tokens` left beside the level
            // fails: the backend lets a budget outrank the level, which would
            // leave thinking ON under Off while the panel showed Off.
            expect(
              nestedThinking(body),
              `Off is a real level for a Gemini 2.x model (only 3.x and later cannot turn ` +
                'thinking off), so it must reach the model — and nothing else may ride along',
            ).toEqual({ level: OFF.wire });
            expect(
              Object.keys(body),
              'and still nothing flat',
            ).not.toContain('thinking_level');

            await expectPlaygroundIdle(page);
          });

          await test.step(`Picking ${LOW.label} sends { level: "${LOW.wire}" }, so the level is not a constant`, async () => {
            // The discriminator for the step above. "The body carries
            // { level: 'off' }" is equally satisfied by a build that sends one
            // fixed level whatever the panel shows — which is the shape a
            // half-applied refactor leaves behind, and the one that is worst on
            // a bill.
            await playground.openModelParameters(0);
            await playground.selectThinkingLevel(LOW.label);
            await playground.closeModelParameters();

            const body = await captureCompletionBody(page, () => playground.clickRun());

            expect(
              nestedThinking(body),
              `a stale { level: "${OFF.wire}" } here would mean the panel's pick never reaches ` +
                'the request at all',
            ).toEqual({ level: LOW.wire });
          });
        },
      );
    },
  );
}
