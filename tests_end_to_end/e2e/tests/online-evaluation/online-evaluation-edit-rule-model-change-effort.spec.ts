import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import type { JudgeMessageWrite, JudgeOutputSchemaType } from '@e2e/core/backend';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * Changing an LLM-judge rule's model must take the previous model's thinking
 * level out of `custom_parameters` and put the reconciled one in its place
 * (OPIK-8636, opik#8841).
 *
 * `custom_parameters` is the rule's only free-form slot, and the thinking
 * level lives in it twice over: Anthropic reads
 * `output_config.effort`, Gemini reads `thinking.level`. Neither is shown
 * anywhere on the rule list, so what this test is about is invisible by
 * construction — and expensive in two different ways:
 *
 *   - a level the new model DOES accept but the user never chose bills for
 *     thinking nobody asked for;
 *   - a level the new model REJECTS stops the rule scoring altogether, with
 *     nothing on the list to say so. Google answers Gemini 2.5 Pro's zero
 *     budget with "Budget 0 is invalid. This model only works in thinking
 *     mode", so a `thinking.level: off` carried over from 2.5 Flash is a judge
 *     that silently never runs again.
 *
 * ## Why this is not already covered
 *
 * `online-evaluation-edit-rule-preserves-model-parameters.spec.ts` covers the
 * opposite gesture with the opposite expectation: an UNCHANGED save, where
 * every key must survive. Its taxonomy note says in as many words that
 * "editing a field through the dialog is not covered", and a model change is
 * the one edit that is supposed to rewrite this blob rather than preserve it.
 * The two specs together are the pair: nothing is dropped unless the model
 * changed, and the stale level is dropped when it did.
 *
 * ## Both surfaces, and both providers
 *
 * The dialog's displayed level and the PATCH it sends can disagree — the
 * reconciler writes the settled level into the form state while
 * `resolveEffort` independently masks an unofferable one for display — so a
 * build where the write-back failed shows the right level and saves the old
 * one. Each test therefore asserts the control, the outbound PATCH body and the
 * persisted blob. Reading the PATCH as well as the blob separates "the frontend
 * sent the stale level" from "the backend stored it".
 *
 * Anthropic and Gemini are separate branches of `updateProviderConfig` writing
 * to different keys of the same map, so they can regress apart.
 *
 * `unrelated_marker` is seeded into both blobs as the control: the effort being
 * replaced correctly would also be satisfied by a save that rebuilt
 * `custom_parameters` from the form's own fields and discarded everything else
 * — which is the regression the sibling spec above exists to catch, and it must
 * not be reintroduced by this edit path.
 *
 * Deterministic and provider-free: the rules are seeded over REST, edited
 * through the dialog and read back, but never executed.
 */

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

/** A key no dialog control has ever heard of — the free-form control. */
const UNRELATED_MARKER = { unrelated_marker: 'keep-me' } as const;

/** Matches the rule PATCH on both the `/opik/api` and bare `/api` mounts. */
function isRuleUpdate(url: string, ruleId: string): boolean {
  return new URL(url).pathname.endsWith(`/v1/private/automations/evaluators/${ruleId}`);
}

test.describe(
  'Online Evaluation — edit rule, model change',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      "switching the judge's Anthropic model replaces the previous model's effort",
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({
        modelRegistryProviders,
        project,
        backendClient,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        test.setTimeout(180_000);
        expect(
          modelRegistryProviders.providers,
          'the Anthropic registry is selectable',
        ).toContain('anthropic');

        const ruleName = `${testNamespace}-anthropic-effort`;
        // `max` because the model switched TO does not offer it: that is what
        // forces the nearest-offered branch. A level both models offered would
        // be kept untouched and prove nothing about the replacement.
        const seeded = { output_config: { effort: 'max' }, ...UNRELATED_MARKER };

        const ruleId = await test.step('Seed a judge rule holding effort max', async () =>
          backendClient.createLlmJudgeRule({
            projectId: project.id,
            name: ruleName,
            samplingRate: 1,
            model: 'claude-sonnet-4-6',
            temperature: 0,
            customParameters: seeded,
            messages: JUDGE_MESSAGES,
            variables: JUDGE_VARIABLES,
            schema: JUDGE_SCHEMA,
          }));

        await test.step('The seed really holds that effort, before the browser opens', async () => {
          // Asserted server-side first: a dialog round-trip over a rule that
          // never held the level would pass without proving anything, and would
          // read as coverage forever.
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name).toBe('claude-sonnet-4-6');
          expect(model.customParameters, 'custom_parameters as seeded').toEqual(seeded);
        });

        const onlineEval = new OnlineEvaluationPage(page);

        await test.step('The dialog hydrates the control to Max', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await onlineEval.openEditRuleDialogByName(ruleName);
          await onlineEval.openJudgeModelParameters();

          await expect(
            onlineEval.judgeParameterLabel('Thinking effort'),
            'the seeded model’s effort does turn thinking on, so it is labelled for that',
          ).toHaveCount(1);
          expect(
            await onlineEval.readJudgeEffort(),
            'read back from the rule’s only copy of the level, under output_config.effort',
          ).toBe('Max');
          await onlineEval.closeJudgeModelParameters();
        });

        await test.step(`Change the model to ${REGISTRY_MODEL.claudeEffortWithoutThinking}`, async () => {
          await onlineEval.selectJudgeModel(REGISTRY_MODEL.claudeEffortWithoutThinking);
          await onlineEval.openJudgeModelParameters();
        });

        await test.step('The control relabels and settles on High, the nearest level offered', async () => {
          await expect(
            onlineEval.judgeParameterLabel('Effort'),
            'the new model’s effort does not turn thinking on, so the label drops "Thinking"',
          ).toHaveCount(1);
          await expect(
            onlineEval.judgeParameterLabel('Thinking effort'),
            'and the old label is gone, not rendered beside it',
          ).toHaveCount(0);
          expect(
            await onlineEval.judgeEffortOptions(),
            'this model takes neither Max nor an Off, which is why the seeded level had to move',
          ).toEqual(['Low', 'Medium', 'High']);
          expect(
            await onlineEval.readJudgeEffort(),
            'High is both the nearest offered level to Max and this model’s default, so this ' +
              'assertion is about the level being REPLACED rather than about which rule chose it',
          ).toBe('High');
          await onlineEval.closeJudgeModelParameters();
        });

        const update = await test.step('Save, and capture the PATCH', async () => {
          const patched = page.waitForRequest(
            (request) => request.method() === 'PATCH' && isRuleUpdate(request.url(), ruleId),
          );
          // The response as well as the dispatch: the dialog closes the moment
          // the PATCH is sent, so waiting for it to close is no barrier and the
          // persisted read below would otherwise be free to observe the
          // pre-save blob.
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'PATCH' && isRuleUpdate(response.url(), ruleId),
          );
          await onlineEval.submitRuleDialog();
          const request = await patched;
          const response = await answered;
          expect(response.ok(), `the save was accepted (got ${response.status()})`).toBe(true);
          return request;
        });

        const expected = { ...UNRELATED_MARKER, output_config: { effort: 'high' } };

        await test.step('The PATCH carries the new level and no trace of the old one', async () => {
          const body = update.postDataJSON() as {
            code?: { model?: { name?: string; custom_parameters?: Record<string, unknown> } };
          };
          expect(body.code?.model?.name, 'the payload names the newly chosen model').toBe(
            'claude-opus-4-5-20251101',
          );
          // Equality over the whole map, so a `max` left anywhere in it — a
          // sibling key, a second output_config entry — fails here.
          expect(
            body.code?.model?.custom_parameters,
            'the stale max is replaced, and the key no control knows about survives',
          ).toEqual(expected);
        });

        await test.step('…and the rule persists exactly that', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name).toBe('claude-opus-4-5-20251101');
          expect(
            model.customParameters,
            'what the judge will read the next time it runs',
          ).toEqual(expected);
        });
      },
    );

    test(
      "switching the judge's Gemini model replaces a level the new model rejects",
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({
        modelRegistryProviders,
        project,
        backendClient,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        test.setTimeout(180_000);
        expect(modelRegistryProviders.providers, 'the Gemini registry is selectable').toContain(
          'gemini',
        );

        const ruleName = `${testNamespace}-gemini-level`;
        // `off` because the model switched TO cannot turn thinking off at all,
        // which makes this the case where carrying the level over does not
        // merely cost money — the provider refuses the request.
        const seeded = { thinking: { level: 'off' }, ...UNRELATED_MARKER };

        const ruleId = await test.step('Seed a judge rule holding thinking level off', async () =>
          backendClient.createLlmJudgeRule({
            projectId: project.id,
            name: ruleName,
            samplingRate: 1,
            model: 'gemini-2.5-flash',
            temperature: 0,
            customParameters: seeded,
            messages: JUDGE_MESSAGES,
            variables: JUDGE_VARIABLES,
            schema: JUDGE_SCHEMA,
          }));

        await test.step('The seed really holds that level, before the browser opens', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name).toBe('gemini-2.5-flash');
          expect(model.customParameters, 'custom_parameters as seeded').toEqual(seeded);
        });

        const onlineEval = new OnlineEvaluationPage(page);

        await test.step('The dialog hydrates the level control to Off', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await onlineEval.openEditRuleDialogByName(ruleName);
          await onlineEval.openJudgeModelParameters();

          await expect(
            onlineEval.judgeParameterLabel('Thinking level'),
            'Gemini’s spelling of the same slot',
          ).toHaveCount(1);
          expect(
            await onlineEval.readJudgeEffort(),
            'read back from thinking.level, the rule’s only copy',
          ).toBe('Off');
          await onlineEval.closeJudgeModelParameters();
        });

        await test.step(`Change the model to ${REGISTRY_MODEL.geminiWithoutThinkingOff}`, async () => {
          await onlineEval.selectJudgeModel(REGISTRY_MODEL.geminiWithoutThinkingOff);
          await onlineEval.openJudgeModelParameters();
        });

        await test.step('The control settles on Low, the nearest level this model offers', async () => {
          expect(
            await onlineEval.judgeEffortOptions(),
            'no Off here — this model only works in thinking mode, which is why the seeded level ' +
              'cannot simply carry over',
          ).toEqual(['Auto', 'Low', 'Medium', 'High']);
          expect(
            await onlineEval.readJudgeEffort(),
            'Low is the nearest offered level to Off, and NOT this model’s default of Auto — so ' +
              'a picked level moves rather than being discarded',
          ).toBe('Low');
          await onlineEval.closeJudgeModelParameters();
        });

        const update = await test.step('Save, and capture the PATCH', async () => {
          const patched = page.waitForRequest(
            (request) => request.method() === 'PATCH' && isRuleUpdate(request.url(), ruleId),
          );
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'PATCH' && isRuleUpdate(response.url(), ruleId),
          );
          await onlineEval.submitRuleDialog();
          const request = await patched;
          const response = await answered;
          expect(response.ok(), `the save was accepted (got ${response.status()})`).toBe(true);
          return request;
        });

        const expected = { ...UNRELATED_MARKER, thinking: { level: 'low' } };

        await test.step('The PATCH carries the new level, with the free-form key intact', async () => {
          const body = update.postDataJSON() as {
            code?: { model?: { name?: string; custom_parameters?: Record<string, unknown> } };
          };
          expect(body.code?.model?.name).toBe('gemini-2.5-pro');
          expect(
            body.code?.model?.custom_parameters,
            'the thinking block is rewritten rather than merged over, so no off survives in it',
          ).toEqual(expected);
        });

        await test.step('…and the rule persists exactly that', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name).toBe('gemini-2.5-pro');
          expect(
            model.customParameters,
            'what the judge will send Google the next time it runs',
          ).toEqual(expected);
        });
      },
    );
  },
);
