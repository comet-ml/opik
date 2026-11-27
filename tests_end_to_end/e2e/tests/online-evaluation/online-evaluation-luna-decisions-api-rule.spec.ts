import { test, expect } from '@e2e/fixtures';
import { PROVIDER_GROUP } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * GPT 6 Luna in the online-evaluation rule editor (opik#9358, OPIK-8712): the
 * same model becomes a DECISIONS-API rule from the Decision model editor and an
 * ordinary CHAT judge from the LLM-as-a-Judge editor, and the editor's controls
 * follow.
 *
 * 2.3.1 is the release that gives the dual-API path a UI. opik#9357 had already
 * made `api: "decisions"` reachable over REST, and
 * `online-evaluation-decision-model-rule-validation.spec.ts` pins the
 * validator's rejections for it — that spec says in its own header that "the
 * luna path has no UI this release" and that the `api` field's behaviour on
 * UPDATE is not covered. 9358 adds `DECISIONS_API_MODELS` and wires both
 * editors to `isDecisionModel(model, api)`, so both of those gaps are now
 * reachable and neither is asserted anywhere.
 *
 * ## Why the pair is what makes this discriminating
 *
 * `gpt-6-luna` is served over BOTH APIs by its provider, so nothing about the
 * model name alone says which one a rule uses — only the stored `api` does.
 * That makes a single-arm test worthless here:
 *
 *  - A build that wrote `api: "decisions"` for every judge rule passes the
 *    Decision-model arm and fails the chat arm.
 *  - A build that never wrote it passes the chat arm and fails the
 *    Decision-model arm.
 *  - A build that dropped `DECISIONS_API_MODELS` from the decision select fails
 *    before either, which is why the select's membership is asserted first.
 *
 * Every failure here is silent to a user: the rule looks saved either way, and
 * what differs is which provider API it judges through and what that costs.
 *
 * ## Why the editor's controls are asserted, not just the payload
 *
 * `isDecisionModel` gates three things in the panel — the chat-model picker,
 * the model-parameters gear and the Cost limit box — and a decisions rule can
 * carry none of them (`convertLLMJudgeDataToLLMJudgeObject` sends
 * `max_cost_usd: null` and no sampling params on that path). So the controls and
 * the payload are two readings of one resolution, and a panel that showed
 * chat-only controls over a decisions payload would let a user set a budget the
 * rule cannot honour. Asserted as ABSENT (`toHaveCount(0)`), not disabled.
 *
 * ## Cost
 *
 * Nothing here can bill. The rules are created against the per-test `project`
 * fixture, which holds no traces at all, so no rule ever samples anything; and
 * `automationRulesCleanup` deletes them at teardown whatever the outcome. That
 * is the same basis `online-evaluation-jev-decision-model.spec.ts` creates
 * enabled rules on.
 *
 * `llm-model-policy`'s display-name allowlist is therefore reached through
 * `openModelPicker()` + `chooseModel()` rather than `selectJudgeModel()`, which
 * asserts it. The guard exists to stop a spec RUNNING an expensive model by
 * accident; here `gpt-6-luna` is the subject of the change under test and
 * cannot run, so asserting it off the allowlist would make the behaviour
 * untestable rather than cheaper. Said out loud rather than worked around
 * quietly.
 *
 * ## Not covered
 *
 * That picking Luna offers to connect **OpenAI** rather than OpenRouter
 * (`DecisionModelSelect` resolves the provider off `DECISIONS_API_MODELS`).
 * Exploration could not reach it: the affordance only renders when the
 * workspace has NO key for that provider, and proving it would mean removing a
 * workspace provider key — which is shared state no spec may touch.
 */

/** The two decisions-ONLY models `DECISION_MODELS` declares, as the editor labels them. */
const JEV_LATEST = '~typesafe/jev-latest';
const JEV_PINNED = 'typesafe/jev-1.13';

/**
 * The dual-API model `DECISIONS_API_MODELS` adds, as the editor labels it, and
 * as the API stores it. The two spellings differ — the select renders the
 * label and persists the value — which is itself worth pinning: a build that
 * stored the label would answer a model name no provider knows.
 */
const LUNA_LABEL = 'GPT 6 Luna';
const LUNA_MODEL = 'gpt-6-luna';

const QUESTION = 'Does the output fully answer the input?';

test.describe(
  'Online Evaluation — GPT 6 Luna through the Decisions API and as a chat judge',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'a Luna Decision model rule round-trips as a decisions rule, with none of the chat-only controls',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({
        project,
        backendClient,
        openRouterProvider,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        const ruleName = `${testNamespace}-luna-decision-rule`;
        const rules = new OnlineEvaluationPage(page);

        await test.step('The workspace has an OpenRouter key', async () => {
          // Not for Luna — that is an OpenAI model — but for the two Jev options
          // beside it in the same select. Without a key the select renders a
          // "Connect OpenRouter" affordance instead of settling, and the
          // membership assertion below would be about a half-loaded control.
          expect(
            openRouterProvider.configured,
            'the Jev options in this select resolve only through OpenRouter',
          ).toBe(true);
        });

        await test.step('The decision-model select offers Luna beside both Jev models', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanel('Decision model', 'Task completion');

          // Retried as a whole: the editor remounts the select while its
          // provider-key query settles, which detaches options mid-read.
          await expect(async () => {
            const listbox = await rules.openDecisionModelSelect();
            for (const model of [JEV_LATEST, JEV_PINNED, LUNA_LABEL]) {
              await expect(
                listbox.getByRole('option', { name: model, exact: true }),
                `the decision-model select must offer "${model}"`,
              ).toBeVisible({ timeout: 2_000 });
            }
          }).toPass({ timeout: 30_000 });

          await rules.chooseDecisionModel(LUNA_LABEL);
          await rules.nameInput.fill(ruleName);
          await rules.mainQuestionInput.fill(QUESTION);
        });

        await test.step('With Luna selected the panel shows none of the chat-only controls', async () => {
          // Absent, not disabled. A decisions rule has no chat model, no
          // sampling parameters and no per-evaluation spend, and the request
          // builder sends `max_cost_usd: null` on this path — so a visible Cost
          // limit box would be a budget the rule cannot honour.
          //
          // Asserted here, with Luna selected, rather than only after the
          // reopen: the resolution is `isDecisionModel(model, api)` and `api`
          // is seeded by the Decision-model editor's own defaults, so this is
          // the state in which a build that resolved on the model name alone
          // would already be wrong.
          await expect(rules.modelPicker, 'no chat-model picker').toHaveCount(0);
          await expect(
            rules.judgeModelParametersTrigger,
            'no model-parameters gear for a decisions rule',
          ).toHaveCount(0);
          await expect(
            rules.costLimitLabel,
            'no cost limit for a decisions rule — the payload sends none',
          ).toHaveCount(0);
        });

        await test.step('It submits with no rejection, carrying api: decisions', async () => {
          // The request body, not only its status: `DecisionModelRuleValidator`
          // answers 400 to a decisions payload built wrongly, so a 201 already
          // says the shape was legal — but it does NOT say the editor asked for
          // the Decisions API. A rule that saved `{name: gpt-6-luna}` with no
          // `api` is accepted as an ordinary chat judge and is exactly the
          // silent failure here.
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'POST' &&
              /\/v1\/private\/automations\/evaluators\/?$/.test(new URL(response.url()).pathname),
          );
          await rules.submitPanel();
          const response = await answered;
          expect(
            response.status(),
            `creating a Luna decision rule answered ${response.status()}: ${await response.text()}`,
          ).toBe(201);

          const sent = JSON.parse(response.request().postData() ?? '{}') as {
            code?: { model?: { name?: unknown; api?: unknown } };
          };
          expect(sent.code?.model?.name, 'the model the editor sent').toBe(LUNA_MODEL);
          expect(
            sent.code?.model?.api,
            'and it asked for the Decisions API explicitly — the model name alone cannot say so',
          ).toBe('decisions');
        });

        const ruleId = await test.step('It persisted as a decisions rule', async () => {
          const listed = await backendClient.listAutomationRulesForProject(project.id);
          const match = listed.filter((rule) => rule.name === ruleName);
          expect(match, `rules named "${ruleName}"`).toHaveLength(1);
          const id = match[0].id;

          expect((await backendClient.getLlmJudgeModel(id)).name, 'the persisted model name').toBe(
            LUNA_MODEL,
          );
          expect(
            await backendClient.getLlmJudgeModelApi(id),
            'the persisted api — read back, not inferred from the request above',
          ).toBe('decisions');

          // A decisions model takes a single user turn and answers yes/no, which
          // is what `DecisionModelRuleValidator` enforces. Asserted because the
          // Decision-model editor is what has to build that shape: a template
          // that leaked a system message, or an INTEGER score, would be refused
          // on a later edit even though the create went through.
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

        await test.step('Reopening it comes back as a Luna Decision rule, still without the chat-only controls', async () => {
          // The read path, which is the half the create cannot show: on reopen
          // the form resolves `isDecisionModel` from the STORED `api`, so a
          // build that persisted it and then failed to read it back renders a
          // chat judge over a decisions rule — and the next save would strip the
          // `api` off it.
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openEditRulePanelByName(ruleName);

          await expect(rules.decisionModelSelect, 'the persisted Luna model').toHaveText(
            LUNA_LABEL,
          );
          await expect(rules.mainQuestionInput, 'the persisted question').toHaveValue(QUESTION);
          await expect(rules.modelPicker, 'no chat-model picker').toHaveCount(0);
          await expect(
            rules.judgeModelParametersTrigger,
            'no model-parameters gear for a decisions rule',
          ).toHaveCount(0);
          await expect(rules.costLimitLabel, 'no cost limit for a decisions rule').toHaveCount(0);

          // And the stored rule is unchanged by having been opened. Reading it
          // again is what separates "the editor rendered it right" from "the
          // editor rendered it right and did not rewrite it": the panel is
          // closed without saving below.
          expect(
            await backendClient.getLlmJudgeModelApi(ruleId),
            'opening the rule must not disturb its api',
          ).toBe('decisions');
          await rules.cancelPanel();
        });
      },
    );

    test(
      'the same model picked in the LLM-as-a-Judge editor is a chat judge, with no api key at all',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({
        project,
        backendClient,
        modelRegistryProviders,
        testNamespace,
        automationRulesCleanup,
        page,
      }) => {
        const ruleName = `${testNamespace}-luna-chat-judge`;
        const rules = new OnlineEvaluationPage(page);

        await test.step('The workspace has an OpenAI key, so the judge picker offers Luna', async () => {
          // A precondition, not a hope: `gpt-6-luna` is an OpenAI registry
          // model, and the LLM-as-a-Judge picker only lists models of connected
          // providers. Without this the search below returns nothing and the
          // failure reads as "Luna is missing from the picker" when the truth is
          // "this workspace has no OpenAI key".
          expect(
            modelRegistryProviders.providers,
            'the judge picker lists a provider only once the workspace holds its key',
          ).toContain('openai');
        });

        await test.step('Pick Luna in the LLM-as-a-Judge model picker', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanel('LLM-as-a-Judge');
          // A template first, and in this order: a blank judge has no prompt,
          // no variables and no score schema, so the form will not submit —
          // and applying the template RENAMES the rule after itself, so the
          // name has to be set after it. Same sequence as
          // `fillCreateRuleLLMJudge`, spelled out here because the model is
          // chosen through the lower-level picker calls below.
          await rules.applyJudgeTemplate('Hallucination');
          await rules.nameInput.fill(ruleName);

          // `openModelPicker` + `chooseModel` rather than `selectJudgeModel`,
          // which asserts `llm-model-policy`'s display-name allowlist. See the
          // Cost note in this file's header: the model under test is
          // `gpt-6-luna` itself and nothing here can bill.
          await rules.openModelPicker();
          await rules.chooseModel(LUNA_LABEL);
          await expect(
            rules.modelPicker,
            'the picker settled on Luna before anything is read off the panel',
          ).toContainText(LUNA_LABEL);
        });

        await test.step('The rule is stored at 0% sampling, so it can never call the model', async () => {
          // Belt and braces over the "this project has no traces" argument in
          // the header, and the only one of the two that survives someone later
          // seeding traces into the fixture project: at 0% the rule samples
          // nothing even if traffic arrives. The assertions below are all about
          // what the editor BUILT and what the API stored, none of which depends
          // on the rule ever running.
          await rules.setSamplingRatePercent(0);
        });

        await test.step('The panel shows the chat-only controls and no decision select', async () => {
          // The mirror of the first test, and the half that makes the pair
          // discriminating. These three controls are gated on
          // `isDecisionModel(model, api)` being FALSE, so a build that resolved
          // on the model name alone would hide all three here — the same model,
          // the other editor.
          await expect(
            rules.judgeModelParametersTrigger,
            'a chat judge keeps its model-parameters gear',
          ).toHaveCount(1);
          await expect(rules.costLimitLabel, 'and its Cost limit box').toHaveCount(1);
          await expect(
            rules.decisionModelSelect,
            'and has no decision-model select — that control belongs to the other editor',
          ).toHaveCount(0);
        });

        const ruleId = await test.step('It submits as an ordinary judge rule', async () => {
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'POST' &&
              /\/v1\/private\/automations\/evaluators\/?$/.test(new URL(response.url()).pathname),
          );
          const id = await rules.submitPanelAndCaptureRuleId();
          const response = await answered;
          expect(
            response.status(),
            `creating a Luna chat judge answered ${response.status()}: ${await response.text()}`,
          ).toBe(201);

          const sent = JSON.parse(response.request().postData() ?? '{}') as {
            code?: { model?: Record<string, unknown> };
          };
          expect(sent.code?.model?.['name'], 'the model the editor sent').toBe(LUNA_MODEL);
          // Asserted as the key being ABSENT, not as `undefined` after a cast:
          // the request builder only adds `api` on the decisions path, and
          // sending `api: null` would be a third state the backend reads
          // differently from an omitted key.
          expect(
            Object.keys(sent.code?.model ?? {}),
            'the chat path must not send an api key at all',
          ).not.toContain('api');
          return id;
        });

        await test.step('And persists with no api key — a chat judge, not a decisions one', async () => {
          // The claim the whole pair exists for. `getLlmJudgeModelApi` returns
          // `undefined` for a genuinely absent key rather than defaulting it,
          // which is why "no api" is assertable at all: a reader that defaulted
          // the absent case to `'decisions'` would make this pass for a rule
          // that had wrongly been written as a decisions rule.
          expect(
            (await backendClient.getLlmJudgeModel(ruleId)).name,
            'the persisted model name',
          ).toBe(LUNA_MODEL);
          expect(
            await backendClient.getLlmJudgeModelApi(ruleId),
            'the same model, saved from the chat editor, carries no api — so the stored field ' +
              'is what decides which provider API judges, not the model name',
          ).toBeUndefined();
        });

        await test.step('Reopening it still shows a chat judge on Luna', async () => {
          // The read path for the negative arm: `api` is absent, so
          // `convertLLMJudgeObjectToLLMJudgeData`'s fallback must NOT infer
          // `decisions` for it — `isDecisionModel(gpt-6-luna)` with no `api` is
          // false, and that is the branch the Jev migration fallback must not
          // over-reach into. The sibling spec covers the case where it must.
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openEditRulePanelByName(ruleName);

          await expect(rules.modelPicker, 'the persisted Luna model').toContainText(LUNA_LABEL);
          await expect(
            rules.judgeModelParametersTrigger,
            'still a chat judge, so still a parameters gear',
          ).toHaveCount(1);
          await expect(rules.costLimitLabel, 'and still a Cost limit box').toHaveCount(1);
          await expect(rules.decisionModelSelect, 'and still no decision select').toHaveCount(0);
          await rules.cancelPanel();
        });

        await test.step('Luna is offered under the OpenAI group, not as a decisions-only model', async () => {
          // Where the picker files it, which is the other half of 9358's wiring:
          // `DECISIONS_API_MODELS` carries a `provider`, and Luna's is OpenAI —
          // unlike the Jev models, which are OpenRouter's and are kept out of
          // this picker entirely. A control search runs first, because an empty
          // result from a picker that failed to load looks identical to one that
          // correctly left a model out.
          await rules.openCreateRulePanel('LLM-as-a-Judge');
          const listbox = await rules.openModelPicker();
          const matches = await rules.searchModels(LUNA_LABEL);
          await expect(
            matches,
            `a "${LUNA_LABEL}" search must return options, or the group assertion proves nothing`,
          ).not.toHaveCount(0);
          const group = listbox.getByRole('group', { name: PROVIDER_GROUP.openAi, exact: true });
          await expect(group, 'the OpenAI provider group').toHaveCount(1);
          await expect(
            group.getByRole('option', { name: LUNA_LABEL, exact: true }),
            `"${LUNA_LABEL}" is offered under ${PROVIDER_GROUP.openAi}`,
          ).toHaveCount(1);

          for (const jev of [JEV_LATEST, JEV_PINNED]) {
            const jevMatches = await rules.searchModels(jev);
            await expect(
              jevMatches,
              `"${jev}" is decisions-only and must stay out of the chat picker, unlike Luna`,
            ).toHaveCount(0);
          }
        });
      },
    );
  },
);
