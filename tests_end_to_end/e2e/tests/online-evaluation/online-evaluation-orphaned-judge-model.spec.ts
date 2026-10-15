import { test, expect } from '@e2e/fixtures';
import type { JudgeMessageWrite, JudgeOutputSchemaType } from '@e2e/core/backend';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * A judge rule whose stored model id the picker no longer lists (opik#8633).
 *
 * The provider-model sync re-pointed `CLAUDE_SONNET_4_5` from `claude-sonnet-4-5`
 * onto the dated `claude-sonnet-4-5-20250929`, and dropped five OpenRouter ids
 * from the picker while leaving their `PROVIDER_MODEL_TYPE` members in place. So
 * a rule saved before the sync can reference an id that is still a legal enum
 * value but appears in no option list — and every model picker in the product is
 * `cross_cutting_fe`, mapped to no area, so no coverage percentage moves for it.
 *
 * `PromptModelSelect` handles this by falling back to the raw stored value:
 * `groupOptions.find(m => m.value === selectedValue)?.label ?? value`. The
 * failure if that fallback went away is a blank picker reading "Select an LLM
 * model" — and then the next ordinary save, which the user believes changes
 * nothing, writes whatever the form had instead. The rule silently starts judging
 * with a different model, at a different price, and nothing anywhere says so.
 *
 * Two claims, in the order they matter:
 *
 *  1. The dialog HYDRATES the orphaned id — the trigger reads exactly the stored
 *     string, not the placeholder and not some other model's label. Asserted as
 *     exact equality, which is what tells "went blank", "substituted another
 *     model" and "rendered the id" apart; a non-emptiness check would pass for
 *     two of the three.
 *  2. Saving the rule with NO edits sends that id back verbatim and persists it.
 *     This is the half `online-evaluation-edit-rule-preserves-model-parameters.
 *     spec.ts` cannot make: that spec's model IS in the picker, so its round trip
 *     never exercises the fallback path at all.
 *
 * Deterministic and LLM-free by construction. The rules are seeded disabled with
 * `sampling_rate: 0`, so none of them can ever fire, and nothing here needs a
 * configured provider: the trigger's fallback reads the stored value, which is
 * why this runs on every deployment rather than only where an Anthropic key is
 * set up.
 */

/**
 * `PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_5` — still an enum member, no longer an
 * option: opik#8633 moved the "Claude Sonnet 4.5" label onto the dated
 * `claude-sonnet-4-5-20250929` build beside it.
 */
const ORPHANED_MODEL = 'claude-sonnet-4-5';

/**
 * One of the five OpenRouter ids opik#8633 removed from `providerModels.ts`
 * while leaving `PROVIDER_MODEL_TYPE.DEEPSEEK_DEEPSEEK_V3_2_EXP` in
 * `providers.ts`. A different route to the same state as the orphan above —
 * dropped from a list rather than displaced within one — and under a different
 * provider, so a fallback that only happened to work for Anthropic fails here.
 */
const DROPPED_MODEL = 'deepseek/deepseek-v3.2-exp';

const UNLISTED_MODELS = [
  { key: 'orphaned', model: ORPHANED_MODEL },
  { key: 'dropped', model: DROPPED_MODEL },
] as const;

/** What the trigger reads when nothing is selected — the failure, not the expectation. */
const EMPTY_PICKER_TEXT = 'Select an LLM model';

/**
 * The trigger text a stored-but-unlisted model id must produce.
 *
 * `getSelectedModelInfo` renders `<provider label> <modelName>` when it can name
 * a provider group for the value and the bare `modelName` when it cannot — and
 * which of the two applies depends on whether the id still resolves to a
 * provider, which is not the fact under test. So the id is anchored at the END,
 * which admits either shape while still being exact about the id itself.
 *
 * Anchoring is load-bearing rather than tidiness: `claude-sonnet-4-5` is a
 * PREFIX of `claude-sonnet-4-5-20250929`, the very id opik#8633 re-pointed the
 * label onto. A `toContainText` would be satisfied by a picker that had silently
 * substituted the dated build — which is the exact rewrite this spec exists to
 * catch.
 */
function pickerShows(model: string): RegExp {
  const escaped = model.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|\\s)${escaped}$`);
}

const JUDGE_MESSAGES: JudgeMessageWrite[] = [
  {
    role: 'USER',
    content: 'Is the OUTPUT non-empty?\n\nOUTPUT:\n{{output}}',
  },
];

const JUDGE_VARIABLES = { output: 'output.output' };

const JUDGE_SCHEMA: Array<{
  name: string;
  type: JudgeOutputSchemaType;
  description: string;
}> = [
  {
    name: 'Non empty',
    type: 'BOOLEAN',
    description: 'Returns true when the output is non-empty',
  },
];

/** Matches the rule PATCH on both the `/opik/api` and bare `/api` mounts. */
function isRuleUpdate(url: string, ruleId: string): boolean {
  return new URL(url).pathname.endsWith(`/v1/private/automations/evaluators/${ruleId}`);
}

test.describe(
  'Online Evaluation — a judge model the picker no longer lists',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'The edit dialog renders a stored model id that is in no option list, rather than going blank',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const seeded = await test.step('Seed a disabled judge rule per unlisted model id', async () =>
          Promise.all(
            UNLISTED_MODELS.map(async ({ key, model }) => {
              const name = `${testNamespace}-${key}`;
              const id = await backendClient.createLlmJudgeRule({
                projectId: project.id,
                name,
                // Disabled AND at a zero sampling rate: either alone would stop
                // it scoring, and both together mean a rule that cannot reach a
                // provider even if one of the two were mis-read.
                enabled: false,
                samplingRate: 0,
                model,
                temperature: 0,
                messages: JUDGE_MESSAGES,
                variables: JUDGE_VARIABLES,
                schema: JUDGE_SCHEMA,
              });
              return { key, model, name, id };
            }),
          ));

        await test.step('Each rule really stored the id it was seeded with', async () => {
          // Before the browser opens. A rule the backend had normalised to some
          // other id would make every assertion below a test of the wrong string,
          // and it would read as coverage of a fallback that never ran.
          for (const rule of seeded) {
            const model = await backendClient.getLlmJudgeModel(rule.id);
            expect(model.name, `${rule.key} rule stores '${rule.model}' verbatim`).toBe(
              rule.model,
            );
          }
        });

        const onlineEval = new OnlineEvaluationPage(page);

        await test.step('Open the Online evaluation page', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          for (const rule of seeded) {
            await expect(onlineEval.ruleRow(rule.name)).toHaveCount(1);
          }
        });

        for (const rule of seeded) {
          await test.step(`The '${rule.key}' rule's picker reads exactly '${rule.model}'`, async () => {
            await onlineEval.openEditRuleDialogByName(rule.name);
            // Not a containment and not a non-emptiness check: the three ways
            // this breaks are a blank picker, a SUBSTITUTED model and a truncated
            // id, and only an end-anchored match on the stored string tells all
            // three apart — see `pickerShows`. `toHaveText` retries, so a picker
            // that hydrates once the model query resolves is waited for rather
            // than raced.
            await expect(
              onlineEval.modelPicker,
              `an id in no option list must fall back to the stored value, not to '${EMPTY_PICKER_TEXT}' and not to another model`,
            ).toHaveText(pickerShows(rule.model));
            await onlineEval.cancelDialog();
          });
        }

        await test.step('Closing without saving left every stored model untouched', async () => {
          for (const rule of seeded) {
            const model = await backendClient.getLlmJudgeModel(rule.id);
            expect(
              model.name,
              `opening and cancelling the '${rule.key}' dialog must not write`,
            ).toBe(rule.model);
          }
        });
      },
    );

    test(
      'Saving the rule with no edits sends the unlisted model id back verbatim',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        // The half that matters for data, and the one the existing
        // preserves-model-parameters spec cannot reach: its model IS in the
        // picker, so the fallback path never runs there. Pressing "Update rule"
        // having changed nothing is the most ordinary gesture on this page, and
        // it is exactly where a form that could not map its own hydrated value
        // serializes something else instead.
        const ruleName = `${testNamespace}-orphaned-save`;

        const ruleId = await test.step('Seed a disabled judge rule on the orphaned model id', async () =>
          backendClient.createLlmJudgeRule({
            projectId: project.id,
            name: ruleName,
            enabled: false,
            samplingRate: 0,
            model: ORPHANED_MODEL,
            temperature: 0,
            messages: JUDGE_MESSAGES,
            variables: JUDGE_VARIABLES,
            schema: JUDGE_SCHEMA,
          }));

        await test.step('The seed really stored the orphaned id', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the rule names the orphaned model').toBe(ORPHANED_MODEL);
        });

        const onlineEval = new OnlineEvaluationPage(page);

        await test.step('Open the rule in the edit dialog', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await onlineEval.openEditRuleDialogByName(ruleName);
          // The save below only means something once the form has hydrated: a
          // submit fired before the model reached the picker would be testing an
          // empty form, and a PATCH carrying the id back would be impossible
          // rather than merely unasserted.
          await expect(onlineEval.modelPicker).toHaveText(pickerShows(ORPHANED_MODEL));
        });

        const update = await test.step('Press "Update rule" without editing anything', async () => {
          // Armed before the click: the request is in flight the moment the
          // dialog submits, so subscribing afterwards would race it.
          const patched = page.waitForRequest(
            (request) => request.method() === 'PATCH' && isRuleUpdate(request.url(), ruleId),
          );
          // The response, not just the dispatch. `AddEditRuleDialog` calls
          // setOpen(false) outside the mutation's callbacks, so the dialog hides
          // the moment the PATCH is sent — waiting for it to close is no barrier,
          // and the persisted read below would otherwise be free to observe the
          // pre-save value and pass on a backend that rewrote the model.
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'PATCH' && isRuleUpdate(response.url(), ruleId),
          );
          await onlineEval.submitRuleDialog();
          const request = await patched;
          const response = await answered;
          expect(
            response.ok(),
            `the unedited save was accepted (got ${response.status()})`,
          ).toBe(true);
          return request;
        });

        await test.step('The outbound PATCH carries the orphaned id, not a listed one', async () => {
          // The body is what the form serialized out of the value it hydrated, so
          // this is where a substitution happens — the backend only ever sees the
          // result. Reading it here separates "the frontend rewrote it" from "the
          // backend rewrote it", which the persisted read below cannot.
          const body = update.postDataJSON() as {
            code?: { model?: { name?: unknown } };
          };
          const sent = body.code?.model?.name;
          expect(sent, 'the update payload names a model').toBeDefined();
          expect(sent, 'an unedited save must send the stored id back unchanged').toBe(
            ORPHANED_MODEL,
          );
        });

        await test.step('And the rule still names it afterwards', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the unedited save did not change the model').toBe(ORPHANED_MODEL);
        });

        await test.step('The rule is still listed, in the state it started in', async () => {
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await expect(onlineEval.ruleStatusCell(ruleName, 'Disabled')).toBeVisible();
          await expect(onlineEval.ruleSamplingRateCell(ruleName, '0%')).toBeVisible();
        });
      },
    );
  },
);
