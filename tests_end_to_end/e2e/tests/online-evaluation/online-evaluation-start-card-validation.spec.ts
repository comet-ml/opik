import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/** The three messages `schema.ts` refuses a judge prompt with. */
const NO_PROMPT = 'Select a template or write your own prompt';
const NO_VARIABLE = 'Add at least one variable, for example {{input}}';
const BAD_VARIABLE = 'A variable is not written correctly, use {{name}}';

/** Matches the create POST on both the `/opik/api` and bare `/api` mounts. */
function isRuleCreate(method: string, url: string): boolean {
  return (
    method === 'POST' &&
    /\/v1\/private\/automations\/evaluators\/?$/.test(new URL(url).pathname)
  );
}

/**
 * The submit blocks the start cards brought with them (opik#9143, OPIK-8372).
 *
 * `DEFAULT_LLM_AS_JUDGE_DATA` now starts at `messages: []` instead of a
 * prefilled example prompt, so the only thing between a new rule and a rule
 * posted with NO prompt at all is `requirePrompt`; and the only thing between a
 * trace-scope prompt and one the engine can never bind is
 * `requirePromptVariable`. Both are new refinements in `schema.ts` and nothing
 * else in the estate reads them, so a refinement lost in a later edit there
 * fails OPEN — the rule saves, and what the user gets back is a judge that
 * cannot run.
 *
 * Each assertion is exhaustive over the messages the editor is showing, not a
 * membership check on one of them, because the second half of this behaviour is
 * that the start error is the ONLY error. The cards hide the model selector and
 * the prompt box, so listing a model or a question error beside them points the
 * author at a field that is not on screen — the decision-model panel really did
 * that until this PR's last commit, which is the shape the Moderation test
 * below pins.
 *
 * Deterministic, free and entirely local to the page: no model is ever called,
 * and the judge half seeds a provider whose base URL refuses every connection
 * purely so the panel has a model to select. The absence of the POST is
 * asserted against a recorder that the deliberate create at the end of each
 * test proves was working.
 */
