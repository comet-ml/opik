import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/** The two Decisions-API models `DECISION_MODELS` declares, as the editor labels them. */
const JEV_LATEST = '~typesafe/jev-latest';
const JEV_PINNED = 'typesafe/jev-1.13';

/**
 * An OpenRouter chat model, the control for "the LLM-as-a-Judge picker is not
 * simply empty". Nothing here ever calls it.
 */
const CHAT_MODEL = 'openai/gpt-4o-mini';

/**
 * The built-in provider a Jev model resolves to. Workspace-global and unnamed,
 * so the fixture reuses an existing key rather than seeding over it.
 */
const OPENROUTER = 'openrouter';

const QUESTION = 'Does the output fully answer the input?';

/**
 * Jev (TypeSafe Decisions API models, served through OpenRouter) as its own
 * evaluator type in the online evaluation editor (opik#8540, OPIK-8510).
 *
 * Decision models are kept out of the model registry and out of the
 * LLM-as-a-Judge model picker. They get their own "Decision model" evaluator,
 * with a model select, one prompt and a list of yes/no questions, and no
 * thread scope because a thread rule cannot use one.
 *
 * Both failure modes are silent. A Jev model offered where it cannot run (thread
 * scope, or the chat-model picker) lets a user build a rule that only fails on
 * submit with a 400; a Jev rule that does not round-trip as a decisions-model
 * rule quietly turns into a chat judge.
 *
 * Deterministic and free: the rules are created but never scored, so no
 * provider call is made. The OpenRouter key exists only so the provider
 * resolves; its secret is never used.
 */
test.describe(
  'Online Evaluation — Jev decision models',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'a Jev rule saves, persists as a decisions-model rule, and reopens as one',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, backendClient, providerKeys, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-jev-rule`;
        const rules = new OnlineEvaluationPage(page);

        await test.step('Make sure the workspace has an OpenRouter key', async () => {
          await providerKeys.ensureBuiltIn(OPENROUTER, [JEV_LATEST, JEV_PINNED]);
        });

        await test.step('Build a decision model rule on the pinned Jev model', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanel('Decision model');
          await expect(rules.decisionModelSelect, 'a new rule starts on the latest Jev').toHaveText(
            JEV_LATEST,
          );
          await rules.nameInput.fill(ruleName);
          await rules.chooseDecisionModel(JEV_PINNED);
          await rules.mainQuestionInput.fill(QUESTION);
        });

        await test.step('It submits with no rejection', async () => {
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'POST' &&
              /\/v1\/private\/automations\/evaluators\/?$/.test(new URL(response.url()).pathname),
          );
          await rules.submitPanel();
          const response = await answered;
          // `DecisionModelRuleValidator` answers 400 to a payload built wrongly.
          expect(
            response.status(),
            `creating a Jev rule answered ${response.status()}: ${await response.text()}`,
          ).toBe(201);
        });

        const ruleId = await test.step('It persisted as a decisions-model rule', async () => {
          const listed = await backendClient.listAutomationRulesForProject(project.id);
          const match = listed.filter((rule) => rule.name === ruleName);
          expect(match, `rules named "${ruleName}"`).toHaveLength(1);
          const id = match[0].id;

          expect(
            (await backendClient.getLlmJudgeModel(id)).name,
            'the persisted model name',
          ).toBe(JEV_PINNED);

          // A decisions model takes a single user turn; a stray system message
          // is exactly what `DecisionModelRuleValidator` refuses.
          const messages = await backendClient.getLlmJudgeMessages(id);
          expect(
            messages.map((m) => m.role.toUpperCase()),
            'the persisted judge messages',
          ).toEqual(['USER']);

          const types = await backendClient.getLlmJudgeScoreTypes(id);
          expect(
            [...new Set(types)],
            'a decisions model answers yes/no, so every score must be BOOLEAN',
          ).toEqual(['BOOLEAN']);
          return id;
        });

        await test.step('Reopening it shows the decision model editor, without the chat-only controls', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openEditRulePanelByName(ruleName);

          await expect(rules.decisionModelSelect, 'the persisted Jev model').toHaveText(JEV_PINNED);
          await expect(rules.mainQuestionInput, 'the persisted question').toHaveValue(QUESTION);
          // Absent, not disabled: a decisions model has no chat model, no
          // sampling parameters and no per-evaluation spend.
          await expect(rules.modelPicker, 'no chat-model picker').toHaveCount(0);
          await expect(
            rules.judgeModelParametersTrigger,
            'no model-parameters gear for a decisions model',
          ).toHaveCount(0);
          await expect(rules.costLimitLabel, 'no cost limit for a decisions model').toHaveCount(0);
        });

        await test.step('Switching to the latest Jev re-saves and persists', async () => {
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'PATCH' &&
              new URL(response.url()).pathname.endsWith(`/v1/private/automations/evaluators/${ruleId}`),
          );
          await rules.chooseDecisionModel(JEV_LATEST);
          await rules.submitPanel();
          expect((await answered).status(), 'saving the reopened Jev rule').toBe(204);

          expect(
            (await backendClient.getLlmJudgeModel(ruleId)).name,
            'the model after the re-save',
          ).toBe(JEV_LATEST);
          expect(
            [...new Set(await backendClient.getLlmJudgeScoreTypes(ruleId))],
            'the score types after the re-save',
          ).toEqual(['BOOLEAN']);
        });
      },
    );

    test(
      'Jev is its own evaluator at trace and span scope, and absent from the LLM-as-a-Judge picker',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, providerKeys, page }) => {
        const rules = new OnlineEvaluationPage(page);

        await test.step('Make sure the workspace has an OpenRouter key', async () => {
          await providerKeys.ensureBuiltIn(OPENROUTER, [JEV_LATEST, JEV_PINNED]);
        });

        await test.step('The decision model editor offers both Jev models, at trace and span scope only', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanel('Decision model');

          await expect(rules.scopeOption('Traces')).toBeVisible();
          await expect(rules.scopeOption('Spans')).toBeVisible();
          await expect(
            rules.scopeOption('Threads'),
            'a thread rule cannot use a decisions model',
          ).toHaveCount(0);

          await rules.decisionModelSelect.click();
          for (const model of [JEV_LATEST, JEV_PINNED]) {
            await expect(page.getByRole('option', { name: model, exact: true })).toBeVisible();
          }
          await page.getByRole('option', { name: JEV_LATEST, exact: true }).click();
          await rules.cancelPanel();
        });

        await test.step('The LLM-as-a-Judge model picker does not list them', async () => {
          await rules.openCreateRulePanel('LLM-as-a-Judge');
          await rules.openModelPicker();

          // The control first: an empty result from a picker that failed to load
          // looks identical to one that correctly left Jev out.
          const control = await rules.searchModels(CHAT_MODEL);
          await expect(
            control,
            `a "${CHAT_MODEL}" search must still return options, or an empty Jev result proves nothing`,
          ).not.toHaveCount(0);

          for (const model of [JEV_LATEST, JEV_PINNED]) {
            const matches = await rules.searchModels(model);
            await expect(
              matches,
              `"${model}" must only be offered by the Decision model evaluator`,
            ).toHaveCount(0);
          }
        });
      },
    );
  },
);
