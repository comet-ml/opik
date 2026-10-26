import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * An LLM-judge rule must persist the Anthropic thinking effort the rule editor panel
 * displays, and must not offer a control it cannot keep (opik#8880).
 *
 * The effort is the ONE provider setting this surface can store beyond a
 * temperature, and it is stored in a place nothing on the rule list shows:
 * `code.model.custom_parameters.output_config.effort`, the slot the judge
 * actually reads. So an effort lost on save is invisible — the rule editor panel closes,
 * the row looks right, and the judge simply never thinks. That is the silent
 * wrongness worth pinning.
 *
 * Both surfaces, because they can regress apart:
 *
 *   - the DIALOG decides which controls to mount and what to say about the one
 *     it takes away;
 *   - the SAVE path (`convertLLMJudgeDataToLLMJudgeObject`) decides what lands
 *     in `custom_parameters`.
 *
 * A editor showing Low over a save that stored nothing looks entirely correct,
 * and an API-only assertion would pass over an editor panel that could never set the
 * value in the first place.
 *
 * ## Why this is not already covered
 *
 * `online-evaluation-llm-judge-model-parameters.spec.ts` sits on the same
 * capability but asks a different question: which controls the gear shows at
 * the model's default. It never changes one and never saves, so nothing there
 * reaches the persisted blob.
 *
 * ## Why the note's exact wording is asserted
 *
 * `RULE_UNSUPPORTED_PARAMS` strips `topP` on this surface, because a rule has
 * nowhere to keep one. So the temperature is the only thing a level can take
 * away here, and the panel must render the temperature-only sentence rather
 * than the playground's two-parameter one — which would tell the user to go and
 * set a Top P control that does not exist on this form.
 *
 * Deterministic and provider-free: the rule is created and read back but never
 * executed, so no judge ever runs and nothing is billed.
 */

/** The level this spec picks, and the value the rule must store. */
const CHOSEN_EFFORT = { label: 'Low', value: 'low' } as const;

/** Every level the rule form offers for this model, in order. */
const OFFERED_EFFORTS = ['Off', 'Low', 'Medium', 'High', 'Max'] as const;

test.describe(
  'Online evaluation — LLM judge thinking effort',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'a rule stores the thinking effort it was given, and hides the temperature it cannot offer at a level',
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
          'the Anthropic registry is selectable',
        ).toContain('anthropic');

        const ruleName = `${testNamespace}-effort`;
        const onlineEval = new OnlineEvaluationPage(page);

        await test.step('Open Online evaluation on an empty project and fill a judge rule', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openCreateRulePanel();
          // Filled before the parameters are touched because picking the
          // template rebuilds the prompt and variable-mapping section; the
          // model has to be chosen before its parameters panel exists at all.
          await onlineEval.fillCreateRuleLLMJudge({
            name: ruleName,
            template: 'Moderation',
            modelDisplayName: REGISTRY_MODEL.claudeWithSampling,
          });
        });

        await test.step('At its default the panel offers the temperature and nothing else', async () => {
          await onlineEval.openJudgeModelParameters();

          expect(
            await onlineEval.mountedJudgeParameterIds(),
            'a rule can set a temperature and no other slider — topP, the token cap and the two ' +
              'runner controls are all stripped on this surface',
          ).toEqual(['temperature']);
          await expect(
            onlineEval.judgeParameterLabel('Thinking effort'),
            'and the effort dropdown beside it, labelled for a model whose effort turns thinking on',
          ).toHaveCount(1);
          expect(
            await onlineEval.readJudgeEffort(),
            'Off is the default, so creating a rule does not start the judge thinking',
          ).toBe('Off');
          expect(
            await onlineEval.judgeEffortOptions(),
            'the rule form offers the same levels as the playground — the effort is not among the ' +
              'parameters this surface strips',
          ).toEqual([...OFFERED_EFFORTS]);
          await expect(
            onlineEval.judgeSamplingOffNote,
            'nothing is off while the effort is Off, so there is no note',
          ).toHaveCount(0);
        });

        await test.step(`Setting it to ${CHOSEN_EFFORT.label} leaves the panel with no control at all`, async () => {
          await onlineEval.selectJudgeEffort(CHOSEN_EFFORT.label);

          expect(
            await onlineEval.mountedJudgeParameterIds(),
            'the temperature is unmounted, not dimmed — Anthropic refuses one while Claude thinks',
          ).toEqual([]);
          // The exact sentence. This surface can store no Top P, so naming one
          // would send the user looking for a control that is not on the form.
          await expect(
            onlineEval.judgeSamplingOffNote,
            'the note names the temperature alone, not the playground’s pair',
          ).toHaveText(
            'Temperature is off while Claude can think. Set Thinking effort to Off to use it.',
          );

          await onlineEval.closeJudgeModelParameters();
        });

        await test.step('Save the rule', async () => {
          await onlineEval.submitPanel();
          await expect(onlineEval.ruleRow(ruleName), 'the rule is listed').toHaveCount(1);
        });

        await test.step('The persisted rule carries the effort in the slot the judge reads', async () => {
          const rules = await backendClient.listAutomationRulesForProject(project.id);
          const created = rules.filter((rule) => rule.name === ruleName);
          // The whole project's rules, not a `find`: a save that somehow wrote
          // two rules would otherwise pass on whichever one came first.
          expect(created, `exactly one rule named ${ruleName}`).toHaveLength(1);

          const model = await backendClient.getLlmJudgeModel(created[0].id);
          expect(model.name, 'the rule names the judge model that was picked').toBe(
            'claude-sonnet-4-6',
          );
          // Equality, not toMatchObject: a flat `thinkingEffort` alongside the
          // nested one is the shape the backend proxy drops without a trace,
          // and `output_config` is the only key the form may write here.
          expect(
            model.customParameters,
            'the effort lands under output_config.effort and nothing else is added',
          ).toEqual({ output_config: { effort: CHOSEN_EFFORT.value } });
          // Deliberate, and the one place this surface's screen and its storage
          // legitimately disagree: the rule keeps the temperature so it comes
          // back when the effort returns to Off, and the backend declines to
          // send it for as long as thinking is on
          // (`AnthropicClientGenerator` applies it only when thinking is off).
          expect(
            model.temperature,
            'the hidden temperature is kept in the rule rather than discarded',
          ).toBe(0);
        });

        await test.step('…and reopening the rule hydrates the control back to it', async () => {
          // The read-back above proves the value reached storage. This proves
          // the rule editor panel can find it there — a rule whose effort only the API can
          // see is one a user cannot change back.
          await onlineEval.openEditRulePanelByName(ruleName);
          await onlineEval.openJudgeModelParameters();
          expect(
            await onlineEval.readJudgeEffort(),
            'hydrated from custom_parameters.output_config.effort, the rule’s only copy',
          ).toBe(CHOSEN_EFFORT.label);
          expect(
            await onlineEval.mountedJudgeParameterIds(),
            'and the panel comes back in the state the level implies',
          ).toEqual([]);
          await onlineEval.closeJudgeModelParameters();
        });

        // No cleanup step here on purpose. Rules do not cascade with their
        // project, so one has to be deleted — and `automationRulesCleanup`
        // discovers and deletes it at teardown, which runs whatever the
        // outcome. A trailing cleanup step would be skipped by any failure
        // above, which is exactly when the workspace is dirtiest.
      },
    );
  },
);
