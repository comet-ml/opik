import { test, expect, REGISTRY_MODEL, EFFORT_REFUSED_OPENAI_MODELS } from '@e2e/fixtures';
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
 * ## Why this release
 *
 * opik#9608's provider-model sync appended `gpt-6.2-astra` and `gpt-6.2-sol` to
 * `llm-models-default.yaml` as bare `id` + `label`, with no `reasoning: true`
 * and no `params:` block, while every sibling `gpt-6.x` / `gpt-5.6.x` OpenAI row
 * carries both. The served catalog reflects it — both rows answer
 * `effort: {accepted: false}` — and both model-settings panels agree with it by
 * mounting no effort control at all. **That agreement is what this spec pins.**
 *
 * Whether the two rows SHOULD accept effort is a question for whoever owns the
 * catalog, and is deliberately not asserted here: the release's exploration
 * found the same two ids routed through OpenRouter DO carry five levels, which
 * makes the native rows look like an omission rather than a vendor fact — but
 * "look like" is not a product rule, and a spec that pinned one would fail the
 * day the catalog is corrected AND the day it is confirmed as intended. The
 * invariant below survives either answer: correct the yaml and the panel must
 * grow the control, leave it and the panel must not.
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
 * `EFFORT_REFUSED_OPENAI_MODELS`.
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
 * A row that refused effort long before this release, beside the two that
 * started doing so in it.
 *
 * The control for a reading of the negative test that would otherwise be
 * available: that the two new rows are special somehow — too new for the
 * frontend to place, or unlisted, or mis-parsed. `gpt-4o-mini` is an ordinary
 * non-reasoning OpenAI model the suite drives everywhere, and it renders
 * exactly the same way, which is what makes "no control" the correct rendering
 * of a refused row rather than a symptom.
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
      'a row whose curated effort is refused mounts no control at all, including both rows 2.2.148 added',
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

        // The two rows opik#9608 shipped, plus the long-standing control that
        // makes their rendering ordinary rather than peculiar to them.
        const refused = [...EFFORT_REFUSED_OPENAI_MODELS, EFFORT_REFUSED_CONTROL];

        const playground = new PlaygroundPage(page, project.id);
        await test.step('Open the playground', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        for (const { displayName, catalogId } of refused) {
          const row = rowFor(catalog, catalogId);
          expectLabelPinned(row, displayName);

          await test.step(`${displayName}: the catalog refuses the parameter`, async () => {
            // The premise, and here it is half the test: if one of these rows
            // ever starts accepting effort, "the panel shows no control" stops
            // being correct behaviour and becomes the bug. That is the shape the
            // gpt-6.2 rows would take if the yaml is corrected — and this is
            // where the spec would then fail, which is the point of reading the
            // catalog at run time rather than pinning the answer.
            expect(
              row.effort.accepted,
              `'${catalogId}' is in this test because its curated row sets effort.accepted ` +
                'false — a row that now accepts it must grow a control, and needs a human',
            ).toBe(false);
            expect(
              row.effort.levels,
              'and a row that refuses the parameter curates no levels to offer',
            ).toEqual([]);
          });

          await test.step(`${displayName}: the panel mounts no effort control`, async () => {
            await playground.selectModel(0, displayName);
            await playground.openModelParameters(0);

            // Zero, not "not the five levels": `supportsOpenAIReasoningEffort`
            // returning false takes the control out of the DOM rather than
            // emptying it, and a control rendered with no options would be a
            // different build — one where a user can open a dropdown that
            // offers nothing.
            await expect(
              playground.reasoningEffortTrigger(),
              `the panel must mount no effort control for ${displayName}, whose curated row ` +
                'refuses the parameter',
            ).toHaveCount(0);

            // The panel really did render, so the absence above is the control
            // being gone and not the panel having failed to open. Max output
            // tokens is on every OpenAI panel, refused effort or not.
            await expect(
              playground.modelParameterLabel('Max output tokens'),
              'the parameters panel itself must have rendered, or "no effort control" says nothing',
            ).toBeVisible();

            await playground.closeModelParameters();
          });

          expectPinApplied(pin);
        }

        await test.step('…and a row that DOES accept effort still mounts one, in the same panel', async () => {
          // The positive control, inside the same test and the same page. Without
          // it every assertion above is satisfied by a build whose effort control
          // never renders for any OpenAI model at all — which is a far bigger
          // regression than the one this test is about, and would read as five
          // green steps.
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
              'proving the absences above are per-row and not a panel that lost the control',
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
