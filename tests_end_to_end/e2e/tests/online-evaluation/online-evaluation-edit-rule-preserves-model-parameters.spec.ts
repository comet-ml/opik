import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * An LLM-judge rule's provider settings live in `code.model.custom_parameters`
 * — the only free-form slot the request shape captures, and where the frontend
 * nests a model's `thinking` block. Nothing else on the rule carries it.
 *
 * The failure this pins down is a save that changes nothing. Opening a rule's
 * edit dialog and pressing "Update rule" is the most ordinary gesture on this
 * page, and it is exactly where a serializer that rebuilds the model block from
 * the form's own fields drops everything the form never rendered a control for.
 * The result is silent: the dialog closes, the row looks identical, the list
 * shows the same name and rate, and the rule has quietly stopped asking the
 * provider to think.
 *
 * So the assertions are on the payload and the persisted blob, not on the
 * screen — there is nothing on screen to see.
 *
 * Two keys are seeded, and both are asserted:
 *   - `thinking`, the block the product actually cares about, nested so a
 *     partial strip (keeping the key, emptying the block) fails too;
 *   - `unrelated_marker`, a key no dialog control has ever heard of. It is the
 *     control: `thinking` surviving on its own would also be satisfied by a
 *     serializer that special-cases `thinking` while still discarding the rest
 *     of the block, and this map is documented as free-form.
 *
 * Deterministic and LLM-free by construction — the rule never has to execute,
 * only round-trip. It is seeded over REST rather than through the create dialog
 * so that a create-path regression cannot be mistaken for an edit-path one.
 */

/** An Anthropic model, whose thinking block the frontend leaves untouched. */
const JUDGE_MODEL = 'claude-haiku-4-5-20251001';

/**
 * The block under test. `budget_tokens` is nested inside `thinking` on purpose:
 * an assertion on the whole object catches a save that preserves the key but
 * flattens or empties its contents, which a `toHaveProperty('thinking')` would
 * not.
 */
const SEEDED_CUSTOM_PARAMETERS = {
  thinking: { type: 'enabled', budget_tokens: 2048 },
  unrelated_marker: 'keep-me',
} as const;

const JUDGE_MESSAGES = [
  {
    role: 'USER',
    content: 'Is the OUTPUT non-empty?\n\nOUTPUT:\n{{output}}',
  },
];

const JUDGE_VARIABLES = { output: 'output.output' };

const JUDGE_SCHEMA = [
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
  'Online Evaluation — edit rule',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'Saving an LLM-judge rule with no edits preserves its model custom_parameters',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-thinking`;

        const ruleId = await test.step('Seed an LLM-judge rule carrying custom_parameters', async () =>
          backendClient.createLlmJudgeAutomationRule({
            projectId: project.id,
            name: ruleName,
            samplingRate: 1,
            modelName: JUDGE_MODEL,
            temperature: 0,
            customParameters: SEEDED_CUSTOM_PARAMETERS,
            messages: JUDGE_MESSAGES,
            variables: JUDGE_VARIABLES,
            schema: JUDGE_SCHEMA,
          }));

        await test.step('The seed really persisted both keys', async () => {
          // Asserted before the browser opens: an edit-dialog round-trip over a
          // rule that never held the block would pass without proving anything,
          // and would read as coverage forever.
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the seeded rule names the judge model').toBe(JUDGE_MODEL);
          expect(
            model.customParameters,
            'custom_parameters as seeded, before any UI interaction',
          ).toEqual(SEEDED_CUSTOM_PARAMETERS);
        });

        const onlineEval = new OnlineEvaluationPage(page);

        await test.step('Open the rule in the edit dialog', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await onlineEval.openEditRuleDialogByName(ruleName);
        });

        const update = await test.step('Press "Update rule" without editing anything', async () => {
          // Armed before the click: the request is in flight the moment the
          // dialog submits, so subscribing afterwards would race it.
          const patched = page.waitForRequest(
            (request) =>
              request.method() === 'PATCH' && isRuleUpdate(request.url(), ruleId),
          );
          await onlineEval.submitRuleDialog();
          return patched;
        });

        await test.step('The outbound PATCH still carries both custom_parameters keys', async () => {
          // The body is what the form serialized out of the values it hydrated,
          // so this is where a strip happens — the backend only ever sees the
          // result. Reading it here separates "the frontend dropped it" from
          // "the backend dropped it", which the persisted read below cannot.
          const body = update.postDataJSON() as {
            code?: { model?: { custom_parameters?: Record<string, unknown> } };
          };
          const sent = body.code?.model?.custom_parameters;
          expect(sent, 'the update payload carries a model custom_parameters block').toBeDefined();
          expect(sent, 'an unedited save sends the block back unchanged').toEqual(
            SEEDED_CUSTOM_PARAMETERS,
          );
        });

        await test.step('And the rule persists them unchanged', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the unedited save did not change the model').toBe(JUDGE_MODEL);
          expect(
            model.customParameters,
            'custom_parameters survive the round-trip through the edit dialog',
          ).toEqual(SEEDED_CUSTOM_PARAMETERS);
        });

        await test.step('The rule is still listed, with the state it started in', async () => {
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await expect(onlineEval.ruleStatusCell(ruleName, 'Enabled')).toBeVisible();
          await expect(onlineEval.ruleSamplingRateCell(ruleName, '100%')).toBeVisible();
        });
      },
    );
  },
);
