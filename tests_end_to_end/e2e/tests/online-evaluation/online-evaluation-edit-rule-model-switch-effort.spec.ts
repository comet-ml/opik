import type { Page, Request } from '@playwright/test';
import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import type {
  BackendClient,
  JudgeMessageWrite,
  JudgeOutputSchemaType,
  ProjectRef,
} from '@e2e/core/backend';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * Switching an LLM-judge rule's MODEL in the rule editor panel must take the previous
 * model's Anthropic thinking effort with it (OPIK-8605, opik#8822).
 *
 * The effort is stored in `code.model.custom_parameters.output_config.effort`,
 * because that is the only free-form slot the request shape captures. It is an
 * Anthropic-only parameter, and the levels a model accepts differ per model.
 * Before this change the stored copy outlived the model it belonged to: a rule
 * switched off `claude-sonnet-5` onto an OpenAI model kept asking for an
 * `effort` the new provider does not take, and a rule moved onto a Claude model
 * with a narrower list kept a level that model answers 400 for. Nothing on the
 * rule row changes, so the failure is silent until the judge next runs.
 *
 * `online-evaluation-edit-rule-preserves-model-parameters.spec.ts` is the other
 * half of this contract and the two must be read together: it pins that an
 * UNEDITED save keeps every free-form key, this one pins what a MODEL SWITCH
 * does to them. The fourth test below is the control that holds the pair apart
 * — without it, every assertion here would pass just as well on a build that
 * dropped the effort on every save.
 *
 * ## A switch WITHIN Anthropic and a switch ACROSS providers differ
 *
 * Only the within-provider switch is the effort-only strip opik#8822 is about:
 * the effort goes and `output_config.format` and `unrelated_marker` stay.
 * A switch to another PROVIDER clears `custom_parameters` wholesale, bystanders
 * included — `updateConfigForModelChange` does it on purpose, since the extra
 * body belongs to the provider that is being left, and `schema.effort.test.ts`
 * pins it as `toBeUndefined()`. The two cases are therefore asserted against
 * different expectations, and conflating them is how this spec was first
 * written: the cross-provider test expected the within-provider shape.
 *
 * ## Both surfaces, deliberately
 *
 * `thinkingEffort` is NOT in `RULE_UNSUPPORTED_PARAMS` — `modelConfigParams.ts`
 * keeps it precisely because it is saved inside `custom_parameters` — so the
 * rule editor panel really does render a Thinking effort dropdown, and the screen can
 * disagree with what gets written. Asserting the PATCH body alone would miss a
 * dropdown showing a level the rule no longer holds, and asserting the screen
 * alone would miss the payload. So each test reads both, and the persisted blob
 * as well, which is what separates "the frontend dropped it" from "the backend
 * did".
 *
 * ## Deterministic and LLM-free by construction
 *
 * Every rule is seeded over REST — so a create-path regression cannot be
 * mistaken for an edit-path one — DISABLED and at 0% sampling, and the fixture
 * project holds no traces, so no judge can ever execute. The `modelRegistryProviders`
 * fixture only has to make the picker OFFER these models; a placeholder key is
 * enough for that, and nothing is ever sent to a provider.
 *
 * ## Why each test opens the page fresh instead of reusing an editor panel
 *
 * The Thinking effort dropdown lives inside the model-parameters gear, which is
 * a `DropdownMenu` over the rule editor panel: while it is open the Update button cannot be
 * clicked, and dismissing it is a race — the close is animated, so an Escape
 * aimed at the dropdown lands on the DIALOG instead and discards the form. Each
 * phase therefore reloads, the same choice and for the same reason as
 * `online-evaluation-llm-judge-model-parameters.spec.ts`.
 */

/**
 * The id the rules are seeded on. Its capabilities row lists every Anthropic
 * level, `xhigh` included, which is what makes the seeded `xhigh` a LEGAL value
 * for this model rather than junk — that is the whole premise, since the
 * behaviour under test is a legal value becoming illegal on a switch.
 */
