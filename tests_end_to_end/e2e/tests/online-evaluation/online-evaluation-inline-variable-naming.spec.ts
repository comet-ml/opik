import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * The rule editor panel (opik#8976) has no variable-mapping table. A judge
 * prompt names its fields inline — `{{input.question}}` — and the panel
 * translates on the way out: the stored message gets a generated name
 * (`{{var_1}}`) and the stored `variables` map carries `var_1 -> input.question`.
 * Hydration runs the translation backwards, so the author sees the path again.
 *
 * Two surfaces, and they disagree BY DESIGN, which is why neither one alone can
 * see a regression here. An API-only assertion cannot tell a correct
 * `{{var_1}}` from a prompt the editor will hydrate wrongly; a UI-only one
 * cannot tell a correctly-hydrated path from a mapping the scoring engine
 * cannot resolve. If the rename and the reverse hydration drift apart, the rule
 * saves with a mapping that resolves to nothing and the judge scores off an
 * empty prompt — while the editor still shows the path the user typed.
 *
 * Every existing spec in this area reaches the editor through
 * `applyJudgeTemplate`, whose templates map `{{trace}}` and never take an
 * inline path, so none of them exercises this at all.
 *
 * Deterministic and provider-free: the rules never score, and the model is
 * selected only because the form requires one.
 */

/** What the author types, and what the editor must show them again. */
const PROMPT_IN_EDITOR = 'Grade the answer.\n\nQUESTION: {{input.question}}';

/** What the server must end up holding for it. */
const PROMPT_STORED = 'Grade the answer.\n\nQUESTION: {{var_1}}';

/**
 * A prompt that already owns the name the generator would reach for first.
 *
 * `var_1` here is a USER's variable name, mapped to the reserved `spans` —
 * which is not an entity path, so it is never inlined and keeps its name
 * across a save. The inline path beside it therefore has to skip to `var_2`.
 * A generator that counted from one without looking would collide, and the
 * collision is silent: one of the two mappings wins and the other field stops
 * reaching the judge.
 */
const COLLISION_PROMPT_STORED = 'Use {{var_1}} and also {{input.question}}';
const COLLISION_PROMPT_AFTER_SAVE = 'Use {{var_1}} and also {{var_2}}';

test.describe(
  'Online Evaluation — inline judge variables',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'An inline field path is stored as var_1, hydrates back as the path, and survives a re-save',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
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

        const ruleName = `${testNamespace}-inline-path`;
        const onlineEval = new OnlineEvaluationPage(page);

        const ruleId = await test.step('Create a judge rule whose prompt names a field path inline', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openCreateRulePanel('LLM-as-a-Judge');
          await onlineEval.nameInput.fill(ruleName);
          await onlineEval.selectJudgeModel(REGISTRY_MODEL.openAiStandard);
          await onlineEval.replacePromptText(PROMPT_IN_EDITOR);
          expect(
            await onlineEval.readPromptText(),
            'the editor took the prompt as typed',
          ).toBe(PROMPT_IN_EDITOR);
          return onlineEval.submitPanelAndCaptureRuleId();
        });

        await test.step('The server holds a generated name, mapped to the path', async () => {
          const messages = await backendClient.getLlmJudgeMessages(ruleId);
          expect(messages, 'the rule has exactly the one prompt message').toHaveLength(1);
          expect(messages[0].role, 'a judge prompt is a USER message').toBe('USER');
          expect(
            messages[0].content,
            'the inline path is renamed in the stored prompt',
          ).toBe(PROMPT_STORED);

          const body = await backendClient.getLlmJudgeRuleBody(ruleId);
          // Compared whole, not with a `find`: an extra mapping is exactly the
          // bug a lookup of the one expected key would pass straight over, and
          // an unresolvable extra is what stops the judge seeing the field.
          expect(
            body.code.variables,
            'and the mapping the engine resolves it through is the only one',
          ).toEqual({ var_1: 'input.question' });
        });

        await test.step('Reopening shows the author the path again, not the generated name', async () => {
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await onlineEval.openEditRulePanelByName(ruleName);
          expect(
            await onlineEval.readPromptText(),
            'hydration runs the rename backwards',
          ).toBe(PROMPT_IN_EDITOR);
        });

        await test.step('A re-save does not rename it again', async () => {
          // The round trip is where a one-way rename shows up: each save would
          // mint a fresh name, so the prompt drifts to var_2, var_3, … and the
          // stored map grows a dead entry every time the user touches anything.
          await onlineEval.toggleEnabled();
          await onlineEval.submitPanel();

          const messages = await backendClient.getLlmJudgeMessages(ruleId);
          expect(messages, 'still the one message').toHaveLength(1);
          expect(messages[0].content, 'the stored prompt is unchanged').toBe(PROMPT_STORED);

          const body = await backendClient.getLlmJudgeRuleBody(ruleId);
          expect(body.code.variables, 'and so is the mapping').toEqual({
            var_1: 'input.question',
          });
          expect(body.enabled, 'the one edit that was made did land').toBe(false);
        });
      },
    );

    test(
      'An inline path takes the next free name when the author already owns var_1',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
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

        const ruleName = `${testNamespace}-name-collision`;
        const onlineEval = new OnlineEvaluationPage(page);

        // Seeded over REST because the editor cannot produce this starting
        // state: it names inline paths itself, so a rule where `var_1` is the
        // AUTHOR's own name for an unrelated mapping has to be written directly.
        const ruleId = await test.step('Seed a rule that already uses var_1 for its own mapping', async () =>
          backendClient.createLlmJudgeRule({
            projectId: project.id,
            name: ruleName,
            messages: [{ role: 'USER', content: COLLISION_PROMPT_STORED }],
            variables: { var_1: 'spans', 'input.question': 'input.question' },
          }));

        await test.step('The seed really holds both mappings', async () => {
          // Asserted before the browser opens: a save over a rule that never
          // had the collision would pass having proved nothing, and would read
          // as coverage forever.
          const body = await backendClient.getLlmJudgeRuleBody(ruleId);
          expect(body.code.variables, 'the author owns var_1, and the path is mapped too').toEqual({
            var_1: 'spans',
            'input.question': 'input.question',
          });
        });

        await test.step('The editor inlines only the path, leaving the named mapping alone', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openEditRulePanelByName(ruleName);
          expect(
            await onlineEval.readPromptText(),
            'a reserved mapping is not an entity path, so it keeps the name the author gave it',
          ).toBe(COLLISION_PROMPT_STORED);
        });

        await test.step('Saving moves the inline path to var_2 and leaves var_1 to its owner', async () => {
          await onlineEval.toggleEnabled();
          await onlineEval.submitPanel();

          const messages = await backendClient.getLlmJudgeMessages(ruleId);
          expect(messages, 'still the one message').toHaveLength(1);
          expect(messages[0].content, 'the generated name skips the one in use').toBe(
            COLLISION_PROMPT_AFTER_SAVE,
          );

          const body = await backendClient.getLlmJudgeRuleBody(ruleId);
          expect(
            body.code.variables,
            'both mappings survive, each under its own name',
          ).toEqual({ var_1: 'spans', var_2: 'input.question' });
        });
      },
    );
  },
);
