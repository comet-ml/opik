import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/** The two Decisions-API models `DECISION_MODELS` declares, as the picker labels them. */
const JEV_LATEST = '~typesafe/jev-latest';
const JEV_PINNED = 'typesafe/jev-1.13';

/**
 * A chat model to start from, and the control for "the picker is not simply
 * dead".
 *
 * Addressed by its OpenRouter id rather than by a vendor display name: the
 * picker only lists providers the workspace holds a key for, and the key this
 * spec guarantees is the OpenRouter one. `ALLOWED_MODEL_DISPLAY_NAMES` carries
 * this exact string for the same reason. Nothing here ever calls it — the rule
 * is created, never run.
 */
const CHAT_MODEL = 'openai/gpt-4o-mini';

/**
 * The built-in provider a Jev model resolves to. Workspace-global and unnamed,
 * so the fixture reuses an existing key rather than seeding over it.
 */
const OPENROUTER = 'openrouter';

/**
 * Jev (TypeSafe Decisions API models, served through OpenRouter) in the online
 * evaluation rule dialog (opik#8540, OPIK-8510).
 *
 * Decisions models are deliberately kept OUT of the model registry and out of
 * `PROVIDER_MODEL_TYPE` — `scripts/sync_provider_models.py` regenerates both
 * and its source list does not carry them. They are added back in two separate
 * places, and the distinction is the whole subject here:
 *
 *   - `withDecisionModels` adds them to the RESOLUTION map only, so a persisted
 *     rule on `~typesafe/jev-latest` still resolves to OpenRouter. No dropdown
 *     lists them from this.
 *   - `extraProviderModels` in `LLMJudgeRuleDetails` is the opt-in that puts
 *     them in THIS dialog's picker — and it is `undefined` at thread scope,
 *     because a thread rule cannot use one.
 *
 * Both failure modes are silent. A Jev entry appearing where it should not
 * (thread scope) lets a user build a rule that only fails on submit with a 400;
 * a Jev entry failing to appear where it should leaves the feature simply
 * missing. `online-evaluation-smoke.spec.ts` would pass with either.
 *
 * Deterministic and free: the rule is created but never scored, so no provider
 * call is made and no LLM output is asserted. The OpenRouter key exists only so
 * the provider resolves — its secret is never used.
 */