const SEEDED_MODEL_ID = 'claude-sonnet-5';

/**
 * How the picker labels `SEEDED_MODEL_ID`, used to assert the rule editor panel really
 * hydrated onto the seeded rule before anything is switched.
 *
 * Taken from `REGISTRY_MODEL` rather than written out, because that is also
 * what registers the name as unbilled with `llm-model-policy` — the guard the
 * POM runs before touching a picker. The entry is named for the SAMPLING axis;
 * this spec picked it for the effort axis, hence the re-bind.
 */
const SEEDED_MODEL_NAME = REGISTRY_MODEL.claudeWithoutSampling;

/** A non-Anthropic model: the new provider has no `effort` parameter at all. */
const OPENAI_TARGET = REGISTRY_MODEL.openAiStandard;

/**
 * A Claude model whose capabilities row offers low/medium/high/max and NOT
 * `xhigh`, so the stale level has to be refitted rather than dropped.
 *
 * Re-bound from the sampling-named entry for the same reason as
 * `SEEDED_MODEL_NAME`.
 */
const NARROWER_EFFORT_TARGET = REGISTRY_MODEL.claudeWithSampling;

/** A Claude model with a row that lists no effort options at all. */
const NO_EFFORT_TARGET = REGISTRY_MODEL.claudeWithoutEffort;

/** The level the refit lands on: `getDefaultThinkingEffort`'s fallback. */
const REFITTED_EFFORT = 'high';

/**
 * The seeded free-form block, and the three shapes a save can legally produce.
 *
 * Three keys, all asserted, because "the effort went" is satisfied by far too
 * much:
 *   - `output_config.effort` is the value under test;
 *   - `output_config.format` is its SIBLING, so a save that rebuilt
 *     `output_config` from the effort alone — or dropped the whole nested
 *     object to be rid of one key — fails here rather than reading as a clean
 *     strip;
 *   - `unrelated_marker` is a top-level key no editor control has ever heard
 *     of, which catches a serializer that discards everything it cannot name.
 */
const SEEDED_CUSTOM_PARAMETERS = {
  output_config: { effort: 'xhigh', format: 'x' },
  unrelated_marker: 'keep-me',
} as const;

/** The effort gone, both bystander keys intact. */
const EFFORT_DROPPED = {
  output_config: { format: 'x' },
  unrelated_marker: 'keep-me',
} as const;

