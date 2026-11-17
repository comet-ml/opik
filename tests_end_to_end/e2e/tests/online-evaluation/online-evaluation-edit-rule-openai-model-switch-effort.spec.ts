import { test, expect, OPEN_ROUTER_MODEL, REGISTRY_MODEL } from '@e2e/fixtures';
import type {
  BackendClient,
  JudgeMessageWrite,
  JudgeOutputSchemaType,
  ProjectRef,
} from '@e2e/core/backend';
import { registryModelIds } from '@e2e/core/model-registry';
import {
  readSentCustomParameters,
  sentCustomParameters,
  submitAndCaptureRuleUpdate,
} from '@e2e/core/rule-editor-save';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * Switching an LLM-judge rule's MODEL must take the previous model's OPENAI or
 * OPENROUTER reasoning effort with it (OPIK-8748, opik#9153).
 *
 * `online-evaluation-edit-rule-model-switch-effort.spec.ts` is the ANTHROPIC
 * half of this contract — `output_config.effort`, built by a different branch of
 * `updateConfigForModelChange` — and the two must be read together. The two
 * halves here are new in 2.2.133, because before #9153 a rule could not store
 * an OpenAI or OpenRouter effort at all, and they regress independently of the
 * Anthropic one: the strip is keyed on `getRuleReasoningEffortOptions` and
 * `getPersistedReasoningEffort`, neither of which the Anthropic path calls.
 *
 * ## What a human cares about being wrong
 *
 * A stale effort either bills for reasoning the user did not choose, or is
 * rejected outright by a provider that does not take the parameter. Nothing on
 * the rules list says either has happened — the row keeps its name, its status
 * and its sampling rate, and the judge simply stops agreeing with the form.
 *
 * ## A switch WITHIN a provider and a switch ACROSS providers differ
 *
 * Only the within-provider switch is the effort-only strip: the effort goes and
 * `unrelated_marker` stays. A switch to another PROVIDER clears
 * `custom_parameters` wholesale, bystanders included —
 * `updateConfigForModelChange` does it on purpose, since the extra body belongs
 * to the provider being left. The two cases are therefore asserted against
 * different expectations, and conflating them is the mistake the Anthropic spec
 * records having made.
 *
 * ## Both surfaces, and a control
 *
 * The strip happens in the frontend, so each test reads the outbound PATCH as
 * well as the persisted blob — that is what separates "the form dropped it"
 * from "the backend did" — and the first and last tests read the control on
 * screen too, because a panel showing a level the rule no longer holds is its
 * own regression.
 *
 * The CONTROL test is load-bearing. The three switches all show an effort
 * disappearing; on their own they would be satisfied by a build that dropped it
 * on EVERY save, including the ordinary open-and-save that is the commonest
 * gesture on this page. It is what makes them evidence about the switch.
 *
 * ## Deterministic and provider-free
 *
 * Every rule is seeded over REST — so a create-path regression cannot be
 * mistaken for an edit-path one — DISABLED and at 0% sampling, into a project
 * holding no traces. No judge can execute and nothing reaches a provider.
 */

/** The native OpenAI reasoning model the rules start on, and its stored id. */
const OPENAI_SOURCE_NAME = REGISTRY_MODEL.openAiReasoning;
const OPENAI_SOURCE_ID = 'gpt-5-mini';

/** A native OpenAI model with no effort option list at all. */
const OPENAI_TARGET_NAME = REGISTRY_MODEL.openAiStandard;
const OPENAI_TARGET_ID = 'gpt-4o-mini';

/**
 * The cross-provider target: a Claude model whose capabilities row EXISTS but
 * lists no thinking-effort levels.
 *
 * Chosen rather than any Claude model, and the choice is load-bearing. The
 * claim these tests make is that NOTHING of the previous provider's block
 * survives — so the target must not contribute a block of its own. A Claude
 * model that does offer levels has its default fitted on the switch, and the
 * save would then write `output_config.effort` for the NEW provider, which is
 * correct behaviour that would read here as a failed clear.
 */
const ANTHROPIC_TARGET_NAME = REGISTRY_MODEL.claudeWithoutEffort;
const ANTHROPIC_TARGET_ID = 'claude-haiku-4-5-20251001';

/** The level the rules are seeded at, and how the control labels it. */
const SEEDED = { label: 'High', value: 'high' } as const;

/** The seeded block for the native-OpenAI cases: the effort, plus a bystander. */
const SEEDED_FLAT = {
  reasoning_effort: SEEDED.value,
  unrelated_marker: 'keep-me',
} as const;

/** The same for the OpenRouter case, where the effort lives nested. */
const SEEDED_NESTED = {
  reasoning: { effort: SEEDED.value },
  unrelated_marker: 'keep-me',
} as const;

/** The effort gone, the bystander intact — the within-provider shape. */
const EFFORT_DROPPED = { unrelated_marker: 'keep-me' } as const;

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
  'Online evaluation — edit rule, model switch and the stored reasoning effort',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'switching within OpenAI onto a model that takes no effort drops it, and the control goes with it',
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

        const ruleName = `${testNamespace}-openai-to-standard`;
        const onlineEval = new OnlineEvaluationPage(page);
        const ruleId = await seedRule({
          backendClient,
          project,
          ruleName,
          model: OPENAI_SOURCE_ID,
          customParameters: SEEDED_FLAT,
        });

        await test.step('The editor shows the stored effort before anything is switched', async () => {
          // The screen half of the premise, read BEFORE the switch: "the
          // control is gone" below would otherwise be satisfied by a panel that
          // never rendered it in the first place.
          await openEditPanelOn(onlineEval, project.id, ruleName);
          expect(
            await onlineEval.readModelPickerText(),
            'the editor hydrated onto the seeded rule',
          ).toContain(OPENAI_SOURCE_NAME);
          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.readJudgeEffort(),
            'the panel reads the level the rule actually holds',
          ).toBe(SEEDED.label);
        });

        const update = await test.step(`Switch the model to ${OPENAI_TARGET_NAME} and save`, async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.selectJudgeModel(OPENAI_TARGET_NAME);
          return submitAndCaptureRuleUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH carries the block without the effort', async () => {
          expect(
            readSentCustomParameters(update),
            'the effort belonged to the model being left; the bystander key did not',
          ).toEqual(EFFORT_DROPPED);
        });

        await test.step('And that is what persisted, on the new model', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the rule moved onto the non-reasoning model').toBe(OPENAI_TARGET_ID);
          expect(
            model.customParameters,
            'the persisted blob agrees with the payload',
          ).toEqual(EFFORT_DROPPED);
        });

        await test.step('Reopened, the panel offers no Reasoning effort at all', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.openJudgeModelParameters();
          // Temperature first: this target DOES have a tunable parameter, so a
          // panel holding it is proof the panel opened and rendered. Without
          // that, "no Reasoning effort" would also be true of a gear that
          // failed to open — which is how this assertion would rot into a pass.
          expect(
            await onlineEval.mountedJudgeParameterIds(),
            'the panel is open and holds this model’s one tunable parameter',
          ).toEqual(['temperature']);
          await expect(
            onlineEval.judgeEffortTrigger,
            'no effort control for a model with no effort levels',
          ).toHaveCount(0);
          await onlineEval.closeJudgeModelParameters();
        });
      },
    );

    test(
      'switching from OpenAI onto another provider clears the whole free-form block',
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
          'both registries this test switches between are selectable',
        ).toEqual(expect.arrayContaining(['openai', 'anthropic']));

        const ruleName = `${testNamespace}-openai-to-anthropic`;
        const onlineEval = new OnlineEvaluationPage(page);
        const ruleId = await seedRule({
          backendClient,
          project,
          ruleName,
          model: OPENAI_SOURCE_ID,
          customParameters: SEEDED_FLAT,
        });

        const update = await test.step(`Switch the model to ${ANTHROPIC_TARGET_NAME} and save`, async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          expect(
            await onlineEval.readModelPickerText(),
            'the editor hydrated onto the seeded rule',
          ).toContain(OPENAI_SOURCE_NAME);
          await onlineEval.selectJudgeModel(ANTHROPIC_TARGET_NAME);
          return submitAndCaptureRuleUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH carries no free-form block at all', async () => {
          // Not the effort-only strip above: a PROVIDER change clears
          // `custom_parameters` wholesale, so the bystander does NOT survive
          // here and this test must not claim it does.
          expect(
            sentCustomParameters(update),
            'the next provider is not handed the previous one’s extra body',
          ).toBeUndefined();
        });

        await test.step('And that is what persisted, on the new model', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the rule moved onto the Anthropic model').toBe(ANTHROPIC_TARGET_ID);
          // Null and absent are the same answer for "the rule holds no
          // free-form parameters", and the backend may store either.
          expect(
            model.customParameters ?? null,
            'nothing of the previous provider’s block survived the save',
          ).toBeNull();
        });
      },
    );

    test(
      'switching from an OpenRouter model onto another provider leaves no nested effort behind',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({
        modelRegistryProviders,
        openRouterNativeModels,
        project,
        backendClient,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        test.setTimeout(180_000);
        // The nested shape is read and stripped by its own branch of
        // `withReasoningEffort`, so it regresses apart from the flat one above:
        // a strip that only knew about `reasoning_effort` would leave
        // `reasoning.effort` on the rule and pass the OpenAI test.
        // That the id is OFFERED under OpenRouter is the `openRouterNativeModels`
        // fixture's own precondition, asserted there before a browser opens.
        // What this needs beside it is the fact that gives the id an effort
        // control at all: a listed native OpenAI reasoning row behind it.
        expect(
          modelRegistryProviders.providers,
          'the Anthropic registry is selectable',
        ).toContain('anthropic');
        expect(
          registryModelIds(openRouterNativeModels.registry, 'openai').has('gpt-5-nano'),
          'the native OpenAI reasoning row behind this id is listed — which is what makes it ' +
            'a reasoning model to this panel',
        ).toBe(true);

        const ruleName = `${testNamespace}-openrouter-to-anthropic`;
        const onlineEval = new OnlineEvaluationPage(page);
        const ruleId = await seedRule({
          backendClient,
          project,
          ruleName,
          model: OPEN_ROUTER_MODEL.openAiReasoning,
          customParameters: SEEDED_NESTED,
        });

        await test.step('The editor reads the NESTED effort before anything is switched', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          expect(
            await onlineEval.readModelPickerText(),
            'the editor hydrated onto the seeded rule',
          ).toContain(OPEN_ROUTER_MODEL.openAiReasoning);
          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.readJudgeEffort(),
            'the panel reads custom_parameters.reasoning.effort, OpenRouter’s own slot',
          ).toBe(SEEDED.label);
        });

        const update = await test.step(`Switch the model to ${ANTHROPIC_TARGET_NAME} and save`, async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.selectJudgeModel(ANTHROPIC_TARGET_NAME);
          return submitAndCaptureRuleUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH carries no free-form block at all', async () => {
          expect(
            sentCustomParameters(update),
            'neither the nested effort nor the bystander follows the rule across providers',
          ).toBeUndefined();
        });

        await test.step('And that is what persisted, on the new model', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the rule moved onto the Anthropic model').toBe(ANTHROPIC_TARGET_ID);
          expect(
            model.customParameters ?? null,
            'no nested effort survived the save',
          ).toBeNull();
        });
      },
    );

    test(
      'CONTROL — a save with no model switch keeps the stored effort, so the strip is conditional on the switch',
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
          'the OpenAI registry is selectable',
        ).toContain('openai');

        const ruleName = `${testNamespace}-unswitched`;
        const onlineEval = new OnlineEvaluationPage(page);
        const ruleId = await seedRule({
          backendClient,
          project,
          ruleName,
          model: OPENAI_SOURCE_ID,
          customParameters: SEEDED_FLAT,
        });

        const update = await test.step('Open the rule and save it without switching the model', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          expect(
            await onlineEval.readModelPickerText(),
            'the editor hydrated onto the seeded rule',
          ).toContain(OPENAI_SOURCE_NAME);
          // The editor keeps Save disabled until something changes, so a
          // literally unedited save is no longer a gesture the product offers.
          // The Enabled switch is the estate's idiom for the smallest edit that
          // is not the field under test, and it keeps this control's role
          // intact: what has to be shown is that an edit which is NOT a model
          // switch leaves the effort alone.
          await onlineEval.toggleEnabled();
          return submitAndCaptureRuleUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH sends the block back unchanged', async () => {
          expect(readSentCustomParameters(update), 'no switch, no strip').toEqual(SEEDED_FLAT);
        });

        await test.step('And the rule still holds it, on the model it started on', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the save did not move the model').toBe(OPENAI_SOURCE_ID);
          expect(
            model.customParameters,
            'the effort survives a save that did not touch the model',
          ).toEqual(SEEDED_FLAT);
        });

        await test.step('Reopened, the panel still reads the stored level', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.readJudgeEffort(),
            'the control still shows the seeded level',
          ).toBe(SEEDED.label);
          await onlineEval.closeJudgeModelParameters();
        });
      },
    );
  },
);