test.describe(
  'Online Evaluation — Jev decision models',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'a Jev rule saves, persists as a decisions-model rule, and reopens in Jev mode',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, backendClient, providerKeys, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-jev-rule`;
        const rules = new OnlineEvaluationPage(page);

        await test.step('Make sure the workspace has an OpenRouter key', async () => {
          // Reused if one is already there; seeded (and torn down) only if not.
          // Never used to make a call — the rule is created, not run.
          await providerKeys.ensureBuiltIn(OPENROUTER, [JEV_LATEST, JEV_PINNED]);
        });

        await test.step('Build the rule in the dialog, switching to Jev', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRuleDialog();
          await rules.dialog.getByRole('textbox', { name: 'Rule name' }).fill(ruleName);

          // Start on a chat model and switch, which is the real gesture and the
          // one that exercises `toDecisionModelDetails`. Creating straight onto
          // Jev would skip the adaptation entirely.
          await rules.openModelPicker();
          await rules.chooseModel(CHAT_MODEL);
          await rules.openModelPicker();
          await rules.chooseModel(JEV_LATEST);
        });

        await test.step('It submits with no rejection', async () => {
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'POST' &&
              /\/v1\/private\/automations\/evaluators\/?$/.test(new URL(response.url()).pathname),
          );
          await rules.submitRuleDialog();
          const response = await answered;
          // The status, not just "the dialog closed": `DecisionModelRuleValidator`
          // is the seam this test exists for, and a 400 from it is exactly what
          // a payload the dialog built wrongly would produce.
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
          ).toBe(JEV_LATEST);

          // Exactly one USER message, and the whole list compared: a decisions
          // model takes a single user turn, and a stray system message is the
          // shape `DecisionModelRuleValidator` refuses — so "our message is in
          // there" would not be the assertion worth making.
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

        await test.step('Reopening it shows Jev, with the chat-only controls gone', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openEditRuleDialogByName(ruleName);

          expect(
            await rules.readModelPickerText(),
            'the model picker must resolve the persisted Jev model to OpenRouter',
          ).toContain(JEV_LATEST);
          // Absent, not disabled: `isDecisionModel` removes them. A decisions
          // model has no sampling parameters and no per-evaluation spend, so
          // offering either would be offering something that does nothing.
          await expect(
            rules.modelSettingsButton,
            'the model-parameters gear must not be offered for a decisions model',
          ).toHaveCount(0);
          await expect(
            rules.maxCostLabel,
            'the max-cost field must not be offered for a decisions model',
          ).toHaveCount(0);
        });

        await test.step('An edit re-saves and persists', async () => {
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'PATCH' &&
              new URL(response.url()).pathname.endsWith(`/v1/private/automations/evaluators/${ruleId}`),
          );
          await rules.submitRuleDialog();
          expect((await answered).status(), 'saving the reopened Jev rule').toBe(204);

          // And it is still the same rule afterwards — a re-save that
          // round-tripped the model through the chat shape would come back on
          // a different model, or with the scores widened past BOOLEAN.
          expect(
            (await backendClient.getLlmJudgeModel(ruleId)).name,
            'the model after an unedited re-save',
          ).toBe(JEV_LATEST);
          expect(
            [...new Set(await backendClient.getLlmJudgeScoreTypes(ruleId))],
            'the score types after an unedited re-save',
          ).toEqual(['BOOLEAN']);
        });
      },
    );

    test(
      'Jev is offered at trace and span scope, and not at thread scope',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, providerKeys, automationRulesCleanup, page }) => {
        const rules = new OnlineEvaluationPage(page);

        await test.step('Make sure the workspace has an OpenRouter key', async () => {
          await providerKeys.ensureBuiltIn(OPENROUTER, [JEV_LATEST, JEV_PINNED]);
        });

        await test.step('Open the create-rule dialog', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRuleDialog();
        });

        for (const scope of ['Trace', 'Span'] as const) {
          await test.step(`At ${scope} scope the picker offers both Jev models`, async () => {
            await rules.setScope(scope);
            await rules.openModelPicker();
            // Each by its exact label, rather than asserting on everything a
            // "jev" search returns: `typesafe/jev-router` is an ordinary
            // OpenRouter chat model that the registry carries independently of
            // DECISION_MODELS, so it matches that search too and is no part of
            // this claim. Searching the exact name keeps the assertion about
            // the two models the opt-in adds.
            for (const model of [JEV_LATEST, JEV_PINNED]) {
              const matches = await rules.searchModels(model);
              await expect(
                matches,
                `"${model}" must be offered at ${scope} scope, exactly once`,
              ).toHaveCount(1);
            }
            await page.keyboard.press('Escape');
          });
        }

        await test.step('At Thread scope neither Jev model is offered', async () => {
          await rules.setScope('Thread');
          await rules.openModelPicker();

          // The control FIRST, in the same open picker: an empty result from a
          // picker that failed to load looks identical to one that correctly
          // filtered Jev out, and the whole claim here is that it filtered.
          const control = await rules.searchModels(CHAT_MODEL);
          await expect(
            control,
            `a "${CHAT_MODEL}" search must still return options at thread scope, or an empty ` +
              'Jev result proves nothing',
          ).not.toHaveCount(0);

          for (const model of [JEV_LATEST, JEV_PINNED]) {
            const matches = await rules.searchModels(model);
            await expect(
              matches,
              `"${model}" must NOT be offered at thread scope — extraProviderModels is ` +
                'undefined there, so a match means a user can pick a model whose rule only ' +
                'fails on submit with a 400',
            ).toHaveCount(0);
          }
        });
      },
    );
  },
);