/** The effort refitted to a level the new model accepts, bystanders intact. */
const EFFORT_REFITTED = {
  output_config: { format: 'x', effort: REFITTED_EFFORT },
  unrelated_marker: 'keep-me',
} as const;

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
  'Online Evaluation — edit rule, model switch and the stored Anthropic effort',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'Switching onto another provider drops the whole free-form block, the effort with it',
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
          'both registries this test switches between are selectable',
        ).toEqual(expect.arrayContaining(['anthropic', 'openai']));

        const ruleName = `${testNamespace}-to-openai`;
        const onlineEval = new OnlineEvaluationPage(page);
        const ruleId = await seedEffortRule({ backendClient, project, ruleName });

        await test.step('The editor shows the stored effort before anything is switched', async () => {
          // The screen half of the premise. Read before the switch rather than
          // only after, so "the dropdown is gone" below cannot be satisfied by a
          // panel that never rendered the control in the first place.
          await openEditPanelOn(onlineEval, project.id, ruleName);
          expect(
            await onlineEval.readModelPickerText(),
            'the rule editor panel hydrated onto the seeded rule',
          ).toContain(SEEDED_MODEL_NAME);
          await onlineEval.openJudgeModelParameters();
          await expect(
            onlineEval.judgeThinkingEffortSelect,
            'the panel reads the level the rule actually holds',
          ).toHaveText('xHigh');
        });

        const update = await test.step(`Switch the model to ${OPENAI_TARGET} and save`, async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.selectJudgeModel(OPENAI_TARGET);
          return submitAndCaptureUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH carries no free-form block at all', async () => {
          // A PROVIDER change clears `custom_parameters` wholesale — it is not
          // the effort-only strip the within-Anthropic tests below assert.
          // `updateConfigForModelChange` does it deliberately ("the extra body
          // belongs to the previous provider"), and the shipped unit test
          // `schema.effort.test.ts` → "drops it on a switch to OpenRouter"
          // pins the same thing as `toBeUndefined()`.
          //
          // So the bystander keys do NOT survive here, and this test must not
          // claim they do: an earlier version of it expected EFFORT_DROPPED,
          // which is the within-provider shape. That expectation passed nowhere
          // and would have had to be weakened to go green — the behaviour it
          // described has a unit test saying the opposite.
          expect(
            sentCustomParameters(update),
            'a cross-provider switch sends no custom_parameters block, so the next provider is not handed the previous one\'s extra body',
          ).toBeUndefined();
        });

        await test.step('And that is what persisted, on the new model', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the rule moved onto the OpenAI model').toBe('gpt-4o-mini');
          // Null and absent are the same answer for "the rule holds no
          // free-form parameters", and the backend may store either.
          expect(
            model.customParameters ?? null,
            'nothing of the previous provider\'s block survived the save',
          ).toBeNull();
        });

        await test.step('The rule is still listed, still disabled, still at 0%', async () => {
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await expect(onlineEval.ruleStatusCell(ruleName, 'Disabled')).toBeVisible();
          await expect(onlineEval.ruleSamplingRateCell(ruleName, '0%')).toBeVisible();
        });
      },
    );

    test(
      'Switching onto a Claude model that takes no effort drops it, and the control goes with it',
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
          'the Anthropic registry is selectable',
        ).toContain('anthropic');

        const ruleName = `${testNamespace}-to-no-effort`;
        const onlineEval = new OnlineEvaluationPage(page);
        const ruleId = await seedEffortRule({ backendClient, project, ruleName });

        const update = await test.step(`Switch the model to ${NO_EFFORT_TARGET} and save`, async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          expect(
            await onlineEval.readModelPickerText(),
            'the rule editor panel hydrated onto the seeded rule',
          ).toContain(SEEDED_MODEL_NAME);
          await onlineEval.selectJudgeModel(NO_EFFORT_TARGET);
          return submitAndCaptureUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH carries the block without the effort', async () => {
          // Still a drop and not a refit: the target's capabilities row exists
          // but offers no levels, so there is nothing to refit TO.
          expect(
            readSentCustomParameters(update),
            'a target with no effort levels leaves no effort behind',
          ).toEqual(EFFORT_DROPPED);
        });

        await test.step('And that is what persisted, on the new model', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the rule moved onto the Haiku model').toBe(
            'claude-haiku-4-5-20251001',
          );
          expect(
            model.customParameters,
            'the persisted blob agrees with the payload',
          ).toEqual(EFFORT_DROPPED);
        });

        await test.step('Reopened, the panel offers no Thinking effort at all', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.openJudgeModelParameters();
          // Temperature first: this target DOES have a tunable parameter, so a
          // panel holding it is proof the panel opened and rendered. Without
          // that, "no Thinking effort" would also be true of a gear that failed
          // to open, which is the way this assertion would rot into a pass.
          expect(
            await onlineEval.mountedJudgeParameterIds(),
            'the panel is open and holds this model\'s one tunable parameter',
          ).toEqual(['temperature']);
          await expect(
            onlineEval.judgeThinkingEffortSelect,
            'no effort control for a model whose row lists no levels',
          ).toHaveCount(0);
        });
      },
    );

    test(
      'Switching onto a Claude model with a narrower list refits the stale effort',
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
          'the Anthropic registry is selectable',
        ).toContain('anthropic');

        const ruleName = `${testNamespace}-to-narrower`;
        const onlineEval = new OnlineEvaluationPage(page);
        const ruleId = await seedEffortRule({ backendClient, project, ruleName });

        const update = await test.step(`Switch the model to ${NARROWER_EFFORT_TARGET} and save`, async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          expect(
            await onlineEval.readModelPickerText(),
            'the rule editor panel hydrated onto the seeded rule',
          ).toContain(SEEDED_MODEL_NAME);
          await onlineEval.selectJudgeModel(NARROWER_EFFORT_TARGET);
          return submitAndCaptureUpdate(page, onlineEval, ruleId);
        });

        await test.step(`The outbound PATCH carries effort: ${REFITTED_EFFORT}`, async () => {
          // A refit and not a drop, which is the distinction this test exists
          // for: the target does offer levels, just not the stored one, so the
          // rule keeps an effort — a legal one.
          expect(
            readSentCustomParameters(update),
            'the stale level was refitted, not discarded, and the bystanders survived',
          ).toEqual(EFFORT_REFITTED);
        });

        await test.step('And that is what persisted, on the new model', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the rule moved onto the Sonnet 4.6 model').toBe(
            'claude-sonnet-4-6',
          );
          expect(
            model.customParameters,
            'the persisted blob agrees with the payload',
          ).toEqual(EFFORT_REFITTED);
        });

        await test.step('Reopened, the panel reads the refitted level', async () => {
          // The screen and the stored value have to agree, and here they are
          // both NEW: a build that refitted the payload while leaving the
          // dropdown on the stale level would pass every assertion above.
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.openJudgeModelParameters();
          await expect(
            onlineEval.judgeThinkingEffortSelect,
            'the dropdown shows the level the rule now holds',
          ).toHaveText('High');
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
        // The load-bearing test of the file. The three above show an effort
        // disappearing; on their own they would be satisfied by a build that
        // dropped it on EVERY save — including the ordinary open-and-save that
        // is the most common gesture on this page, and the one
        // online-evaluation-edit-rule-preserves-model-parameters.spec.ts exists
        // to protect. This is what makes them evidence about the switch.
        expect(
          modelRegistryProviders.providers,
          'the Anthropic registry is selectable',
        ).toContain('anthropic');

        const ruleName = `${testNamespace}-unswitched`;
        const onlineEval = new OnlineEvaluationPage(page);
        const ruleId = await seedEffortRule({ backendClient, project, ruleName });

        const update = await test.step('Open the rule and save it without switching the model', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          expect(
            await onlineEval.readModelPickerText(),
            'the rule editor panel hydrated onto the seeded rule',
          ).toContain(SEEDED_MODEL_NAME);
          // The editor keeps Save disabled until something changes, so a
          // literally unedited save is no longer a gesture the product offers.
          // The Enabled switch is the estate's idiom for the smallest edit that
          // is not the field under test, and it keeps this control's role
          // intact: what has to be shown is that an edit which is NOT a model
          // switch leaves the effort alone, which is exactly what this is.
          await onlineEval.toggleEnabled();
          return submitAndCaptureUpdate(page, onlineEval, ruleId);
        });

        await test.step('The outbound PATCH sends the block back unchanged', async () => {
          expect(
            readSentCustomParameters(update),
            'no switch, no strip',
          ).toEqual(SEEDED_CUSTOM_PARAMETERS);
        });

        await test.step('And the rule still holds it, on the model it started on', async () => {
          const model = await backendClient.getLlmJudgeModel(ruleId);
          expect(model.name, 'the save did not move the model').toBe(SEEDED_MODEL_ID);
          expect(
            model.customParameters,
            'the effort survives a save that changed nothing',
          ).toEqual(SEEDED_CUSTOM_PARAMETERS);
        });

        await test.step('Reopened, the panel still reads the stored level', async () => {
          await openEditPanelOn(onlineEval, project.id, ruleName);
          await onlineEval.openJudgeModelParameters();
          await expect(
            onlineEval.judgeThinkingEffortSelect,
            'the dropdown still shows xHigh',
          ).toHaveText('xHigh');
        });
      },
    );
  },
);

