import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import type {
  BackendClient,
  JudgeMessageWrite,
  JudgeOutputSchemaType,
  ProjectRef,
} from '@e2e/core/backend';
import { expectPinApplied, pinOpenAiPipelineMode } from '@e2e/core/openai-pipeline-mode';
import {
  readSentCustomParameters,
  submitAndCaptureRuleUpdate,
} from '@e2e/core/rule-editor-save';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * A judge rule does not resend a reasoning effort the OpenAI key's pipeline
 * mode refuses (OPIK-8748, opik#9153's second commit).
 *
 * `max` is a Responses-API-only level: OpenAI answers 400 for it on Chat
 * Completions. So the rule editor now learns the workspace key's
 * `openai_pipeline_mode` from the provider-keys query and gates both the
 * offered levels and the SAVED value on it — a stored `max` is shown as the
 * model's default and dropped from the payload while the key is on Chat
 * Completions, and kept while it is on the Responses API.
 *
 * ## What a human cares about being wrong
 *
 * Both directions are silent. Resending `max` on a Chat Completions key turns
 * every scoring run on that rule into a 400 — the rule sits in the list looking
 * enabled and simply stops producing scores. Dropping it on a Responses-API key
 * silently downgrades a model the user is paying for. Neither shows anywhere on
 * the rules list.
 *
 * ## Why the drop must not take the rest of the block with it
 *
 * `withOpenAIReasoningEffort` rebuilds `custom_parameters` to remove the
 * effort, so the question "did it remove ONLY the effort" is a real one.
 * `unrelated_marker` is a top-level key no editor control has ever heard of; it
 * catches a serializer that discards everything it cannot name, which would
 * read as a clean strip without it.
 *
 * ## How the pipeline mode is set, and why not on the real key
 *
 * `pinOpenAiPipelineMode` rewrites that ONE field on the real provider-keys
 * response on its way to the browser. Staging's OpenAI key carries no mode (it
 * normalises to `chat_completions_api`) and is shared by every other spec and
 * every human on the deployment, so flipping its stored value is not an option
 * — see `core/openai-pipeline-mode.ts` for why this rather than a fabricated
 * body. `expectPinApplied` is what stops both tests passing vacuously on a
 * workspace with no OpenAI key at all, where the pin would be a no-op and the
 * panel would fall back to the Chat Completions default.
 *
 * ## Why this is not already covered
 *
 * `playground-openai-effort-pipeline-mode.spec.ts` asserts the same gate on the
 * PLAYGROUND, whose save path is a different function and whose stored shape is
 * a prompt rather than a rule. Nothing in the estate read a rule's reasoning
 * effort at all before 2.2.133.
 *
 * ## Deterministic and provider-free
 *
 * Each rule is seeded DISABLED at 0% sampling into a project holding no traces
 * — two independent reasons no judge can ever execute — and the save is a
 * PATCH, never a completion.
 */

/** The model under test, and the id the rule stores it as. */
const MODEL_NAME = REGISTRY_MODEL.openAiResponsesOnlyEffort;
const MODEL_ID = 'gpt-5.6-sol';

/** The Responses-API-only level, as the control labels it and as it is stored. */
const RESPONSES_ONLY = { label: 'Max', value: 'max' } as const;

/**
 * The block each rule is seeded with: the refused level, plus a bystander key.
 *
 * The bystander is load-bearing — see the file header. Without it, "the effort
 * is gone" would be satisfied by a save that wiped `custom_parameters` whole.
 */
const SEEDED_CUSTOM_PARAMETERS = {
  reasoning_effort: RESPONSES_ONLY.value,
  unrelated_marker: 'keep-me',
} as const;

/** The effort gone, the bystander intact. */
const EFFORT_WITHHELD = { unrelated_marker: 'keep-me' } as const;

const JUDGE_MESSAGES: JudgeMessageWrite[] = [
  { role: 'USER', content: 'Is the OUTPUT non-empty?\n\nOUTPUT:\n{{output}}' },
];

const JUDGE_VARIABLES = { output: 'output.output' };

const JUDGE_SCHEMA: Array<{ name: string; type: JudgeOutputSchemaType; description: string }> = [
  {
    name: 'Non empty',
    type: 'BOOLEAN',
    description: 'Returns true when the output is non-empty',
  },
];

