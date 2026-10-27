import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * "Cost limit (USD)" is the per-evaluation spending cap the rule editor panel
 * grew in opik#8976. It is the shape a field is only ever noticed in when it
 * was silently NOT saved: nothing on the rules list shows it, so a limit that
 * never reached the body reads exactly like one that did until a judge runs up
 * a bill nobody capped.
 *
 * Both surfaces, because they disagree by design and each half is a different
 * failure:
 *
 *   - CREATE: the panel must put the typed number in the POST body, and the
 *     rule must come back holding it.
 *   - HYDRATE: reopening must show the stored number, not an empty box — an
 *     empty box on a rule that has a limit is how a user clears one by
 *     accident on their next unrelated save.
 *   - CLEAR: emptying the box must send `max_cost_usd: null` EXPLICITLY. An
 *     omitted key is the sharp case: the update constructor leaves the old
 *     limit in place, so the box reads empty, the panel closes, and the rule
 *     is still capped at a number the user believes they removed. Only the
 *     outbound request tells "sent null" from "omitted"; after the fact the
 *     two are indistinguishable.
 *
 * Deterministic and provider-free — the rule never scores anything, so no key
 * is needed and the model is only there because the form requires one.
 *
 * The id comes from the POST's `Location` header rather than from a lookup by
 * name: the backend silently suffixes a clashing rule name with `-1`, so a
 * name lookup can hand back an older rule and report a perfectly healthy save
 * as one that lost the field (measured during exploration of this PR).
 */

/** Neither 0 nor 1 — a value only this test could have put there. */
const COST_LIMIT = '2.75';

/** Matches the rule PATCH on both the `/opik/api` and bare `/api` mounts. */
function isRuleUpdate(url: string, ruleId: string): boolean {
  return new URL(url).pathname.endsWith(`/v1/private/automations/evaluators/${ruleId}`);
}

test.describe(
  'Online Evaluation — judge cost limit',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'A judge cost limit persists on create, hydrates on reopen, and clears to an explicit null',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({
        modelRegistryProviders,
        project,
        backendClient,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        expect(
          modelRegistryProviders.providers,
          'the registry this test picks its model from is selectable',
        ).toContain('openai');

        const ruleName = `${testNamespace}-cost-limit`;
        const onlineEval = new OnlineEvaluationPage(page);

        const ruleId = await test.step('Create a judge rule with a cost limit typed in', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openCreateRulePanel('LLM-as-a-Judge', 'Task completion');
          await onlineEval.nameInput.fill(ruleName);
          await onlineEval.selectJudgeModel(REGISTRY_MODEL.openAiStandard);
          await onlineEval.setCostLimit(COST_LIMIT);
          return onlineEval.submitPanelAndCaptureRuleId();
        });

        await test.step('The created rule holds the typed limit', async () => {
          const body = await backendClient.getLlmJudgeRuleBody(ruleId);
          expect(body.name, 'the Location header addressed the rule just created').toBe(ruleName);
          expect(
            Object.keys(body.code),
            'the stored evaluator names max_cost_usd at all',
          ).toContain('max_cost_usd');
          expect(body.code.max_cost_usd, 'and holds the number that was typed').toBe(
            Number(COST_LIMIT),
          );
        });

        await test.step('Reopening the rule shows the stored limit', async () => {
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await onlineEval.openEditRulePanelByName(ruleName);
          expect(
            await onlineEval.readCostLimit(),
            'the panel hydrates the limit it saved',
          ).toBe(COST_LIMIT);
        });

        const update = await test.step('Clear the box and save', async () => {
          const patched = page.waitForRequest(
            (request) => request.method() === 'PATCH' && isRuleUpdate(request.url(), ruleId),
          );
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'PATCH' && isRuleUpdate(response.url(), ruleId),
          );
          await onlineEval.clearCostLimit();
          await onlineEval.submitPanel();
          const request = await patched;
          const response = await answered;
          expect(response.ok(), `the save was accepted (got ${response.status()})`).toBe(true);
          return request;
        });

        await test.step('The outbound PATCH carries max_cost_usd: null, not an omitted key', async () => {
          // The assertion this spec exists for. An omitted key leaves the old
          // limit in place server-side while the panel shows an empty box, and
          // the read-back below cannot tell that apart from a save that never
          // happened at all.
          const body = (update.postDataJSON() ?? {}) as Record<string, unknown>;
          const code = body.code;
          expect(code, 'the update payload carries a code block').toBeTruthy();
          expect(
            Object.keys(code as Record<string, unknown>),
            'the update payload names max_cost_usd',
          ).toContain('max_cost_usd');
          expect(
            (code as Record<string, unknown>).max_cost_usd,
            'and sends it as an explicit null',
          ).toBeNull();
        });

        await test.step('And the server really dropped the limit', async () => {
          const body = await backendClient.getLlmJudgeRuleBody(ruleId);
          // Written as "whatever the server answers means no limit": measured
          // against 2.2.115 the key is dropped from the response entirely once
          // cleared, and an explicit `null` would mean the same thing. The
          // strict claim — that the FORM sent null rather than omitting the
          // field — is the step above, where it is the only place observable.
          expect(body.code.max_cost_usd ?? null, 'the rule is no longer capped').toBeNull();
        });

        await test.step('And the reopened panel shows an empty box', async () => {
          await onlineEval.openEditRulePanelByName(ruleName);
          expect(
            await onlineEval.readCostLimit(),
            'a rule with no limit hydrates empty',
          ).toBe('');
          await onlineEval.cancelPanel();
        });
      },
    );
  },
);
