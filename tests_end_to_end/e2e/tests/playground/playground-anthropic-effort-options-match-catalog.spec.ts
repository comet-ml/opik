import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import type { LlmCatalogModelRef } from '@e2e/core/backend';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import {
  probeAnthropicEffortGate,
  UNACCEPTABLE_EFFORT,
} from '@e2e/core/anthropic-effort-gate';

/**
 * The playground's Anthropic effort control against the catalog the backend now
 * enforces from (OPIK-8748, opik#8928).
 *
 * That PR deleted `AnthropicModelName.EFFORT_LEVELS_BY_MODEL_ID` — a hardcoded
 * map of model id to accepted effort levels — and had the gate read
 * `registryService.curatedParamsOf` instead, so the accept set is now catalog
 * data served by `GET /v1/private/llm/models`. **The frontend was not part of
 * that change** (0 of its 35 files) and still decides the control's options
 * from its own hardcoded `ANTHROPIC_MODEL_CAPABILITIES` in
 * `constants/llm.ts`.
 *
 * Two hardcoded tables became one hardcoded table and one data file, with
 * nothing tying them together. The frontend map's own comment still says its
 * levels "must be a subset of the model's levels in EFFORT_LEVELS_BY_MODEL_ID
 * in the backend's AnthropicModelName" — a symbol that no longer exists. When
 * they drift, the user-visible failure is silent and total: the panel offers a
 * level, the user picks it, and every run on that model is answered 400 by a
 * gate they cannot see.
 *
 * ## Why this is not already covered
 *
 * `playground.configure-model-settings` has four specs behind it and none of
 * them can catch this, because all four short-circuit
 * `POST /v1/private/chat/completions` in the browser through
 * `core/playground-completions.ts`. They assert what the frontend PUT on the
 * wire, which is the right question for a reconciler
 * (`playground-anthropic-effort-model-switch.spec.ts`) and the wrong one here:
 * the request never reaches `ChatCompletionService`, so the gate is never
 * consulted and a drifted map looks identical to an aligned one. The
 * taxonomy's own note on that capability lists "the backend hop itself" as
 * uncovered. This spec is that hop.
 *
 * ## Why the catalog is the oracle and not a second opinion
 *
 * `GET /v1/private/llm/models` serves the same curated rows
 * `curatedParamsOf` reads, so comparing the panel against it compares the
 * frontend's table against the data the backend will enforce. The gate is then
 * read directly as well — see {@link probeAnthropicEffortGate} — which closes
 * the triangle: panel == catalog == gate. Asserting only panel-vs-catalog
 * would still pass on a build whose gate ignored the catalog it was handed.
 *
 * ## Cost
 *
 * Nothing here reaches a provider. The panel is read without ever running, and
 * every gate probe sends {@link UNACCEPTABLE_EFFORT}, which is refused during
 * request mapping. That matters beyond tidiness: the models under test are
 * Claude Opus ids that `llm-model-policy` forbids this suite from driving, and
 * `modelRegistryProviders` registers them unbilled on exactly this basis.
 */

/** The catalog's lowercase levels as `EFFORT_LABELS` in `modelUtils.ts` renders them. */
const EFFORT_LABELS: Record<string, string> = {
  low: 'Low',
  medium: 'Medium',
  high: 'High',
  xhigh: 'xHigh',
  max: 'Max',
};

/**
 * The one option the control may offer that is not a catalog level.
 *
 * `getAnthropicThinkingEffortOptions` prepends it for a model that thinks only
 * when asked, and it means "send no effort at all" rather than naming a level —
 * so the catalog has nothing to say about it and `Off` is dropped before
 * comparing. Dropped by name, never by position: anything else the control
 * grew would survive the filter and fail the comparison, which is the point.
 * Whether `Off` appears on the right models is
 * `playground-anthropic-effort-model-switch.spec.ts`'s subject, not this one's.
 */
const OFF = 'Off';

/**
 * The models driven through the panel, by display name and by the catalog id
 * the same model answers to.
 *
 * Both halves are pinned because the two tables under test are keyed
 * differently — the frontend by `PROVIDER_MODEL_TYPE`, the catalog by provider
 * id — and the dated ids are exactly where a mismatch would hide: the panel's
 * "Claude Opus 4.5" is `claude-opus-4-5-20251101`, and the undated
 * `claude-opus-4-5` is a separate row the frontend marks with no effort levels
 * at all.
 *
 * Chosen to span all three shapes a curated row takes, so one spec covers
 * every branch of `getAnthropicThinkingEffortOptions`:
 */
