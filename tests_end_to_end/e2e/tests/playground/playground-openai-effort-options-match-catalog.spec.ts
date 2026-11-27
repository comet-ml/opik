import { test, expect, REGISTRY_MODEL, EFFORT_CORRECTED_OPENAI_MODELS } from '@e2e/fixtures';
import type { LlmCatalogModelRef } from '@e2e/core/backend';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { expectPinApplied, pinOpenAiPipelineMode } from '@e2e/core/openai-pipeline-mode';

/**
 * The playground's OpenAI Reasoning effort control against the curated catalog
 * it is built from — the OpenAI half of the triangle
 * `playground-anthropic-effort-options-match-catalog.spec.ts` closes for
 * Anthropic.
 *
 * `GET /v1/private/llm/models` is not a second opinion about the panel: the
 * frontend's `modelRegistryStore` OVERWRITES its in-tree `PROVIDER_MODELS`
 * constant with this endpoint's answer on mount, and
 * `supportsOpenAIReasoningEffort` / `getOpenAIReasoningEffortOptions` then read
 * the row's `params.effort` out of it (`getOpenAIListedParams`). So the catalog
 * is the DATA the control is generated from, and the only reason panel and
 * catalog can disagree is the frontend failing to read what it was served —
 * which is exactly the drift this spec exists to catch.
 *
 * ## Why this release, and what changed in 2.2.151
 *
 * opik#9608's provider-model sync (2.2.148) appended `gpt-6.2-astra` and
 * `gpt-6.2-sol` to `llm-models-default.yaml` as bare `id` + `label`, with no
 * `reasoning: true` and no `params:` block, while every sibling `gpt-6.x` /
 * `gpt-5.6.x` OpenAI row carried both. The served catalog reflected it — both
 * rows answered `effort: {accepted: false}` — and both model-settings panels
 * agreed by mounting no effort control at all.
 *
 * Whether the two rows SHOULD accept effort was left to whoever owns the
 * catalog, and the invariant this spec pins was written to survive either
 * answer: **correct the yaml and the panel must grow the control, leave it and
 * the panel must not.** opik#9648's sync, in 2.2.151, corrected it — both rows
 * now carry their siblings' block, `effort: {accepted: true, levels: [low,
 * medium, high, xhigh], default: medium}`. So the claim on these two ids has
 * flipped, and the second test below now asserts the other branch: the catalog
 * accepts, so the panel must mount a control offering exactly those levels.
 *
 * The long-standing refused row (`gpt-4o-mini`) is unchanged and still carries
 * the negative half, which is what keeps the two readings apart — "the panel
 * reads the catalog" rather than "the panel always mounts a control now".
 *
 * ## Why this is not already covered
 *
 * `playground-anthropic-effort-options-match-catalog.spec.ts` is the same
 * question for ANTHROPIC, whose options come from a different frontend table
 * (`ANTHROPIC_MODEL_CAPABILITIES`) read by a different helper. The OpenAI specs
 * that drive this control all pin ONE model and assert a literal option list —
 * `playground-openai-effort-pipeline-mode.spec.ts` pins `GPT 5.6 Sol` and
 * `GPT 5.5`, `playground-reasoning-model-parameters.spec.ts` pins `GPT 5 Mini` —
 * so each asserts that one row's levels, never that the panel is reading the
 * catalog it was handed. None of the 11 ids this release added is touched by any
 * spec in the estate.
 *
 * ## There is no OpenAI gate to close the triangle against
 *
 * The Anthropic spec reads a third oracle — `AnthropicEffort.validateEffort`'s
 * own 400 — and so can claim `@cap:playground.model-parameter-gate`. **The
 * OpenAI branch has no such gate.** Probed against staging 2.2.148, a
 * `custom_parameters.reasoning_effort` of `qa-not-an-effort-level` on
 * `gpt-6.1-sol`, `gpt-6.2-astra` and `gpt-4o-mini` alike is not refused during
 * request mapping at all: the request reaches OpenAI and comes back as the
 * provider's own 401 on the workspace key. So both tests here are tagged
 * `configure-model-settings` — the PANEL — and the parameter-gate capability's
 * note stays honest in saying its OpenAI branch is uncovered. Adding a gate is
 * a product decision, not something a spec can assert into existence.
 *
 * ## Why the pipeline mode is pinned
 *
 * `getOpenAIReasoningEffortOptions` takes the row's `routes.responses_api.effort`
 * instead of its base `effort` when the workspace key is on the Responses API,
 * and several rows here differ between the two — `gpt-6.1-sol` adds `max` on
 * that route. Unpinned, the oracle would depend on a shared workspace key's
 * stored mode, which any human can change. Pinned to `chat_completions_api` the
 * oracle is exactly `params.effort`, which is what `getLlmCatalogModels` reads.
 * `expectPinApplied` is what stops the whole spec passing vacuously on a
 * workspace with no OpenAI key, where the pin is a no-op.
 *
 * ## Cost
 *
 * Nothing reaches a provider. The panel is read without ever running: no
 * completion is submitted, so the only OpenAI traffic a Run would cause never
 * happens. The two `gpt-6.2` ids are on `llm-model-policy`'s forbidden list and
 * are registered unbilled by `modelRegistryProviders` on that basis — see
 * `EFFORT_CORRECTED_OPENAI_MODELS`.
 */