test.describe(
  'Online Evaluation — the start cards block a rule with no usable prompt',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'the LLM-as-a-Judge panel names the missing prompt, then the missing variable, and posts neither',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, providerKeys, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-judge-guard`;
        const rules = new OnlineEvaluationPage(page);

        await test.step('Seed a provider that cannot bill, only so a model is selectable', async () => {
          // The model is not the subject here, but it has to be set: with none
          // chosen the panel would also show "Model is required", and the
          // exhaustive comparisons below are about the PROMPT messages being
          // the whole story. Its base URL refuses every connection, so nothing
          // can be spent on it even by accident.
          await providerKeys.createUnreachable({
            providerName: `${testNamespace}-unreachable`,
            modelName: 'unreachable-model',
          });
        });

        // Armed before the first submit and never torn down: every create POST
        // for the rest of the test lands here, so the deliberate create at the
        // end proves the empty lists below were observed and not merely assumed.
        const creates: string[] = [];
        page.on('request', (request) => {
          if (isRuleCreate(request.method(), request.url())) creates.push(request.url());
        });

        await test.step('Open a new judge rule and name it, choosing no card', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanelOnStartCards('LLM-as-a-Judge');
          await rules.nameInput.fill(ruleName);
        });

        await test.step('Create is refused, and the only error names the missing prompt', async () => {
          await rules.submitButton.click();
          await expect
            .poll(() => rules.panelErrorMessages(), {
              message: 'the editor settles on exactly the start error',
            })
            .toEqual([NO_PROMPT]);
          await expect(
            rules.startCards,
            'the cards are still on screen for the author to choose from',
          ).toHaveCount(1);
          await expect(
            rules.nameInput,
            'and the panel stayed open rather than saving',
          ).toBeVisible();
          expect(creates, 'nothing was posted').toEqual([]);
        });

        await test.step('A prompt with no variable is refused by name', async () => {
          await rules.chooseStartCard('Type your prompt');
          await rules.selectJudgeModel('unreachable-model');
          await rules.replacePromptText('Decide whether the answer is any good.');

          await rules.submitButton.click();
          await expect
            .poll(() => rules.panelErrorMessages(), {
              message: 'the editor settles on exactly the missing-variable error',
            })
            .toEqual([NO_VARIABLE]);
          expect(creates, 'still nothing posted').toEqual([]);
        });

        await test.step('An unclosed variable is refused as malformed, not as missing', async () => {
          // A different message from the one above, and the distinction is the
          // whole of this step: `{{input}` parses to no variables at all, so a
          // refinement that only counted them would tell the author to add the
          // variable they have just written.
          await rules.replacePromptText('Decide whether the answer is any good: {{input}');

          await rules.submitButton.click();
          await expect
            .poll(() => rules.panelErrorMessages(), {
              message: 'the editor settles on exactly the malformed-variable error',
            })
            .toEqual([BAD_VARIABLE]);
          expect(creates, 'still nothing posted').toEqual([]);
        });

        await test.step('Closing the variable clears the error, and the rule creates', async () => {
          await rules.replacePromptText('Decide whether the answer is any good: {{input}}');
          await expect
            .poll(() => rules.panelErrorMessages(), {
              message: 'a well-formed variable leaves the editor with nothing to complain about',
            })
            .toEqual([]);

          // The control, without which every empty `creates` above is
          // unfalsifiable: "no POST was sent" reads the same way if the
          // recorder were mis-wired or the submit button had never been hit.
          // Same panel, same test, same recorder — one deliberate create.
          const ruleId = await rules.submitPanelAndCaptureRuleId();
          expect(ruleId, 'the create answered with an id').not.toBe('');
          expect(
            creates,
            'exactly one POST in the whole test — the one this step asked for',
          ).toHaveLength(1);
        });
      },
    );

    test(
      'the decision model panel shows the start error alone, for a question the author has not been offered',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-decision-guard`;
        const rules = new OnlineEvaluationPage(page);

        const creates: string[] = [];
        page.on('request', (request) => {
          if (isRuleCreate(request.method(), request.url())) creates.push(request.url());
        });

        await test.step('Open a new decision model rule and name it, choosing no card', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          // No provider key needed on this panel: a decisions model is offered
          // from a fixed list, so the form already has a model.
          await rules.openCreateRulePanelOnStartCards('Decision model');
          await rules.nameInput.fill(ruleName);
        });

        await test.step('Create is refused, and the start error is the only one shown', async () => {
          await rules.submitButton.click();
          // Exhaustive, and that is the assertion. The Questions card sits in
          // the other column with an empty Main question box, and the
          // decision-model refinement used to flag it here — "Write the
          // question for this score", for a question the author cannot have
          // written because the cards are still covering the prompt. It is
          // skipped until a card is chosen, and this is what says so.
          await expect
            .poll(() => rules.panelErrorMessages(), {
              message: 'the editor settles on exactly the start error',
            })
            .toEqual([NO_PROMPT]);
          await expect(rules.startCards, 'the cards are still on screen').toHaveCount(1);
          await expect(rules.nameInput, 'and the panel stayed open').toBeVisible();
          expect(creates, 'nothing was posted').toEqual([]);
        });

        await test.step('Choosing a card clears it, and the rule creates', async () => {
          await rules.chooseStartCard('Write your own');
          await rules.replacePromptText('Judge this: {{output}}');
          await rules.mainQuestionInput.fill('Is the output safe to send?');
          await expect
            .poll(() => rules.panelErrorMessages(), {
              message: 'a prompt and a question leave the editor with nothing to complain about',
            })
            .toEqual([]);

          // The same control as the judge test, for the same reason.
          const ruleId = await rules.submitPanelAndCaptureRuleId();
          expect(ruleId, 'the create answered with an id').not.toBe('');
          expect(
            creates,
            'exactly one POST in the whole test — the one this step asked for',
          ).toHaveLength(1);
        });
      },
    );
  },
);
