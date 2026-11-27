import { test, expect, REGISTRY_MODEL, EFFORT_CORRECTED_OPENAI_MODELS } from '@e2e/fixtures';
import type { LlmCatalogModelRef } from '@e2e/core/backend';
import { expectPinApplied, pinOpenAiPipelineMode } from '@e2e/core/openai-pipeline-mode';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * The LLM-judge rule editor's OpenAI Reasoning effort control against the same
 * curated catalog, and the second half of this release's candidate.
 *
 * `playground-openai-effort-options-match-catalog.spec.ts` asserts the same
 * invariant on the PLAYGROUND. This is not a duplicate of it: the rule editor
 * mounts its own component (`PromptModelConfigs` inside the editor side panel,
 * not `PromptModelSettings`), applies its own `RULE_UNSUPPORTED_PARAMS` filter
 * on top of the model's capabilities, and renders the control in a Radix
 * dropdown menu rather than a popover. The two surfaces have already regressed
 * apart once — opik#9153 had to teach this one to read and write an effort the
 * playground had always handled — so a spec on one says nothing about the
 * other.
 *
 * ## Why this release, and what changed in 2.2.151
 *
 * opik#9608 (2.2.148) appended `gpt-6.2-astra` and `gpt-6.2-sol` to
 * `llm-models-default.yaml` with no `params:` block, so the served catalog
 * answered `effort: {accepted: false}` for both. The release report named this
 * editor as the second page affected and nothing in the estate drove either id.
 * Verified by hand on staging for 2.2.148: both rows mounted a Temperature and
 * no Reasoning effort here, while `GPT 6.1 Sol` mounted a Reasoning effort and
 * no Temperature.
 *
 * As on the playground, whether those two rows SHOULD accept effort was left to
 * whoever owns the catalog, and the invariant pinned is that this panel agrees
 * with the catalog the backend serves — which holds whichever way that question
 * is answered, and fails the day the yaml is corrected without the editor
 * following. opik#9648, in 2.2.151, corrected it: both rows now carry their
 * siblings' `effort: {accepted: true, levels: [low, medium, high, xhigh]}`. So
 * the editor must now mount the control for them, which is what the loop below
 * asserts; `gpt-4o-mini` carries the unchanged negative half.
 *
 * ## Why these capability tags
 *
 * `@cap:online-evaluation.create-llm-judge-rule` is what the estate's other
 * judge-panel specs claim for assertions about this form's model settings
 * (`online-evaluation-llm-judge-model-parameters.spec.ts`,
 * `online-evaluation-llm-judge-thinking-effort.spec.ts`), and this spec drives
 * the create-rule editor the same way. Nothing here claims
 * `playground.model-parameter-gate`: the OpenAI branch has no request-mapping
 * gate to read — see the playground spec's header for the probe that establishes
 * it.
 *
 * ## Deterministic, and no rule is ever created
 *
 * The form is filled and read; `submit` is never clicked, so no automation rule
 * is written and no teardown is needed. Nothing reaches a provider either: a
 * judge only calls one when a rule exists, is enabled and samples a trace.
 * The two `gpt-6.2` ids are registered unbilled by `modelRegistryProviders` on
 * that basis — see `EFFORT_CORRECTED_OPENAI_MODELS`.
 *
 * The pipeline mode is pinned to `chat_completions_api` for the reason the
 * playground spec gives: unpinned, the oracle would be the row's base `effort`
 * or its `routes.responses_api.effort` depending on a shared workspace key's
 * stored mode.
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
 * The accepting row driven here, by display name and catalog id.
 *
 * One rather than the playground spec's two: this surface's question is whether
 * THIS panel reads the catalog, and the playground spec already establishes
 * that the comparison discriminates between level sets. `gpt-5-mini` is the
 * cheapest OpenAI reasoning model on the suite's unbilled registry and is the
 * model the sibling judge specs drive, so a failure here is comparable to
 * theirs.
 */
const EFFORT_ACCEPTED_MODEL = {
  displayName: REGISTRY_MODEL.openAiReasoning,
  catalogId: 'gpt-5-mini',
};

/**
 * A row that refuses effort, and has since long before either release.
 *
 * The negative half of the test, and the reason it still discriminates now that
 * the two `gpt-6.2` rows accept the parameter: without a row the editor must
 * mount NO control for, "the editor mounts a control offering the catalog's
 * levels" is equally satisfied by an editor that mounts one for every OpenAI
 * model and reads the catalog for none of them.
 */
const EFFORT_REFUSED_CONTROL = {
  displayName: REGISTRY_MODEL.openAiStandard,
  catalogId: 'gpt-4o-mini',
};

/** The judge template every rule here starts from; its content is not the subject. */
const TEMPLATE = 'Moderation';