/**
 * Seed a disabled, never-sampled LLM-judge rule holding the effort block, and
 * assert over REST that it really holds it.
 *
 * The read-back is not ceremony. Every assertion in this file is about a value
 * CHANGING, and a rule that never carried the block would satisfy "the effort
 * is gone" perfectly — vacuously, and forever, because a capability counted as
 * covered is never re-derived. So the premise is established before the browser
 * opens, where a seeding failure is unmistakable.
 */
async function seedEffortRule(args: {
  backendClient: BackendClient;
  project: ProjectRef;
  ruleName: string;
}): Promise<string> {
  const { backendClient, project, ruleName } = args;
  return test.step(`seed rule "${ruleName}" on ${SEEDED_MODEL_ID} holding effort: xhigh`, async () => {
    const ruleId = await backendClient.createLlmJudgeRule({
      projectId: project.id,
      name: ruleName,
      // Disabled AND at 0% sampling: two independent reasons this rule can
      // never reach a provider, so the placeholder keys the fixture may have
      // written cannot turn into a failed provider call.
      enabled: false,
      samplingRate: 0,
      model: SEEDED_MODEL_ID,
      customParameters: SEEDED_CUSTOM_PARAMETERS,
      messages: JUDGE_MESSAGES,
      variables: JUDGE_VARIABLES,
      schema: JUDGE_SCHEMA,
    });

    const seeded = await backendClient.getLlmJudgeModel(ruleId);
    expect(seeded.name, 'the seeded rule names the Anthropic judge model').toBe(SEEDED_MODEL_ID);
    expect(
      seeded.customParameters,
      'custom_parameters as seeded, before any UI interaction',
    ).toEqual(SEEDED_CUSTOM_PARAMETERS);
    return ruleId;
  });
}