/** The catalog's lowercase levels as `OPENAI_EFFORT_LABELS` in `modelUtils.ts` renders them. */
const EFFORT_LABELS: Record<string, string> = {
  none: 'None',
  minimal: 'Minimal',
  low: 'Low',
  medium: 'Medium',
  high: 'High',
  xhigh: 'xHigh',
  max: 'Max',
};

/**
 * The rows driven through the panel whose curated row ACCEPTS effort, by
 * display name and by the catalog id the same model answers to.
 *
 * Both halves are pinned for the reason the Anthropic spec gives: the two
 * tables under test are keyed differently, and the id is what the catalog read
 * is looked up by while the label is what the picker is driven by.
 *
 * Two rows, not one, and chosen for DIFFERENT level sets — `gpt-5-mini` curates
 * `minimal,low,medium,high` and `gpt-5.5` curates `none,low,medium,high,xhigh`.
 * A single row could not tell "the panel reads the catalog" apart from "the
 * panel happens to render a list that matches it": the two sets differ in
 * length, in membership and in their first element, so one hardcoded list
 * cannot satisfy both.
 *
 * Both are already on `llm-model-policy`'s unbilled registry through
 * `REGISTRY_MODEL`, and both are the cheapest member of their class by that
 * map's own rule.
 */
const EFFORT_ACCEPTED_MODELS = [
  { displayName: REGISTRY_MODEL.openAiReasoning, catalogId: 'gpt-5-mini' },
  { displayName: REGISTRY_MODEL.openAiEffortWithoutMax, catalogId: 'gpt-5.5' },
] as const;

/**
 * A row that refuses effort, and has since long before either release.
 *
 * The negative half of the second test, and the reason that test still
 * discriminates now that the two `gpt-6.2` rows accept the parameter: without
 * a row the panel must mount NO control for, "the panel mounts a control
 * offering the catalog's levels" is equally satisfied by a build that mounts
 * one for every OpenAI model and reads the catalog for none of them.
 *
 * `gpt-4o-mini` is an ordinary non-reasoning OpenAI model the suite drives
 * everywhere, which is what makes "no control" the correct rendering of a
 * refused row rather than something peculiar to it.
 *
 * On `ALLOWED_MODEL_DISPLAY_NAMES` outright, unlike the two `gpt-6.2` ids.
 */
const EFFORT_REFUSED_CONTROL = { displayName: REGISTRY_MODEL.openAiStandard, catalogId: 'gpt-4o-mini' };

/** The curated row for `id`, failing rather than returning undefined. */
function rowFor(catalog: LlmCatalogModelRef[], id: string): LlmCatalogModelRef {
  const row = catalog.find((model) => model.id === id);
  // Not an optional chain and not a `?? fallback`: a spec whose oracle is
  // missing must stop, because every comparison below it would otherwise be
  // made against a default and pass having checked nothing.
  expect(row, `the catalog must carry a curated row for '${id}'`).toBeDefined();
  return row!;
}

/** The catalog's levels for a row, as the panel would label them. */
function expectedPanelOptions(row: LlmCatalogModelRef): string[] {
  return row.effort.levels.map((level) => {
    const label = EFFORT_LABELS[level];
    // A level the catalog curates that the frontend has no label for is
    // dropped from the control entirely by `toOpenAIReasoningEffort`, so it
    // would read here as the panel offering one option fewer. That is a drift
    // of its own rather than a mapping gap to paper over.
    expect(label, `the frontend must label the curated level '${level}'`).toBeDefined();
    return label!;
  });
}

/**
 * The display label the catalog serves for a row, asserted against the name
 * this spec drives the picker by.
 *
 * The pinned label is not decoration: it is what `llm-model-policy` matches on,
 * so it cannot be read out of the catalog at run time. Asserting the two agree
 * is what turns a re-labelled row into a loud failure here instead of a picker
 * selecting some other model under the old name.
 */