/**
 * Seed a disabled, never-sampled LLM-judge rule holding an effort block, and
 * assert over REST that it really holds it.
 *
 * The read-back is the premise of every assertion in this file: each one is
 * about a value that CHANGED, and a rule that never carried the block would
 * satisfy "the effort is gone" perfectly — vacuously, and forever, because a
 * capability counted as covered is never re-derived.
 */
async function seedRule(args: {
  backendClient: BackendClient;
  project: ProjectRef;
  ruleName: string;
  model: string;
  customParameters: Record<string, unknown>;
}): Promise<string> {
  const { backendClient, project, ruleName, model, customParameters } = args;
  return test.step(`seed rule "${ruleName}" on ${model} holding ${JSON.stringify(customParameters)}`, async () => {
    const ruleId = await backendClient.createLlmJudgeRule({
      projectId: project.id,
      name: ruleName,
      // Disabled AND at 0% sampling: two independent reasons this rule can
      // never reach a provider, so the placeholder keys the registry fixtures
      // may have written cannot turn into a failed provider call.
      enabled: false,
      samplingRate: 0,
      model,
      customParameters,
      messages: JUDGE_MESSAGES,
      variables: JUDGE_VARIABLES,
      schema: JUDGE_SCHEMA,
    });

    const seeded = await backendClient.getLlmJudgeModel(ruleId);
    expect(seeded.name, 'the seeded rule names the judge model').toBe(model);
    expect(
      seeded.customParameters,
      'custom_parameters as seeded, before any UI interaction',
    ).toEqual(customParameters);
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
