import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * A Jev rule saved BEFORE `model.api` existed stays a Decision rule when its
 * model is switched to GPT 6 Luna (opik#9358, OPIK-8712).
 *
 * This is the migration path for rules that already exist in every customer
 * workspace, and the one case the editor has to INFER `api` rather than read it.
 * `convertLLMJudgeObjectToLLMJudgeData` does it in one line:
 *
 *   api: data.model?.api ?? (isDecisionModel(model) ? "decisions" : undefined)
 *
 * Without that fallback a pre-9357 Jev rule opens with `api: undefined`, and the
 * moment its model is changed to a DUAL-API model — Luna is the only one today —
 * `isDecisionModel(model, api)` goes false and the save silently rewrites the
 * rule as a chat judge: same name, same question, judging through a different
 * provider API at a different price, with nothing on screen saying so.
 *
 * ## Why this needs an e2e spec at all
 *
 * The fallback has Vitest coverage (`schema.decisionModel.test.ts`), which can
 * see the function but not the round trip it sits in. Three things only a live
 * backend shows:
 *
 *  - That the PRE-RELEASE shape is still accepted and still readable. The seed
 *    here is literally a `POST` with `api` omitted, which is what every stored
 *    Jev rule looks like; a `DecisionModelRuleValidator` that had started
 *    REQUIRING `api` would make these rules unopenable, and the unit test would
 *    not notice.
 *  - That the inferred value survives the save as a real `PATCH` body and comes
 *    back off `GET`. The inference happening in the form is not the claim — the
 *    claim is that the rule on the server ends up a decisions rule.
 *  - That the editor renders the stored rule as a Decision rule in the first
 *    place. A pre-release Jev rule reaching the chat editor would offer a
 *    chat-model picker over a model no chat API serves.
 *
 * `online-evaluation-decision-model-rule-validation.spec.ts` says in its own
 * header that the `api` field's behaviour on UPDATE — the validator's other
 * entry point — is not covered. This is that path, driven through the editor
 * that actually issues it.
 *
 * ## Why the negative control is load-bearing
 *
 * Switching Jev -> Luna and asserting `api: "decisions"` would also pass on a
 * build that wrote `api: "decisions"` for every rule it ever saved. So the
 * second test seeds the SAME pre-release shape — a judge rule with no `api` —
 * on a CHAT-ONLY model, switches its model through the editor, and asserts the
 * save still carries no `api` at all. That is the fallback's condition
 * (`isDecisionModel(model)`) read from the other side, and no single build
 * satisfies both tests.
 *
 * The control deliberately stays within the chat editor rather than trying to
 * drive a Decision rule onto a chat-only model: the evaluator type is a prop
 * derived from the stored rule, not a form field, and the decision select
 * offers only decisions-capable models — so that transition does not exist in
 * the product and a spec attempting it would be asserting about a control that
 * is not there.
 *
 * ## Cost
 *
 * Nothing here can bill. The seeded rules are written with `enabled: false`
 * (`JudgeRuleWrite`'s default) and live under the per-test `project` fixture,
 * which holds no traces, so nothing is ever sampled; `automationRulesCleanup`
 * deletes them at teardown whatever the outcome. The saves below go through the
 * editor, which re-sends whatever `enabled` the rule already had, so a rule
 * seeded disabled stays disabled.
 *
 * `llm-model-policy`'s display-name allowlist is reached through
 * `chooseDecisionModel()` and `openModelPicker()` + `chooseModel()` rather than
 * `selectJudgeModel()`, which asserts it: `gpt-6-luna` is the subject of the
 * change under test. Said out loud rather than worked around quietly.
 */

/** The decisions-ONLY model the pre-release rule is seeded on, as stored and as labelled. */
const JEV_PINNED = 'typesafe/jev-1.13';

/** The dual-API model, as the decision select labels it and as the API stores it. */
const LUNA_LABEL = 'GPT 6 Luna';
const LUNA_MODEL = 'gpt-6-luna';

/**
 * Two chat-ONLY models, for the negative control. `isDecisionModel` is false for
 * both under any `api`, so a rule on one of them must gain no `api` when its
 * model changes. Both are on `llm-model-policy`'s allowlist, so the control
 * drives the guarded `selectJudgeModel` rather than the lower-level picker
 * calls.
 */
const CHAT_ONLY_SEEDED = { label: 'GPT 4o Mini', model: 'gpt-4o-mini' };
// The stored id is the DATED one: the picker's display name is undated, and the
// registry resolves it to a pinned snapshot. Pinned as the backend stores it,
// since that is what the save assertion reads.
const CHAT_ONLY_SWITCHED = { label: 'Claude Haiku 4.5', model: 'claude-haiku-4-5-20251001' };

/**
 * The rule's one USER message as STORED — `{{output}}` bound to
 * `output.output` through the rule's `variables` map.
 */
const PROMPT = 'Does the output fully answer the input? {{output}}';

/**
 * The same message as the editor RENDERS it.
 *
 * `inlineVariableMappings` rewrites a tag whose mapping is a plain
 * entity path (`input` / `output` / `metadata`, optionally nested) to the path
 * itself, so a stored `{{output}}` mapped to `output.output` appears in the box
 * as `{{output.output}}`. The two spellings are pinned separately rather than
 * one being compared against the other: the inlining is a deliberate display
 * rule, and a spec that asserted the stored spelling would be asserting the
 * inlining had NOT happened.
 */
const PROMPT_AS_RENDERED = 'Does the output fully answer the input? {{output.output}}';

/**
 * The score's description, which the Decision model editor renders as its
 * QUESTION.
 *
 * Kept apart from `PROMPT` because the editor shows them in two different
 * controls and both share a placeholder: `DecisionModelQuestionsCard` renders
 * each score's description as a question box, so a spec reading
 * `mainQuestionInput` is reading the SCHEMA, not the message. Asserting the
 * prompt against that box would pass only by coincidence.
 */
const SCORE_QUESTION = 'Whether the output answers the input.';

/**
 * The shape a judge rule stored before `model.api` existed: no `api` key at all.
 *
 * Parameterised by model so the control can seed the identical shape on a
 * chat-only model — the point of the control is that only the MODEL differs
 * between the two tests, so the `api` the editor infers cannot be explained by
 * anything else about the rule.
 */
const preReleaseRule = (modelName: string) => ({
  type: 'llm_as_judge' as const,
  model: { name: modelName },
  messages: [{ role: 'USER', content: PROMPT }],
  variables: { output: 'output.output' },
  schema: [{ name: 'Answered', type: 'BOOLEAN' as const, description: SCORE_QUESTION }],
});

test.describe(
  'Online Evaluation — a pre-release Jev rule switched to a dual-API model',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'switching a pre-api Jev rule to GPT 6 Luna keeps it a Decision rule and saves api: decisions',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({
        project,
        backendClient,
        openRouterProvider,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        const ruleName = `${testNamespace}-prerelease-jev-to-luna`;
        const rules = new OnlineEvaluationPage(page);

        await test.step('The workspace has an OpenRouter key', async () => {
          // Jev resolves only through OpenRouter, and without a key the decision
          // select renders a "Connect OpenRouter" affordance instead of settling
          // — so the model switch below would be driving a half-loaded control.
          expect(
            openRouterProvider.configured,
            'a Jev model resolves only through OpenRouter',
          ).toBe(true);
        });

        const ruleId = await test.step('Seed a Jev rule with no api key, as every stored one has', async () => {
          // Written verbatim through `writeJudgeRuleStatus` rather than built by
          // `createLlmJudgeRule`: the ABSENCE of `api` is the whole premise, and
          // a builder that defaulted the field could not express it. The status
          // is asserted rather than thrown on, so a backend that had started
          // refusing this shape reports as the finding it would be rather than
          // as a seeding error.
          const written = await backendClient.writeJudgeRuleStatus({
            ...preReleaseRule(JEV_PINNED),
            projectId: project.id,
            name: ruleName,
          });
          expect(
            written.status,
            `the pre-release shape (a Jev model with no api) must still be accepted, got ` +
              `${written.status}: ${written.message}`,
          ).toBe(201);
          expect(written.ruleId, 'the create reported an id').not.toBeNull();

          // The premise, read back off the server rather than assumed from the
          // request: if the backend had started defaulting `api` on write, every
          // assertion below would be about a rule that was already a decisions
          // rule and the fallback would never be exercised.
          expect(
            await backendClient.getLlmJudgeModelApi(written.ruleId!),
            'the seeded rule carries no api at all — that is what makes this the migration case',
          ).toBeUndefined();
          return written.ruleId!;
        });

        await test.step('It opens in the editor as a Decision rule on the Jev model', async () => {
          // `isDecisionModel(typesafe/jev-1.13)` is true from `DECISION_MODELS`
          // alone, with no `api` needed — which is why a pre-release Jev rule is
          // openable at all. A build that resolved only on `api` would render
          // the chat editor here, offering a chat-model picker over a model no
          // chat API serves.
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openEditRulePanelByName(ruleName);

          await expect(rules.decisionModelSelect, 'the seeded Jev model').toHaveText(JEV_PINNED);
          await expect(rules.modelPicker, 'no chat-model picker').toHaveCount(0);
          await expect(
            rules.judgeModelParametersTrigger,
            'no model-parameters gear for a decisions rule',
          ).toHaveCount(0);
        });

        await test.step('Switch the decision model to Luna and save', async () => {
          // The moment the fallback matters. Luna is dual-API, so from here on
          // `isDecisionModel` needs the form's `api` to be `decisions` — and the
          // only place that value can have come from is the inference, because
          // the stored rule carried none.
          await rules.chooseDecisionModel(LUNA_LABEL);

          // Still a Decision rule on screen, asserted BEFORE the save: if the
          // inference had not run, the panel would have swung to the chat editor
          // the instant the model changed, and the save below would be sending a
          // chat payload for a reason a reader of a 204 cannot see.
          await expect(
            rules.decisionModelSelect,
            'the panel stays on the decision-model select after the switch',
          ).toHaveText(LUNA_LABEL);
          await expect(rules.modelPicker, 'and grows no chat-model picker').toHaveCount(0);
          await expect(rules.costLimitLabel, 'and no cost limit').toHaveCount(0);

          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'PATCH' &&
              new URL(response.url()).pathname.endsWith(
                `/v1/private/automations/evaluators/${ruleId}`,
              ),
          );
          await rules.submitPanel();
          const response = await answered;
          expect(
            response.status(),
            `saving the switched rule answered ${response.status()}: ${await response.text()}`,
          ).toBe(204);

          // The request body, which the 204 cannot report: a save that sent
          // `{name: gpt-6-luna}` with no `api` is accepted just as happily and
          // is exactly the silent rewrite this spec exists to catch.
          const sent = JSON.parse(response.request().postData() ?? '{}') as {
            code?: { model?: { name?: unknown; api?: unknown } };
          };
          expect(sent.code?.model?.name, 'the model the editor sent').toBe(LUNA_MODEL);
          expect(
            sent.code?.model?.api,
            'and it carried the inferred Decisions API through the switch',
          ).toBe('decisions');
        });

        await test.step('The stored rule is a Luna decisions rule', async () => {
          expect(
            (await backendClient.getLlmJudgeModel(ruleId)).name,
            'the model after the save',
          ).toBe(LUNA_MODEL);
          expect(
            await backendClient.getLlmJudgeModelApi(ruleId),
            'the rule on the server is a decisions rule — read back, not inferred from the PATCH',
          ).toBe('decisions');
          expect(
            [...new Set(await backendClient.getLlmJudgeScoreTypes(ruleId))],
            'and still answers yes/no, which is what a decisions model can return',
          ).toEqual(['BOOLEAN']);
        });

        await test.step('Reopening it still shows a Luna Decision rule', async () => {
          // The second read, which is the one a user meets: by now `api` is
          // STORED, so this exercises the read path rather than the fallback —
          // and together with the step above it shows the rule survived a full
          // round trip rather than only a save.
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openEditRulePanelByName(ruleName);

          await expect(rules.decisionModelSelect, 'the persisted Luna model').toHaveText(
            LUNA_LABEL,
          );
          // The rule's own content, unchanged by a model switch. Both are
          // asserted because the editor renders them in two different controls:
          // the message in the CodeMirror prompt box, and the score's
          // description as the question. A switch that silently re-templated the
          // rule — which is what a form reinitialising on the new model would do
          // — loses one or both.
          expect(
            await rules.readPromptText(),
            'the seeded prompt is unchanged, with its variable mapping inlined for display',
          ).toBe(PROMPT_AS_RENDERED);
          await expect(
            rules.mainQuestionInput,
            "and so is the score's question",
          ).toHaveValue(SCORE_QUESTION);
          await expect(rules.modelPicker, 'no chat-model picker').toHaveCount(0);
          await expect(
            rules.judgeModelParametersTrigger,
            'no model-parameters gear',
          ).toHaveCount(0);
          await rules.cancelPanel();
        });
      },
    );

    test(
      'the same pre-api shape on a chat-only model gains no api when its model changes',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({
        project,
        backendClient,
        modelRegistryProviders,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        const ruleName = `${testNamespace}-prerelease-chat-rule`;
        const rules = new OnlineEvaluationPage(page);

        await test.step('The workspace holds keys for both chat providers involved', async () => {
          // Both models have to be OFFERED for the switch to be a switch: an
          // absent provider means the picker lists nothing and the failure reads
          // as "the model is missing" rather than "this workspace has no key".
          expect(
            modelRegistryProviders.providers,
            'the chat-model picker lists a provider only once the workspace holds its key',
          ).toContain('openai');
          expect(modelRegistryProviders.providers, 'and the same for the switch target').toContain(
            'anthropic',
          );
        });

        const ruleId = await test.step(
          'Seed the identical pre-release shape, on a chat-only model',
          async () => {
            // Only the MODEL differs from the first test's seed: same type, same
            // message, same variables, same schema, and `api` just as absent. So
            // whatever the editor infers here cannot be explained by anything
            // about the rule except which model it names — which is exactly what
            // `isDecisionModel` reads.
            const written = await backendClient.writeJudgeRuleStatus({
              ...preReleaseRule(CHAT_ONLY_SEEDED.model),
              projectId: project.id,
              name: ruleName,
            });
            expect(
              written.status,
              `the pre-release shape must still be accepted, got ${written.status}: ${written.message}`,
            ).toBe(201);
            expect(written.ruleId, 'the create reported an id').not.toBeNull();
            expect(
              await backendClient.getLlmJudgeModelApi(written.ruleId!),
              'the seeded rule carries no api at all, exactly as the Jev one does',
            ).toBeUndefined();
            return written.ruleId!;
          },
        );

        await test.step('It opens as a chat judge, with no decision select', async () => {
          // The fallback's condition read from the other side: with no stored
          // `api` and a model `isDecisionModel` rejects, the form's `api` stays
          // `undefined` and the chat editor renders. A build whose fallback
          // ignored the model would put a decision select here instead, over a
          // model the Decisions API does not serve.
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openEditRulePanelByName(ruleName);

          await expect(rules.modelPicker, 'the seeded chat model').toContainText(
            CHAT_ONLY_SEEDED.label,
          );
          await expect(
            rules.decisionModelSelect,
            'no decision-model select for a chat-only model',
          ).toHaveCount(0);
          // The chat-model picker's PRESENCE is the whole claim here, and it is
          // the exact inverse of the Jev rule's panel in the test above, where
          // the picker is absent and the select is there. The gear and the Cost
          // limit box are deliberately not asserted at this point: a rule
          // seeded over REST carries no provider config yet, so the gear's
          // presence here is about config hydration rather than about which
          // editor rendered. The sibling Luna spec asserts them off a model
          // chosen through the picker, which is where that claim belongs.
        });

        await test.step('Switch its model and save', async () => {
          // A model change, so the comparison with the first test is like for
          // like: there the switch carried the inferred `api` forward, here there
          // is nothing to carry. Both switch targets are on
          // `llm-model-policy`'s allowlist, so this arm goes through the guarded
          // `selectJudgeModel` — no bypass is needed for the control.
          await rules.selectJudgeModel(CHAT_ONLY_SWITCHED.label);
          await expect(rules.modelPicker, 'the picker settled on the new model').toContainText(
            CHAT_ONLY_SWITCHED.label,
          );
          await expect(
            rules.decisionModelSelect,
            'and the panel still has no decision select',
          ).toHaveCount(0);

          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'PATCH' &&
              new URL(response.url()).pathname.endsWith(
                `/v1/private/automations/evaluators/${ruleId}`,
              ),
          );
          await rules.submitPanel();
          const response = await answered;
          expect(
            response.status(),
            `saving the switched chat rule answered ${response.status()}: ${await response.text()}`,
          ).toBe(204);

          const sent = JSON.parse(response.request().postData() ?? '{}') as {
            code?: { model?: Record<string, unknown> };
          };
          expect(sent.code?.model?.['name'], 'the model the editor sent').toBe(
            CHAT_ONLY_SWITCHED.model,
          );
          // Asserted as the KEY being absent, not as `undefined` after a cast:
          // the request builder only adds `api` on the decisions path, and an
          // explicit `api: null` would be a third state the backend reads
          // differently from an omitted key.
          expect(
            Object.keys(sent.code?.model ?? {}),
            'a chat-only rule must not acquire an api — this is the assertion a build that ' +
              'wrote api: "decisions" unconditionally fails, and it would pass every ' +
              'assertion in the test above',
          ).not.toContain('api');
        });

        await test.step('And the stored rule still has no api', async () => {
          expect(
            (await backendClient.getLlmJudgeModel(ruleId)).name,
            'the model after the save',
          ).toBe(CHAT_ONLY_SWITCHED.model);
          expect(
            await backendClient.getLlmJudgeModelApi(ruleId),
            'the inference is scoped to models that can actually use the Decisions API',
          ).toBeUndefined();
        });
      },
    );
  },
);