test.describe(
  'Online evaluation — a rule’s reasoning effort and the key’s pipeline mode',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'on a Chat Completions key, Max is not offered and a stored max is not resent',
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
          'the OpenAI registry is selectable',
        ).toContain('openai');

        const ruleName = `${testNamespace}-chat-completions`;
        const onlineEval = new OnlineEvaluationPage(page);
        const pin = await pinOpenAiPipelineMode(page, 'chat_completions_api');
        const ruleId = await seedMaxEffortRule({ backendClient, project, ruleName });

        await test.step('The control does not offer Max', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          expect(
            await onlineEval.readModelPickerText(),
            'the editor hydrated onto the seeded rule',
          ).toContain(MODEL_NAME);
          await onlineEval.openJudgeModelParameters();
          // The whole offered list, not a membership check: "Max is gone" and
          // "the levels are gone" are different regressions, and
          // `judgeEffortOptions` also asserts the DISPLAYED level is one of
          // them — so a panel showing the refused value would fail here rather
          // than reading as a clean gate.
          expect(
            await onlineEval.judgeEffortOptions(),
            'a Chat Completions key is never offered the Responses-only level',
          ).not.toContain(RESPONSES_ONLY.label);
        });

        const update = await test.step('Save the rule after the smallest unrelated edit', async () => {
          // A fresh editor: the gear is open above, and an Escape aimed at it
          // closes the whole form. The Enabled switch is the estate's idiom for
          // the smallest edit that is not the field under test — Save stays
          // disabled until something changes, so an unedited save is not a
          // gesture the product offers.
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.toggleEnabled();
          return submitAndCaptureRuleUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH carries no max, and still carries the bystander', async () => {
          expect(
            readSentCustomParameters(update),
            'the refused level is withheld and nothing else is lost',
          ).toEqual(EFFORT_WITHHELD);
        });

        await test.step('And that is what persisted, on the same model', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the save did not move the model').toBe(MODEL_ID);
          expect(
            model.customParameters,
            'the persisted blob agrees with the payload',
          ).toEqual(EFFORT_WITHHELD);
        });

        // Last, not first: a pin that rewrote nothing makes every assertion
        // above pass for a reason that has nothing to do with the gate.
        expectPinApplied(pin);
      },
    );

    test(
      'on a Responses API key, Max is displayed and a stored max survives the save',
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
        // The control that makes the test above evidence about the MODE. On its
        // own, "max is dropped" is equally satisfied by a build that drops the
        // level unconditionally — which is the pre-#9153 behaviour and the one
        // regression this pair exists to catch.
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry is selectable',
        ).toContain('openai');

        const ruleName = `${testNamespace}-responses-api`;
        const onlineEval = new OnlineEvaluationPage(page);
        const pin = await pinOpenAiPipelineMode(page, 'responses_api');
        const ruleId = await seedMaxEffortRule({ backendClient, project, ruleName });

        await test.step('The control offers Max, and displays the stored one', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.readJudgeEffort(),
            'the panel reads the level the rule actually holds',
          ).toBe(RESPONSES_ONLY.label);
          expect(
            await onlineEval.judgeEffortOptions(),
            'this key’s mode accepts the Responses-only level',
          ).toContain(RESPONSES_ONLY.label);
        });

        const update = await test.step('Save the rule after the smallest unrelated edit', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.toggleEnabled();
          return submitAndCaptureRuleUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH sends the block back unchanged', async () => {
          expect(
            readSentCustomParameters(update),
            'a mode that accepts the level keeps it — no strip, no reshaping',
          ).toEqual(SEEDED_CUSTOM_PARAMETERS);
        });

        await test.step('And the rule still holds it', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the save did not move the model').toBe(MODEL_ID);
          expect(
            model.customParameters,
            'the persisted blob agrees with the payload',
          ).toEqual(SEEDED_CUSTOM_PARAMETERS);
        });

        expectPinApplied(pin);
      },
    );
  },
);

/**
 * Seed a disabled, never-sampled rule holding the refused level and a bystander
 * key, and assert over REST that it really holds them.
 *
 * The read-back is the premise of both tests: each is about what happens to a
 * value that is ALREADY stored, and a rule that never carried `max` would
 * satisfy "max is not resent" vacuously — and forever, because a capability
 * counted as covered is never re-derived.
 */
async function seedMaxEffortRule(args: {
  backendClient: BackendClient;
  project: ProjectRef;
  ruleName: string;
}): Promise<string> {
  const { backendClient, project, ruleName } = args;
  return test.step(`seed rule "${ruleName}" on ${MODEL_ID} holding reasoning_effort: max`, async () => {
    const ruleId = await backendClient.createLlmJudgeRule({
      projectId: project.id,
      name: ruleName,
      // Disabled AND at 0% sampling: two independent reasons this rule can
      // never reach a provider, so the placeholder key the registry fixture may
      // have written cannot turn into a failed provider call.
      enabled: false,
      samplingRate: 0,
      model: MODEL_ID,
      customParameters: SEEDED_CUSTOM_PARAMETERS,
      messages: JUDGE_MESSAGES,
      variables: JUDGE_VARIABLES,
      schema: JUDGE_SCHEMA,
    });

    const seeded = await backendClient.getLlmJudgeModel(ruleId);
    expect(seeded.name, 'the seeded rule names the Responses-only-effort model').toBe(MODEL_ID);
    expect(
      seeded.customParameters,
      'custom_parameters as seeded, before any UI interaction',
    ).toEqual(SEEDED_CUSTOM_PARAMETERS);
    return ruleId;
  });
}

/** Reload the Online Evaluation page and open one rule's editor panel. */
async function openEditPanelOn(
  onlineEval: OnlineEvaluationPage,
  projectId: string,
  ruleName: string,
): Promise<void> {
  await onlineEval.goto(projectId);
  await onlineEval.waitForReady();
  await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
  await onlineEval.openEditRulePanelByName(ruleName);
}