/** Reload the Online Evaluation page and open one rule's rule editor panel. */
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

/**
 * Press "Save changes" and return the PATCH request the rule editor panel sent.
 *
 * Both the request and the response are awaited. `the rule editor panel` calls
 * `setOpen(false)` outside the mutation's callbacks, so the rule editor panel hides the
 * moment the PATCH is dispatched — waiting only for it to close would leave the
 * persisted read below free to observe the PRE-save blob and pass on a backend
 * that threw the update away.
 */
async function submitAndCaptureUpdate(
  page: Page,
  onlineEval: OnlineEvaluationPage,
  ruleId: string,
): Promise<Request> {
  // Armed before the click: the request is in flight the instant the rule editor panel
  // submits, so subscribing afterwards would race it.
  const patched = page.waitForRequest(
    (request) => request.method() === 'PATCH' && isRuleUpdate(request.url(), ruleId),
  );
  const answered = page.waitForResponse(
    (response) =>
      response.request().method() === 'PATCH' && isRuleUpdate(response.url(), ruleId),
  );
  await onlineEval.submitPanel();
  const request = await patched;
  const response = await answered;
  expect(response.ok(), `the save was accepted (got ${response.status()})`).toBe(true);
  return request;
}

/**
 * The `code.model.custom_parameters` the form serialized into the PATCH.
 *
 * Read from the outbound body rather than only from the persisted rule because
 * the strip happens in the frontend — the backend only ever sees the result —
 * so this is the reading that says WHERE a wrong value came from. Asserted
 * present rather than defaulted: a payload carrying no block at all is a
 * different failure from one carrying an emptied block, and `?? {}` would hide
 * the difference.
 */
function readSentCustomParameters(request: Request): Record<string, unknown> {
  const sent = sentCustomParameters(request);
  expect(sent, 'the update payload carries a model custom_parameters block').toBeDefined();
  return sent as Record<string, unknown>;
}

/**
 * The same read, without requiring the block to be there.
 *
 * Needed by the cross-provider case, whose whole expectation is that no block
 * is sent at all — `readSentCustomParameters` cannot express that, since
 * asserting absence through a helper that asserts presence is a contradiction.
 */
function sentCustomParameters(request: Request): Record<string, unknown> | undefined {
  const body = request.postDataJSON() as {
    code?: { model?: { custom_parameters?: Record<string, unknown> } };
  };
  return body.code?.model?.custom_parameters;
}
