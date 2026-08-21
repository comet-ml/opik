import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/** Names every variable the seeded rules map, so the dialog re-parses all three. */
const SEED_PROMPT = 'Judge this. {{input}} {{spans}} {{trace}}';

/**
 * A rule whose reserved variables both hold their sentinels — what the dialog
 * writes for itself under the agentic-tools default.
 */
const SENTINEL_VARIABLES: Record<string, string> = {
  input: 'input.question',
  spans: 'spans',
  trace: 'trace',
};

/**
 * The same rule with a custom `spans` path. Only the API can produce this state
 * now — under the agentic-tools default the dialog hides the auto-filled row,
 * so there is no input left to type a custom path into — which is precisely why
 * it needs pinning: a mapping the UI cannot re-create is a mapping the UI must
 * not destroy.
 */
const OVERRIDE_VARIABLES: Record<string, string> = {
  input: 'input.question',
  spans: 'input.custom_spans',
  trace: 'trace',
};

/** The `code.variables` map of a PATCH body, as the edit dialog sends it. */
function patchedVariables(postData: unknown): unknown {
  return (postData as { code?: { variables?: unknown } } | null)?.code?.variables;
}

test.describe('Online Evaluation — edit rule', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('Editing a rule preserves its persisted variable mapping, custom overrides included', { tag: ['@cap:online-evaluation.edit-rule'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // The silent-wrongness case. The edit dialog re-derives the variable list
    // from the prompt every time it renders, and under the agentic-tools
    // default that derivation can fill a reserved name with a sentinel. If it
    // ever applied to a variable the user had already mapped, the offending row
    // would be HIDDEN — the mapping would be overwritten with no on-screen
    // trace of it, and the rule would silently start reading the wrong path.
    //
    // So the assertions straddle both surfaces: what the dialog shows, and what
    // it sends. A UI-only check cannot see a clobbered hidden row, and an
    // API-only check cannot see that the override stayed editable.
    const sentinelRule = `${testNamespace}-sentinel`;
    const overrideRule = `${testNamespace}-override`;

    const onlineEval = new OnlineEvaluationPage(page);

    const seeded = await test.step('Seed two LLM-judge rules through the API', async () => ({
      sentinel: await backendClient.createLlmJudgeRule({
        name: sentinelRule,
        projectId: project.id,
        prompt: SEED_PROMPT,
        variables: SENTINEL_VARIABLES,
      }),
      override: await backendClient.createLlmJudgeRule({
        name: overrideRule,
        projectId: project.id,
        prompt: SEED_PROMPT,
        variables: OVERRIDE_VARIABLES,
      }),
    }));

    await test.step('The seed really holds the two states before the browser opens', async () => {
      // Everything below reads as coverage whether or not the override was
      // actually stored: a rule seeded with a sentinel `spans` would hide the
      // same row and pass the same UI assertions. Prove the discriminating
      // state exists first, or the rest of this test cannot fail.
      expect(await backendClient.getLlmJudgeRuleVariables(seeded.sentinel.id)).toEqual(
        SENTINEL_VARIABLES,
      );
      expect(await backendClient.getLlmJudgeRuleVariables(seeded.override.id)).toEqual(
        OVERRIDE_VARIABLES,
      );
    });

    await test.step('Both rules are listed', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await expect(onlineEval.ruleRow(sentinelRule)).toHaveCount(1);
      await expect(onlineEval.ruleRow(overrideRule)).toHaveCount(1);
    });

    await test.step('The sentinel rule opens with both reserved rows hidden', async () => {
      await onlineEval.openEditRuleDialog(sentinelRule);
      await onlineEval.expectVariableMappingRows(['input']);
      await expect(onlineEval.variableMappingInput('input')).toHaveValue('input.question');
    });

    await test.step('Re-saving the sentinel rule sends its mapping back verbatim', async () => {
      const patched = page.waitForResponse(
        (r) =>
          r.url().includes(`/v1/private/automations/evaluators/${seeded.sentinel.id}`) &&
          r.request().method() === 'PATCH',
      );
      await onlineEval.submitDialog();

      const response = await patched;
      expect(response.status(), 'the unedited re-save is accepted').toBe(204);
      expect(
        patchedVariables(response.request().postDataJSON()),
        'a re-save must not re-derive the mapping',
      ).toEqual(SENTINEL_VARIABLES);
    });

    await test.step('A custom spans path stays visible and editable', async () => {
      await onlineEval.openEditRuleDialog(overrideRule);
      // `spans` survives on screen because its value differs from the sentinel,
      // while `trace` — which holds the sentinel — is filtered out. The list
      // discriminates on the value, not on the name, and that is the whole
      // reason a user-set path does not become write-only.
      await onlineEval.expectVariableMappingRows(['input', 'spans']);
      await expect(onlineEval.variableMappingInput('spans')).toHaveValue('input.custom_spans');
      await expect(onlineEval.variableMappingInput('input')).toHaveValue('input.question');
      await expect(
        onlineEval.variableMappingRow('trace'),
        'trace still holds its sentinel, so it is still hidden',
      ).toHaveCount(0);
    });

    await test.step('Re-saving the override rule does not clobber it with the sentinel', async () => {
      const patched = page.waitForResponse(
        (r) =>
          r.url().includes(`/v1/private/automations/evaluators/${seeded.override.id}`) &&
          r.request().method() === 'PATCH',
      );
      await onlineEval.submitDialog();

      const response = await patched;
      expect(response.status(), 'the unedited re-save is accepted').toBe(204);
      expect(
        patchedVariables(response.request().postDataJSON()),
        'the custom spans path is sent back unchanged',
      ).toEqual(OVERRIDE_VARIABLES);
    });

    await test.step('Both rules read back from the API exactly as seeded', async () => {
      // The PATCH bodies above say what the dialog sent; this says what the
      // deployment now holds. A rule that took the round-trip and came back
      // different is the regression, wherever it happened.
      expect(await backendClient.getLlmJudgeRuleVariables(seeded.sentinel.id)).toEqual(
        SENTINEL_VARIABLES,
      );
      expect(await backendClient.getLlmJudgeRuleVariables(seeded.override.id)).toEqual(
        OVERRIDE_VARIABLES,
      );
    });
  });
});