function expectLabelPinned(row: LlmCatalogModelRef, displayName: string): void {
  expect(
    row.label,
    `the catalog's label for '${row.id}' must still be the name this spec drives the picker ` +
      'by — a re-labelled row needs a human to re-pin it, not a silently different selection',
  ).toBe(displayName);
}

test.describe(
  'Playground — the OpenAI reasoning-effort control against the curated catalog',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'the control offers exactly the levels each curated row accepts, for two rows with different level sets',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page, backendClient }) => {
        test.setTimeout(180_000);
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry must be selectable, or the picker offers none of these models',
        ).toContain('openai');

        const pin = await pinOpenAiPipelineMode(page, 'chat_completions_api');

        const catalog = await test.step('Read the curated OpenAI catalog', async () => {
          const rows = await backendClient.getLlmCatalogModels('openai');
          expect(
            rows.length,
            'the catalog must describe some OpenAI models, or there is no oracle',
          ).toBeGreaterThan(0);
          return rows;
        });

        const playground = new PlaygroundPage(page, project.id);
        await test.step('Open the playground', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        const offeredPerModel: string[][] = [];

        for (const { displayName, catalogId } of EFFORT_ACCEPTED_MODELS) {
          const row = rowFor(catalog, catalogId);
          expectLabelPinned(row, displayName);

          // The premise. Each of these rows is in this test because it accepts
          // effort; a row that stopped accepting it would make the comparison
          // below trivially true (empty == empty) rather than failing, and
          // needs a human to re-pick the model.
          expect(
            row.effort.accepted,
            `'${catalogId}' is in this test because its curated row accepts effort`,
          ).toBe(true);
          expect(
            row.effort.levels.length,
            `and a row that accepts the parameter must curate levels to offer`,
          ).toBeGreaterThan(0);

          const expected = expectedPanelOptions(row);

          await test.step(`${displayName}: the panel offers the catalog's levels, in order`, async () => {
            await playground.selectModel(0, displayName);
            await playground.openModelParameters(0);

            // The whole list and its ORDER, not a membership check: the control
            // renders the levels in the order the row curates them, and a panel
            // that offered the right set in the wrong order would show a user a
            // scale running the wrong way.
            expect(
              await playground.reasoningEffortOptions(),
              `the panel must offer exactly what the catalog curates for '${catalogId}' — it is ` +
                'generated from that row, so any difference is the frontend not reading what ' +
                'it was served',
            ).toEqual(expected);

            offeredPerModel.push(expected);
            await playground.closeModelParameters();
          });

          expectPinApplied(pin);
        }

        await test.step('The two rows really did pose different questions', async () => {
          // What stops this test passing over a panel that renders one
          // hardcoded list. Asserted after the fact rather than as a pin on the
          // literal sets, so a catalog regen that moves either row's levels
          // keeps the test meaningful instead of failing on data it does not
          // own — but a regen that collapsed the two onto one list fails here,
          // loudly, rather than quietly weakening every assertion above.
          expect(offeredPerModel, 'both accepting rows were driven').toHaveLength(
            EFFORT_ACCEPTED_MODELS.length,
          );
          expect(
            new Set(offeredPerModel.map((options) => options.join('|'))).size,
            `the two curated rows must offer two DIFFERENT level sets, or one hardcoded list ` +
              `would satisfy both — got ${JSON.stringify(offeredPerModel)}`,
          ).toBe(offeredPerModel.length);
        });
      },
    );

    test(
      'a row whose curated effort is refused mounts no control at all, while the two rows 2.2.151 corrected now mount one',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page, backendClient }) => {
        test.setTimeout(240_000);
        expect(modelRegistryProviders.providers).toContain('openai');

        const pin = await pinOpenAiPipelineMode(page, 'chat_completions_api');

        const catalog = await test.step('Read the curated OpenAI catalog', async () => {
          const rows = await backendClient.getLlmCatalogModels('openai');
          expect(rows.length, 'the catalog must describe some OpenAI models').toBeGreaterThan(0);
          return rows;
        });

        const playground = new PlaygroundPage(page, project.id);
        await test.step('Open the playground', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        for (const { displayName, catalogId } of EFFORT_CORRECTED_OPENAI_MODELS) {
          const row = rowFor(catalog, catalogId);
          expectLabelPinned(row, displayName);

          const expected = await test.step(`${displayName}: the catalog now ACCEPTS the parameter`, async () => {
            // The premise, and here it is half the test. These two rows carried
            // no `params.effort` at all in 2.2.148 and the panel correctly
            // mounted no control; opik#9648 gave them their siblings' block, so
            // "no control" has stopped being correct behaviour and become the
            // bug. Read at run time rather than pinned, so the next sync that
            // moves them back fails here — on the premise — instead of in the
            // rendering assertion below, where it would read as a frontend
            // regression.
            expect(
              row.effort.accepted,
              `'${catalogId}' is in this test because 2.2.151's sync gave its curated row an ` +
                'effort block — a row that refuses it again must mount no control, and needs a human',
            ).toBe(true);
            expect(
              row.effort.levels.length,
              'and a row that accepts the parameter must curate levels to offer',
            ).toBeGreaterThan(0);
            return expectedPanelOptions(row);
          });

          await test.step(`${displayName}: the panel mounts a control offering the catalog's levels`, async () => {
            await playground.selectModel(0, displayName);
            await playground.openModelParameters(0);

            await expect(
              playground.reasoningEffortTrigger(),
              `the panel must mount exactly one effort control for ${displayName}, whose curated ` +
                'row now accepts the parameter',
            ).toHaveCount(1);

            // The option list, not merely the control's presence: a control
            // mounted from some default list would satisfy a presence check
            // while still not reading the row it was generated from, which is
            // the drift this spec exists to catch. Order included, for the
            // reason the first test gives.
            expect(
              await playground.reasoningEffortOptions(),
              `the panel must offer exactly what the catalog curates for '${catalogId}'`,
            ).toEqual(expected);

            await playground.closeModelParameters();
          });

          expectPinApplied(pin);
        }

        await test.step(`${EFFORT_REFUSED_CONTROL.displayName}: the catalog refuses the parameter`, async () => {
          // The negative half. Without a row the panel must mount NO control
          // for, every assertion above is satisfied by a build that mounts one
          // for every OpenAI model — which would be the same drift in the
          // opposite direction.
          const row = rowFor(catalog, EFFORT_REFUSED_CONTROL.catalogId);
          expectLabelPinned(row, EFFORT_REFUSED_CONTROL.displayName);
          expect(
            row.effort.accepted,
            `'${EFFORT_REFUSED_CONTROL.catalogId}' is the negative control here because its row ` +
              'refuses effort — a row that now accepts it needs a human to re-pick the control',
          ).toBe(false);
          expect(
            row.effort.levels,
            'and a row that refuses the parameter curates no levels to offer',
          ).toEqual([]);
        });

        await test.step(`${EFFORT_REFUSED_CONTROL.displayName}: the panel mounts no effort control`, async () => {
          await playground.selectModel(0, EFFORT_REFUSED_CONTROL.displayName);
          await playground.openModelParameters(0);

          // Zero, not "not the five levels": `supportsOpenAIReasoningEffort`
          // returning false takes the control out of the DOM rather than
          // emptying it, and a control rendered with no options would be a
          // different build — one where a user can open a dropdown that
          // offers nothing.
          await expect(
            playground.reasoningEffortTrigger(),
            `the panel must mount no effort control for ${EFFORT_REFUSED_CONTROL.displayName}, ` +
              'whose curated row refuses the parameter',
          ).toHaveCount(0);

          // The panel really did render, so the absence above is the control
          // being gone and not the panel having failed to open. Max output
          // tokens is on every OpenAI panel, refused effort or not.
          await expect(
            playground.modelParameterLabel('Max output tokens'),
            'the parameters panel itself must have rendered, or "no effort control" says nothing',
          ).toBeVisible();

          await playground.closeModelParameters();
          expectPinApplied(pin);
        });

        await test.step('…and switching back to an accepting row mounts the control again', async () => {
          // The closing control, inside the same test and the same page. The
          // refused row above is the LAST model selected, so without this step
          // "no control for gpt-4o-mini" is equally satisfied by a panel whose
          // effort control stopped rendering for anything after a model switch
          // — a larger regression than the one this test is about, and one that
          // would read as green steps. Driven on a row neither 9608 nor 9648
          // touched, so it cannot move with the same sync.
          const { displayName, catalogId } = EFFORT_ACCEPTED_MODELS[0];
          const row = rowFor(catalog, catalogId);
          expectLabelPinned(row, displayName);
          expect(
            row.effort.accepted,
            `'${catalogId}' is the positive control here because its row accepts effort`,
          ).toBe(true);

          await playground.selectModel(0, displayName);
          await playground.openModelParameters(0);
          await expect(
            playground.reasoningEffortTrigger(),
            `${displayName} accepts effort, so this panel must mount exactly one control — ` +
              'proving the absence above is per-row and not a panel that lost the control',
          ).toHaveCount(1);
          expect(
            await playground.reasoningEffortOptions(),
            'and it offers that row’s curated levels',
          ).toEqual(expectedPanelOptions(row));
          await playground.closeModelParameters();
        });
      },
    );
  },
);