/** The curated row for `id`, failing rather than returning undefined. */
function rowFor(catalog: LlmCatalogModelRef[], id: string): LlmCatalogModelRef {
  const row = catalog.find((model) => model.id === id);
  // A spec whose oracle is missing must stop: every comparison below would
  // otherwise be made against a default and pass having checked nothing.
  expect(row, `the catalog must carry a curated row for '${id}'`).toBeDefined();
  return row!;
}

/** The catalog's levels for a row, as the panel would label them. */
function expectedPanelOptions(row: LlmCatalogModelRef): string[] {
  return row.effort.levels.map((level) => {
    const label = EFFORT_LABELS[level];
    expect(label, `the frontend must label the curated level '${level}'`).toBeDefined();
    return label!;
  });
}

/**
 * The catalog's label for a row, asserted against the name the picker is driven
 * by — which is also what `llm-model-policy` matches on, so it cannot be read
 * from the catalog at run time.
 */
function expectLabelPinned(row: LlmCatalogModelRef, displayName: string): void {
  expect(
    row.label,
    `the catalog's label for '${row.id}' must still be the name this spec drives the picker by`,
  ).toBe(displayName);
}

test.describe(
  'Online evaluation — the judge reasoning-effort control against the curated catalog',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'the judge panel offers the levels the curated row accepts — including both rows 2.2.151 corrected — and mounts no control for a row that refuses them',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ modelRegistryProviders, project, page, backendClient, testNamespace }) => {
        // One test rather than two, unlike the playground spec: here the
        // positive and the negative cases share an expensive setup (the rules
        // page, the create panel, the judge template) and the model picker can
        // be re-driven inside the open editor, so splitting them would double a
        // ~40s preamble to assert the same two facts. They are still separate
        // steps, so a failure names which case broke.
        test.setTimeout(240_000);
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry must be selectable, or the judge picker offers none of these models',
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

        const onlineEval = new OnlineEvaluationPage(page);

        await test.step(`Open a judge rule editor on ${EFFORT_ACCEPTED_MODEL.displayName}`, async () => {
          const row = rowFor(catalog, EFFORT_ACCEPTED_MODEL.catalogId);
          expectLabelPinned(row, EFFORT_ACCEPTED_MODEL.displayName);

          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openCreateRulePanel();
          await onlineEval.fillCreateRuleLLMJudge({
            // Named only because the form wants one; the rule is never
            // submitted, so nothing by this name is ever created.
            name: `${testNamespace}-openai-effort-catalog`,
            template: TEMPLATE,
            modelDisplayName: EFFORT_ACCEPTED_MODEL.displayName,
          });
        });

        await test.step(`${EFFORT_ACCEPTED_MODEL.displayName}: the panel offers the catalog's levels, in order`, async () => {
          const row = rowFor(catalog, EFFORT_ACCEPTED_MODEL.catalogId);
          // The premise: a row that stopped accepting effort would make the
          // comparison trivially true (empty == empty) rather than failing.
          expect(
            row.effort.accepted,
            `'${EFFORT_ACCEPTED_MODEL.catalogId}' is in this spec because its row accepts effort`,
          ).toBe(true);
          expect(
            row.effort.levels.length,
            'and a row that accepts the parameter must curate levels to offer',
          ).toBeGreaterThan(0);

          await expect(
            onlineEval.judgeModelParametersTrigger,
            'the model-parameters gear',
          ).toHaveCount(1);
          await onlineEval.openJudgeModelParameters();

          await expect(
            onlineEval.judgeParameterLabel('Reasoning effort'),
            'the control is labelled for its provider, not "Thinking effort" or "Thinking level"',
          ).toHaveCount(1);
          // The whole list and its ORDER. This panel builds its options from
          // the same `getOpenAIReasoningEffortOptions` the playground does, but
          // through its own component and its own unsupported-params filter —
          // so "the right set in the right order" is a claim about this surface
          // and not an inference from the other one.
          expect(
            await onlineEval.judgeEffortOptions(),
            `the judge panel must offer exactly what the catalog curates for ` +
              `'${EFFORT_ACCEPTED_MODEL.catalogId}'`,
          ).toEqual(expectedPanelOptions(row));

          await onlineEval.closeJudgeModelParameters();
        });

        // The two rows opik#9608 shipped with no effort block and opik#9648
        // corrected. The claim on them flipped with the catalog; see the header.
        for (const { displayName, catalogId } of EFFORT_CORRECTED_OPENAI_MODELS) {
          const row = rowFor(catalog, catalogId);
          expectLabelPinned(row, displayName);

          await test.step(`${displayName}: the catalog now ACCEPTS the parameter`, async () => {
            // Half the test. These two rows carried no `params.effort` at all in
            // 2.2.148 and this editor correctly mounted no control; opik#9648
            // gave them their siblings' block, so "no control" has stopped being
            // correct behaviour and become the bug. Read at run time rather than
            // pinned, so a sync that moves them back fails here — on the premise
            // — instead of in the rendering assertion below, where it would read
            // as a frontend regression.
            expect(
              row.effort.accepted,
              `'${catalogId}' is in this spec because 2.2.151's sync gave its curated row an ` +
                'effort block — a row that refuses it again must mount no control, and needs a human',
            ).toBe(true);
            expect(
              row.effort.levels.length,
              'and a row that accepts the parameter must curate levels to offer',
            ).toBeGreaterThan(0);
          });

          await test.step(`${displayName}: the judge panel offers the catalog's levels, in order`, async () => {
            await onlineEval.selectJudgeModel(displayName);

            await expect(
              onlineEval.judgeModelParametersTrigger,
              `the gear must be there for ${displayName}, or there is no panel to read`,
            ).toHaveCount(1);
            await onlineEval.openJudgeModelParameters();

            await expect(
              onlineEval.judgeEffortTrigger,
              `the judge panel must mount exactly one effort control for ${displayName}, whose ` +
                'curated row now accepts the parameter',
            ).toHaveCount(1);

            // The option list and its ORDER, not merely the control's presence:
            // a control mounted from some default list would satisfy a presence
            // check while still not reading the row it was generated from, which
            // is the drift this spec exists to catch. This panel builds its
            // options through its own component and its own unsupported-params
            // filter, so it is a claim about this surface and not an inference
            // from the playground.
            expect(
              await onlineEval.judgeEffortOptions(),
              `the judge panel must offer exactly what the catalog curates for '${catalogId}'`,
            ).toEqual(expectedPanelOptions(row));

            await onlineEval.closeJudgeModelParameters();
          });
        }

        await test.step(`${EFFORT_REFUSED_CONTROL.displayName}: the catalog refuses the parameter`, async () => {
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

        await test.step(`${EFFORT_REFUSED_CONTROL.displayName}: the judge panel mounts no effort control`, async () => {
          await onlineEval.selectJudgeModel(EFFORT_REFUSED_CONTROL.displayName);

          await expect(
            onlineEval.judgeModelParametersTrigger,
            `the gear must still be there for ${EFFORT_REFUSED_CONTROL.displayName} — this row ` +
              'has a Temperature a rule can set, so a missing gear would be a different ' +
              'regression and would make "no effort control" unreadable',
          ).toHaveCount(1);
          await onlineEval.openJudgeModelParameters();

          // Zero, not "not the levels": the control renders only while the
          // chosen model declares levels, so a refused row drops it from the
          // panel rather than showing it empty.
          await expect(
            onlineEval.judgeEffortTrigger,
            `the judge panel must mount no effort control for ` +
              `${EFFORT_REFUSED_CONTROL.displayName}, whose curated row refuses the parameter`,
          ).toHaveCount(0);

          // The panel really did render, so the absence above is the control
          // being gone and not the menu having failed to open. The proof differs
          // by case, deliberately: a refused OpenAI row keeps its Temperature
          // slider, while an ACCEPTING one has none at all (the temperature is
          // resolved away) and is proven rendered by its effort control instead.
          expect(
            await onlineEval.mountedJudgeParameterIds(),
            `the judge parameters menu itself must have rendered some slider for ` +
              `${EFFORT_REFUSED_CONTROL.displayName}, or "no effort control" says nothing`,
          ).not.toHaveLength(0);

          await onlineEval.closeJudgeModelParameters();
        });

        await test.step('…and switching back to the accepting row mounts the control again', async () => {
          // The closing control. Without it every negative above is satisfied
          // by an editor whose effort control stopped rendering for any model
          // at all after the first model switch — a far larger regression that
          // would otherwise read as green steps.
          const row = rowFor(catalog, EFFORT_ACCEPTED_MODEL.catalogId);
          await onlineEval.selectJudgeModel(EFFORT_ACCEPTED_MODEL.displayName);
          await onlineEval.openJudgeModelParameters();
          await expect(
            onlineEval.judgeEffortTrigger,
            `${EFFORT_ACCEPTED_MODEL.displayName} accepts effort, so the panel must mount ` +
              'exactly one control — proving the absences above are per-row',
          ).toHaveCount(1);
          expect(
            await onlineEval.judgeEffortOptions(),
            'and it offers that row’s curated levels, after the switches',
          ).toEqual(expectedPanelOptions(row));
          await onlineEval.closeJudgeModelParameters();
        });

        // Last, not first: the pin is applied by the provider-keys read the
        // editor makes, so there is nothing to count until the form has been
        // driven. Without this the whole test would pass vacuously on a
        // workspace holding no OpenAI key, where the panel falls back to the
        // Chat Completions default for a reason unrelated to the pin.
        expectPinApplied(pin);
      },
    );
  },
);
