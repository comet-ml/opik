import { test, expect, OPEN_ROUTER_GROUP, OPEN_ROUTER_MODEL, REGISTRY_MODEL } from '@e2e/fixtures';
import type {
  BackendClient,
  JudgeMessageWrite,
  JudgeOutputSchemaType,
  ProjectRef,
} from '@e2e/core/backend';
import { registryModelIds } from '@e2e/core/model-registry';
import {
  readSentCustomParameters,
  submitAndCaptureRuleUpdate,
} from '@e2e/core/rule-editor-save';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * An LLM-judge rule now offers and persists the OpenAI / OpenRouter reasoning
 * effort, each in its own provider's shape and in exactly one copy
 * (OPIK-8748, opik#9153).
 *
 * Before this release `RULE_UNSUPPORTED_PARAMS` stripped `reasoningEffort` on
 * this surface, so a reasoning model's gear showed nothing a rule could set and
 * the value could not be stored at all. #9153 removed it from that set and
 * taught the editor to read and write it — `custom_parameters.reasoning_effort`
 * for a native OpenAI model, `custom_parameters.reasoning.effort` for an
 * OpenRouter-routed one, and the nested slot for an OpenAI model that already
 * carries other `reasoning` keys.
 *
 * ## What a human cares about being wrong
 *
 * Nothing on the rules list shows a judge's reasoning effort. An effort lost on
 * save is a judge that never reasons; an effort the user did not pick is a
 * judge billing for reasoning nobody asked for; and two disagreeing copies are
 * a 400 from OpenRouter, which stops the rule scoring with no sign on the row.
 * All three are silent.
 *
 * ## Both surfaces, deliberately
 *
 * The panel decides which levels to offer and which one to display; the save
 * path (`convertLLMJudgeDataToLLMJudgeObject` -> `withReasoningEffort`) decides
 * what lands in `custom_parameters`. They regress apart: a gear showing Low
 * over a save that stored nothing looks entirely correct, and an API-only
 * assertion would pass over a panel that could never set the value. So each
 * test reads the screen, the outbound body where there is one, and the
 * persisted blob.
 *
 * ## Why this is not already covered
 *
 * `online-evaluation-llm-judge-thinking-effort.spec.ts` is the same shape for
 * ANTHROPIC's `output_config.effort`, a different slot built by a different
 * branch. `online-evaluation-llm-judge-model-parameters.spec.ts` and
 * `online-evaluation-openrouter-judge-model-parameters.spec.ts` assert the
 * PRE-#9153 behaviour — that an OpenAI reasoning model shows no gear at all —
 * and so will fail by design on 2.2.133; they are someone else's specs and are
 * left alone. `playground-openai-effort-pipeline-mode.spec.ts` drives the
 * effort on the PLAYGROUND, which is a different save path entirely (the
 * playground was never affected, because the behaviour change is driven by the
 * rule's `unsupportedParams` set).
 *
 * ## Deterministic and LLM-free by construction
 *
 * Every rule here is either never enabled or seeded DISABLED at 0% sampling,
 * and the fixture project holds no traces, so no judge can ever execute and
 * nothing reaches a provider. The registry fixtures only have to make the
 * pickers OFFER these models; a placeholder key is enough for that.
 *
 * ## Why each phase reopens the page
 *
 * The effort control lives inside the model-parameters gear, a `DropdownMenu`
 * over the editor: while it is open the submit cannot be clicked, and
 * dismissing it with Escape lands on the DIALOG and discards the form. A phase
 * that only READS the effort therefore reloads rather than closing the gear —
 * the same choice `online-evaluation-edit-rule-model-switch-effort.spec.ts`
 * makes, and for the same reason.
 */

/**
 * The level every test picks, and the value it must store.
 *
 * `Low` because it is offered by the Chat Completions list, the Responses list
 * and OpenRouter's published list for every model here, so one constant covers
 * all three cases — and because it is neither model's default, which is what
 * makes picking it an actual change rather than a no-op. That it differs from
 * the displayed default is ASSERTED per case rather than assumed: a build whose
 * default moved onto Low would otherwise turn every round trip below into a
 * test of the default.
 */
const CHOSEN = { label: 'Low', value: 'low' } as const;

/** The level the two seeded-copy tests start from, in the OpenAI nested slot. */
const SEEDED_NESTED = { label: 'Low', value: 'low' } as const;

/** The level the nested-sibling test moves to, so the save is a change. */
const RAISED = { label: 'High', value: 'high' } as const;

/**
 * The id behind `REGISTRY_MODEL.openAiReasoning`, as the rule stores it.
 *
 * Written out because the persisted assertion is about the MODEL as well as
 * its parameters: a save that wrote the right effort onto the wrong model is a
 * rule that scores with something the user did not choose.
 */
const OPENAI_MODEL_ID = 'gpt-5-mini';

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
  'Online evaluation — LLM judge reasoning effort',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'a native OpenAI judge rule stores the reasoning effort it displays, flat and once',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
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

        const ruleName = `${testNamespace}-openai-effort`;
        const onlineEval = new OnlineEvaluationPage(page);

        await test.step(`Fill a judge rule on ${REGISTRY_MODEL.openAiReasoning}`, async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openCreateRulePanel();
          await onlineEval.fillCreateRuleLLMJudge({
            name: ruleName,
            template: 'Moderation',
            modelDisplayName: REGISTRY_MODEL.openAiReasoning,
          });
        });

        await test.step('The gear is there, and its one control is the Reasoning effort', async () => {
          // The gear's PRESENCE is half the change: before #9153 this model had
          // nothing a rule could set, so `PromptModelConfigs` returned null and
          // the button did not render at all.
          await expect(
            onlineEval.judgeModelParametersTrigger,
            'the model-parameters gear',
          ).toHaveCount(1);
          await onlineEval.openJudgeModelParameters();

          expect(
            await onlineEval.mountedJudgeParameterIds(),
            'no slider a rule can set for an OpenAI reasoning model — the temperature is ' +
              'resolved away and the penalties are gated off',
          ).toEqual([]);
          await expect(
            onlineEval.judgeParameterLabel('Reasoning effort'),
            'the control is labelled for its provider, not "Thinking effort"',
          ).toHaveCount(1);
        });

        await test.step(`${CHOSEN.label} is offered, is not the default, and can be picked`, async () => {
          const displayed = await onlineEval.readJudgeEffort();
          expect(
            displayed,
            `picking ${CHOSEN.label} has to be a change — if it were already the displayed ` +
              'default, the round trip below would be asserting the default instead',
          ).not.toBe(CHOSEN.label);
          expect(
            await onlineEval.judgeEffortOptions(),
            `this model offers ${CHOSEN.label}`,
          ).toContain(CHOSEN.label);

          await onlineEval.selectJudgeEffort(CHOSEN.label);
          await onlineEval.closeJudgeModelParameters();
        });

        const ruleId = await test.step('Save the rule', async () => {
          const id = await onlineEval.submitPanelAndCaptureRuleId();
          await expect(onlineEval.ruleRow(ruleName), 'the rule is listed').toHaveCount(1);
          return id;
        });

        await test.step('The persisted rule carries a FLAT effort and nothing else', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the rule names the judge model that was picked').toBe(
            OPENAI_MODEL_ID,
          );
          // Equality, not toMatchObject: a nested `reasoning.effort` alongside
          // the flat one is the shape OpenRouter answers 400 for, and the two
          // copies disagreeing is the bug #9153's own collapse logic exists to
          // prevent. Only the flat key may be here for a native OpenAI model
          // carrying no other `reasoning` keys.
          expect(
            model.customParameters,
            'the effort lands under reasoning_effort, in one copy, with nothing added',
          ).toEqual({ reasoning_effort: CHOSEN.value });
        });

        await test.step('…and reopening the rule hydrates the control back to it', async () => {
          // The read-back above proves the value reached storage. This proves
          // the panel can find it there — an effort only the API can see is one
          // a user cannot change back.
          await onlineEval.openEditRulePanelByName(ruleName);
          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.readJudgeEffort(),
            'hydrated from custom_parameters.reasoning_effort, the rule’s only copy',
          ).toBe(CHOSEN.label);
          await onlineEval.closeJudgeModelParameters();
        });

        // No cleanup step: rules do not cascade with their project, and
        // `automationRulesCleanup` discovers and deletes them at teardown,
        // which runs whatever the outcome. A trailing step would be skipped by
        // any failure above — exactly when the workspace is dirtiest.
      },
    );

    test(
      'an OpenRouter judge rule stores the effort NESTED, with no flat copy beside it',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({
        openRouterNativeModels,
        project,
        backendClient,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        test.setTimeout(180_000);
        // `withReasoningEffort`'s OpenRouter half is separate code from its
        // OpenAI one, and OpenRouter answers 400 when a flat `reasoning_effort`
        // disagrees with `reasoning.effort` — so the two shapes regress apart
        // and the absence of the flat copy is as much the assertion as the
        // presence of the nested one.
        expect(
          registryModelIds(openRouterNativeModels.registry, 'openai').has('gpt-5-nano'),
          'the native OpenAI reasoning row behind this id is listed — which is what makes it ' +
            'a reasoning model to this panel at all',
        ).toBe(true);

        const ruleName = `${testNamespace}-openrouter-effort`;
        const onlineEval = new OnlineEvaluationPage(page);

        await test.step(`Fill a judge rule on ${OPEN_ROUTER_MODEL.openAiReasoning}`, async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openCreateRulePanel();
          await onlineEval.fillCreateRuleLLMJudge({
            name: ruleName,
            template: 'Moderation',
            modelDisplayName: OPEN_ROUTER_MODEL.openAiReasoning,
            providerGroup: OPEN_ROUTER_GROUP,
          });
        });

        await test.step(`The gear offers a Reasoning effort, and ${CHOSEN.label} is picked`, async () => {
          await expect(
            onlineEval.judgeModelParametersTrigger,
            'the model-parameters gear',
          ).toHaveCount(1);
          await onlineEval.openJudgeModelParameters();
          await expect(
            onlineEval.judgeParameterLabel('Reasoning effort'),
            'the control is labelled for its provider',
          ).toHaveCount(1);

          const displayed = await onlineEval.readJudgeEffort();
          expect(
            displayed,
            `picking ${CHOSEN.label} has to be a change`,
          ).not.toBe(CHOSEN.label);
          expect(
            await onlineEval.judgeEffortOptions(),
            `this model offers ${CHOSEN.label}`,
          ).toContain(CHOSEN.label);

          await onlineEval.selectJudgeEffort(CHOSEN.label);
          await onlineEval.closeJudgeModelParameters();
        });

        const ruleId = await test.step('Save the rule', async () => {
          const id = await onlineEval.submitPanelAndCaptureRuleId();
          await expect(onlineEval.ruleRow(ruleName), 'the rule is listed').toHaveCount(1);
          return id;
        });

        await test.step('The persisted rule carries reasoning.effort and NO flat copy', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the rule names the OpenRouter-routed model').toBe(
            OPEN_ROUTER_MODEL.openAiReasoning,
          );
          expect(
            model.customParameters,
            'nested only — a flat reasoning_effort beside it is the 400 OpenRouter gives',
          ).toEqual({ reasoning: { effort: CHOSEN.value } });
        });

        await test.step('…and reopening the rule hydrates the control back to it', async () => {
          await onlineEval.openEditRulePanelByName(ruleName);
          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.readJudgeEffort(),
            'hydrated from custom_parameters.reasoning.effort',
          ).toBe(CHOSEN.label);
          await onlineEval.closeJudgeModelParameters();
        });
      },
    );

    test(
      'of two disagreeing stored copies the NESTED one is displayed, and a save leaves exactly one',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({
        modelRegistryProviders,
        project,
        backendClient,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        // The subtle half of #9153, and the one with no unit test on this
        // surface. A rule can arrive holding both copies — written by the SDK,
        // by an older build, or by a hand-made REST call — and they can
        // disagree. The NESTED one is what the model runs at on the Responses
        // API, so that is what the panel must show; and a save must collapse
        // the pair to the one copy, or the rule keeps answering 400.
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry is selectable',
        ).toContain('openai');

        const ruleName = `${testNamespace}-two-copies`;
        const onlineEval = new OnlineEvaluationPage(page);
        const ruleId = await seedEffortRule({
          backendClient,
          project,
          ruleName,
          model: OPENAI_MODEL_ID,
          // Deliberately disagreeing, and deliberately with the LOSING value
          // flat: were both 'low', "the panel reads the nested copy" would be
          // satisfied by a panel reading the flat one.
          customParameters: { reasoning_effort: 'high', reasoning: { effort: SEEDED_NESTED.value } },
        });

        await test.step('The panel reads the nested copy, not the flat one', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          expect(
            await onlineEval.readModelPickerText(),
            'the editor hydrated onto the seeded rule',
          ).toContain(REGISTRY_MODEL.openAiReasoning);
          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.readJudgeEffort(),
            'the nested copy is the level the model would actually run at',
          ).toBe(SEEDED_NESTED.label);
        });

        const update = await test.step('Save the rule after the smallest unrelated edit', async () => {
          // A fresh editor, because the gear is open above and Escape would
          // take the whole form with it. The Enabled switch is the estate's
          // idiom for the smallest edit that is not the field under test — the
          // editor keeps Save disabled until something changes, so a literally
          // unedited save is not a gesture the product offers.
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.toggleEnabled();
          return submitAndCaptureRuleUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH carries one copy, holding the nested value', async () => {
          expect(
            readSentCustomParameters(update),
            'the pair collapsed to the flat slot — the empty `reasoning` object left behind ' +
              'is dropped rather than sent as `{}`',
          ).toEqual({ reasoning_effort: SEEDED_NESTED.value });
        });

        await test.step('And that is what persisted', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the save did not move the model').toBe(OPENAI_MODEL_ID);
          expect(
            model.customParameters,
            'the persisted blob agrees with the payload',
          ).toEqual({ reasoning_effort: SEEDED_NESTED.value });
        });
      },
    );

    test(
      'an effort beside another reasoning key stays nested, and adds no flat copy',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({
        modelRegistryProviders,
        project,
        backendClient,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        // The control that holds the test above apart from this one. There the
        // leftover `reasoning` object was empty, so the effort moved to the
        // flat slot; here it has a SIBLING, so on the Responses API a stored
        // `reasoning` object replaces the one built from `reasoning_effort`
        // wholesale — and an effort written flat would never reach the model.
        // Without this case, "the pair collapses to flat" would read as the
        // whole rule.
        expect(
          modelRegistryProviders.providers,
          'the OpenAI registry is selectable',
        ).toContain('openai');

        const ruleName = `${testNamespace}-nested-sibling`;
        const onlineEval = new OnlineEvaluationPage(page);
        const ruleId = await seedEffortRule({
          backendClient,
          project,
          ruleName,
          model: OPENAI_MODEL_ID,
          customParameters: { reasoning: { effort: SEEDED_NESTED.value, summary: 'auto' } },
        });

        const update = await test.step(`Open the rule and raise the effort to ${RAISED.label}`, async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.readJudgeEffort(),
            'the panel reads the nested effort it was seeded with',
          ).toBe(SEEDED_NESTED.label);
          await onlineEval.selectJudgeEffort(RAISED.label);
          await onlineEval.closeJudgeModelParameters();
          return submitAndCaptureRuleUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH keeps the effort inside `reasoning`, beside its sibling', async () => {
          expect(
            readSentCustomParameters(update),
            'the sibling key survives and no flat copy is added — the nested object is what ' +
              'the Responses API sends',
          ).toEqual({ reasoning: { effort: RAISED.value, summary: 'auto' } });
        });

        await test.step('And that is what persisted', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the save did not move the model').toBe(OPENAI_MODEL_ID);
          expect(
            model.customParameters,
            'the persisted blob agrees with the payload',
          ).toEqual({ reasoning: { effort: RAISED.value, summary: 'auto' } });
        });

        await test.step('Reopened, the panel reads the raised level', async () => {
          // The screen and the stored value have to agree, and here both are
          // NEW: a build that wrote the right payload while leaving the control
          // on the seeded level would pass every assertion above.
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.readJudgeEffort(),
            'the control shows the level the rule now holds',
          ).toBe(RAISED.label);
          await onlineEval.closeJudgeModelParameters();
        });
      },
    );
  },
);

/**
 * Seed a disabled, never-sampled LLM-judge rule holding a free-form block, and
 * assert over REST that it really holds it.
 *
 * The read-back is not ceremony. Every assertion in the two edit tests is about
 * a value the panel READ or CHANGED, and a rule that never carried the block
 * would satisfy "the effort collapsed to one copy" vacuously — and forever,
 * because a capability counted as covered is never re-derived. So the premise
 * is established before the browser opens, where a seeding failure is
 * unmistakable.
 *
 * Disabled AND at 0% sampling: two independent reasons the rule can never reach
 * a provider, so the placeholder key the registry fixture may have written
 * cannot turn into a failed provider call.
 */
async function seedEffortRule(args: {
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