const PANEL_MODELS = [
  {
    /** Three levels and no Off — `effortWithoutThinking`, so its label reads "Effort". */
    displayName: REGISTRY_MODEL.claudeEffortWithoutThinking,
    catalogId: 'claude-opus-4-5-20251101',
  },
  {
    /** Four levels plus Off — `thinksOnlyWhenAsked`. The one row withholding only xHigh. */
    displayName: REGISTRY_MODEL.claudeWithSampling,
    catalogId: 'claude-sonnet-4-6',
  },
  {
    /** All five levels and no Off — thinks by default. */
    displayName: REGISTRY_MODEL.claudeEffortDefaultMedium,
    catalogId: 'claude-opus-5-5',
  },
] as const;

/**
 * A model whose curated row is `effort: {accepted: false}`.
 *
 * On the allowlist in its own right (`llm-model-policy`), unlike the three
 * above, which are only reachable through `modelRegistryProviders`' unbilled
 * registration.
 */
const NO_EFFORT_MODEL = { displayName: 'Claude Haiku 4.5', catalogId: 'claude-haiku-4-5-20251001' };

/** The curated row for `id`, failing rather than returning undefined. */
function rowFor(catalog: LlmCatalogModelRef[], id: string): LlmCatalogModelRef {
  const row = catalog.find((model) => model.id === id);
  // Not an optional chain or a `?? fallback`: a spec whose oracle is missing
  // must stop, because every comparison below it would otherwise be made
  // against a default and pass having checked nothing.
  expect(row, `the catalog must carry a curated row for '${id}'`).toBeDefined();
  return row!;
}

/** The catalog's levels for `id` as the panel would label them. */
function expectedPanelOptions(row: LlmCatalogModelRef): string[] {
  return row.effort.levels.map((level) => {
    const label = EFFORT_LABELS[level];
    // A level the catalog curates that the frontend has no label for would
    // render as nothing at all, so it is a drift of its own rather than a
    // mapping gap to paper over.
    expect(label, `the frontend must label the curated level '${level}'`).toBeDefined();
    return label!;
  });
}

test.describe(
  'Playground — the Anthropic effort control against the curated catalog',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'the control offers exactly the levels the catalog curates, and the gate accepts exactly those',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page, backendClient }) => {
        test.setTimeout(240_000);
        expect(
          modelRegistryProviders.providers,
          'the Anthropic registry must be selectable, or the picker offers none of these models',
        ).toContain('anthropic');

        const catalog = await test.step('Read the curated Anthropic catalog', async () => {
          const rows = await backendClient.getLlmCatalogModels('anthropic');
          expect(
            rows.length,
            'the catalog must describe some Anthropic models, or there is no oracle',
          ).toBeGreaterThan(0);
          return rows;
        });

        const playground = new PlaygroundPage(page, project.id);
        await test.step('Open the playground', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        for (const { displayName, catalogId } of PANEL_MODELS) {
          const row = rowFor(catalog, catalogId);

          // The premise. Each of these three rows is picked for a specific
          // shape, and a catalog that stopped accepting effort for one of them
          // would make the comparison below trivially true (empty == empty)
          // rather than failing — so the shape is asserted before it is used.
          expect(
            row.effort.accepted,
            `'${catalogId}' is in this spec because its row accepts effort; a row that stopped ` +
              'accepting it needs a human to re-pick the model, not a passing comparison',
          ).toBe(true);

          const expected = expectedPanelOptions(row);

          await test.step(`${displayName}: the panel offers the catalog's levels`, async () => {
            await playground.selectModel(0, displayName);
            await playground.openModelParameters(0);

            const offered = await playground.thinkingEffortOptions();
            expect(
              offered.filter((option) => option !== OFF),
              `the panel's hardcoded ANTHROPIC_MODEL_CAPABILITIES row for ${displayName} must ` +
                `offer exactly what the catalog curates for '${catalogId}' — a level offered ` +
                'here and absent there is a run the gate answers 400',
            ).toEqual(expected);

            await playground.closeModelParameters();
          });

          await test.step(`${displayName}: the gate accepts the catalog's levels`, async () => {
            // Read from the gate itself rather than inferred from the catalog
            // it is supposed to be reading. This is the half that fails if
            // opik#8928 wired `curatedParamsOf` up wrongly, and the panel
            // comparison above cannot see it.
            const gate = await probeAnthropicEffortGate(backendClient, catalogId);
            expect(
              gate.accepted,
              `the gate must admit effort for '${catalogId}', whose curated row accepts it`,
            ).toBe(true);
            expect(
              gate.levels,
              `the gate enforces from the same curated row the panel was just compared against, ` +
                'so the two must agree level for level and in order',
            ).toEqual(row.effort.levels);
          });
        }
      },
    );

    test(
      'a model whose curated row refuses effort renders no control and is refused at every level',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page, backendClient }) => {
        test.setTimeout(180_000);
        expect(modelRegistryProviders.providers).toContain('anthropic');

        const row = await test.step('The catalog refuses effort for this model', async () => {
          const catalog = await backendClient.getLlmCatalogModels('anthropic');
          const found = rowFor(catalog, NO_EFFORT_MODEL.catalogId);
          // Again the premise, and here it is the whole test: if this row ever
          // starts accepting effort, "the panel shows no control" stops being
          // correct behaviour and becomes the bug.
          expect(
            found.effort.accepted,
            `'${NO_EFFORT_MODEL.catalogId}' is in this spec because its curated row sets ` +
              'effort.accepted false',
          ).toBe(false);
          expect(
            found.effort.levels,
            'and a row that refuses the parameter curates no levels to offer',
          ).toEqual([]);
          return found;
        });

        await test.step('The panel mounts no effort control at all', async () => {
          const playground = new PlaygroundPage(page, project.id);
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, NO_EFFORT_MODEL.displayName);
          await playground.openModelParameters(0);

          // Zero, not "not the five levels": `supportsAnthropicThinkingEffort`
          // returning false takes the control out of the DOM rather than
          // emptying it, and a control rendered with no options would be a
          // different build — one where a user can open a dropdown that offers
          // nothing.
          await expect(
            playground.thinkingEffortTrigger(),
            `the panel must mount no effort control for ${NO_EFFORT_MODEL.displayName}, whose ` +
              'row refuses the parameter — any control here offers a level the gate will refuse',
          ).toHaveCount(0);

          // The panel really did render, so the absence above is the control
          // being gone and not the panel having failed to open.
          await expect(
            playground.modelParameterLabel('Max output tokens'),
            'the parameters panel itself must have rendered, or "no effort control" says nothing',
          ).toBeVisible();
          await playground.closeModelParameters();
        });

        await test.step('…and the gate refuses the parameter outright', async () => {
          const gate = await probeAnthropicEffortGate(backendClient, NO_EFFORT_MODEL.catalogId);
          expect(
            gate.accepted,
            'the gate must refuse the parameter for this model with its "does not support" ' +
              'shape, which is a different 400 from an out-of-range level',
          ).toBe(false);
          expect(gate.levels, 'and name no supported levels, because there are none').toEqual(
            row.effort.levels,
          );
        });
      },
    );

    test(
      'every curated Anthropic row accepts exactly the effort levels the gate enforces',
      { tag: ['@cap:playground.model-parameter-gate'] },
      async ({ modelRegistryProviders, backendClient }) => {
        // The whole catalog, not the three models the panel half drives. This
        // is opik#8928's actual refactor — the gate's accept set is now catalog
        // data — and it is the one assertion that scales to every row for free,
        // since a refused probe never calls a provider. A per-model
        // hardcoded expectation would instead have to be re-transcribed from
        // the deleted map every time a model ships.
        test.setTimeout(300_000);
        expect(modelRegistryProviders.providers).toContain('anthropic');

        const catalog = await backendClient.getLlmCatalogModels('anthropic');
        expect(
          catalog.length,
          'the catalog must describe some Anthropic models, or this test asserts nothing',
        ).toBeGreaterThan(0);

        for (const row of catalog) {
          await test.step(`${row.id}: the gate enforces its curated row`, async () => {
            const gate = await probeAnthropicEffortGate(backendClient, row.id);
            // Compared as one object so a row that agreed on `accepted` while
            // disagreeing on the levels cannot pass half the check.
            expect(
              { accepted: gate.accepted, levels: gate.levels },
              `the gate must enforce exactly what '${row.id}' curates — since opik#8928 both ` +
                'sides read the same row, so any disagreement is the catalog not being read',
            ).toEqual({ accepted: row.effort.accepted, levels: row.effort.levels });
          });
        }
      },
    );
  },
);
