import { test, expect } from '@e2e/fixtures';
import type { JudgeRuleWrite } from '@e2e/core/backend';

/**
 * `DecisionModelRuleValidator` — which judge rules a decisions model is allowed
 * to run, and the message a user gets when it is not (opik#9357, OPIK-8712).
 *
 * 9357 made the Decisions API reachable on a model that ALSO serves chat
 * (`gpt-6-luna`, via `api: "decisions"`), which turned the validator from "Jev
 * rules are special" into a resolution over the model name and the `api` field
 * together. Each rejection below is a distinct sentence a user sees after
 * misconfiguring a rule, and the accepted pair are the two shapes that must
 * keep working.
 *
 * Why this is worth a permanent spec when the PR shipped
 * `DecisionModelRuleValidatorTest`:
 *
 *  - The validator runs inside the create/update path of a resource, after
 *    Jackson has deserialized `api` off the wire. A unit test over
 *    hand-built `AutomationRuleEvaluator` objects cannot see a field that fails
 *    to deserialize, or a model name the enum spells differently from the wire,
 *    and both of those present as "my rule was refused for no reason".
 *  - `online-evaluation-jev-decision-model.spec.ts` guards the happy-path round
 *    trip through the editor and asserts no rejection at all. It is the only
 *    thing in the estate that touches decisions rules, so every refusal here is
 *    unasserted — and a refusal that stops being issued silently lets a user
 *    store a rule that can only fail later, at scoring time, where the failure
 *    is a log line.
 *  - The luna path has no UI this release (`constants/decisionModels.ts` still
 *    lists only the two Jev models), so API callers are its whole audience.
 *
 * API-level, with no browser, and deliberately: every one of these payloads is
 * one the editor cannot build. The dialog will not offer an INTEGER score on a
 * decisions model, will not add a second message, and does not know `gpt-6-luna`
 * exists — so driving the UI could not reach a single rejection here.
 *
 * Deterministic and free. Nine creates, no scoring, no provider call: the
 * validator runs before any client is resolved, and every rule is written with
 * `enabled: false` so an accepted one cannot start sampling the workspace's
 * traffic (see `JudgeRuleWrite.enabled`). That also makes it safe on an
 * environment whose provider keys are rejected upstream, which staging's are.
 *
 * Tagged `@cap:online-evaluation.create-llm-judge-rule` — the same key
 * `online-evaluation-jev-decision-model.spec.ts` carries, and for the same
 * reason the taxonomy records against it: `decision_model` has no capability of
 * its own, so decision-rule creation rides on the judge key. This spec asserts
 * only CREATE: no rule is edited, enabled, or scored, and the `api` field's
 * behaviour on UPDATE (the validator's other entry point) is not covered.
 */

/** A decisions-ONLY model: the Decisions API with or without `api`. */
const JEV_MODEL = '~typesafe/jev-latest';

/**
 * A model its provider serves over BOTH APIs. The whole subject of 9357: it
 * takes the Decisions API only when the rule asks for it, and is an ordinary
 * chat judge otherwise.
 */
const DUAL_API_MODEL = 'gpt-6-luna';

/** A chat-only model — nothing here ever calls it. */
const CHAT_ONLY_MODEL = 'gpt-4o';

const ONE_USER_MESSAGE = [{ role: 'USER', content: 'Does the output answer the input? {{output}}' }];
const OUTPUT_VARIABLE = { output: 'output.output' };
const BOOLEAN_SCHEMA = [
  { name: 'Answered', type: 'BOOLEAN' as const, description: 'Whether the output answers.' },
];
const INTEGER_SCHEMA = [
  { name: 'Answered', type: 'INTEGER' as const, description: 'Whether the output answers.' },
];

test.describe(
  'Online Evaluation — decision-model rule validation (opik#9357)',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'a decisions rule is accepted on both decision models and round-trips its api, and refused on a chat-only one',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup }) => {
        /** One create, named per case so a leftover rule names the case that left it. */
        const write = (label: string, rule: Omit<JudgeRuleWrite, 'projectId' | 'name'>) =>
          backendClient.writeJudgeRuleStatus({
            ...rule,
            projectId: project.id,
            name: `${testNamespace}-${label}`,
          });

        await test.step('api=decisions on a chat-only model is refused by name', async () => {
          const refused = await write('chat-only-decisions', {
            type: 'llm_as_judge',
            model: { name: CHAT_ONLY_MODEL, temperature: 0, api: 'decisions' },
            messages: ONE_USER_MESSAGE,
            variables: OUTPUT_VARIABLE,
            schema: BOOLEAN_SCHEMA,
          });
          expect(refused.status, 'asking for an API the model does not serve').toBe(400);
          // The model name has to be IN the message: this is the one rejection a
          // user hits by picking the wrong model rather than by building the
          // rule wrongly, and a message that did not say which model was wrong
          // would send them looking at the rest of the form.
          expect(refused.message).toBe(`Model '${CHAT_ONLY_MODEL}' has no Decisions API`);
          expect(refused.ruleId, 'a refused create stores nothing').toBeNull();
        });

        await test.step('api=decisions on the dual-API model is accepted and reads back', async () => {
          const accepted = await write('dual-api-decisions', {
            type: 'llm_as_judge',
            model: { name: DUAL_API_MODEL, temperature: 0, api: 'decisions' },
            messages: ONE_USER_MESSAGE,
            variables: OUTPUT_VARIABLE,
            schema: BOOLEAN_SCHEMA,
          });
          expect(
            accepted.status,
            `creating a decisions rule on ${DUAL_API_MODEL} answered ${accepted.status}: ${accepted.message}`,
          ).toBe(201);
          const ruleId = accepted.ruleId;
          expect(ruleId, 'the 201 carried a Location naming the stored rule').not.toBeNull();

          // Read back, because the whole point of 9357 is that this field
          // decides which client scores the rule. A rule stored with `api`
          // dropped is a rule that silently became a chat judge — it lists,
          // opens and scores perfectly plausibly, against the wrong API.
          expect(
            await backendClient.getLlmJudgeModelApi(ruleId as string),
            'the stored rule still asks for the Decisions API',
          ).toBe('decisions');
          expect(
            (await backendClient.getLlmJudgeModel(ruleId as string)).name,
            'and still names the model it was created on',
          ).toBe(DUAL_API_MODEL);
        });

        await test.step('a Jev rule needs no api field, and reads back without one', async () => {
          const accepted = await write('jev-implicit-decisions', {
            type: 'llm_as_judge',
            model: { name: JEV_MODEL, temperature: 0 },
            messages: ONE_USER_MESSAGE,
            variables: OUTPUT_VARIABLE,
            schema: BOOLEAN_SCHEMA,
          });
          expect(
            accepted.status,
            `creating a Jev rule answered ${accepted.status}: ${accepted.message}`,
          ).toBe(201);
          const ruleId = accepted.ruleId;
          expect(ruleId, 'the 201 carried a Location naming the stored rule').not.toBeNull();

          // ABSENT, not 'decisions'. A decisions-only model resolves to the
          // Decisions API on its name alone, and a server that helpfully filled
          // the field in would make the dual-API assertion above pass for every
          // model — including a chat one.
          expect(
            await backendClient.getLlmJudgeModelApi(ruleId as string),
            'a decisions-only model carries no api field of its own',
          ).toBeUndefined();
          expect(
            [...new Set(await backendClient.getLlmJudgeScoreTypes(ruleId as string))],
            'a decisions model answers yes/no, so every score is BOOLEAN',
          ).toEqual(['BOOLEAN']);
        });
      },
    );

    test(
      'every shape a decisions model cannot run is refused with the message that names it',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup }) => {
        const write = (label: string, rule: Omit<JudgeRuleWrite, 'projectId' | 'name'>) =>
          backendClient.writeJudgeRuleStatus({
            ...rule,
            projectId: project.id,
            name: `${testNamespace}-${label}`,
          });

        /**
         * Each refusal with the message it must answer, verbatim.
         *
         * Compared in full rather than by substring: a 400 for the wrong reason
         * is a different defect from the one each case is about, and five rules
         * that all answered "need exactly one user message" would satisfy every
         * substring check on the word "decisions".
         */
        const refusals: Array<{
          label: string;
          why: string;
          rule: Omit<JudgeRuleWrite, 'projectId' | 'name'>;
          message: string;
        }> = [
          {
            label: 'jev-integer-score',
            why: 'a non-Boolean score',
            rule: {
              type: 'llm_as_judge',
              model: { name: JEV_MODEL, temperature: 0 },
              messages: ONE_USER_MESSAGE,
              variables: OUTPUT_VARIABLE,
              schema: INTEGER_SCHEMA,
            },
            message:
              "Decisions models only support Boolean scores, score 'Answered', type 'INTEGER'",
          },
          {
            label: 'jev-spend-budget',
            why: 'a per-evaluation spend budget the decisions path cannot meter',
            rule: {
              type: 'llm_as_judge',
              model: { name: JEV_MODEL, temperature: 0 },
              messages: ONE_USER_MESSAGE,
              variables: OUTPUT_VARIABLE,
              schema: BOOLEAN_SCHEMA,
              maxCostUsd: 1,
            },
            message: `Decisions models don't support a spend budget, model '${JEV_MODEL}'`,
          },
          {
            label: 'jev-two-messages',
            why: 'more than the single user turn a decisions call takes',
            rule: {
              type: 'llm_as_judge',
              model: { name: JEV_MODEL, temperature: 0 },
              messages: [{ role: 'SYSTEM', content: 'You are a judge.' }, ...ONE_USER_MESSAGE],
              variables: OUTPUT_VARIABLE,
              schema: BOOLEAN_SCHEMA,
            },
            message: `Rules on decisions models need exactly one user message, model '${JEV_MODEL}'`,
          },
          {
            label: 'jev-trace-variable',
            why: 'the {{trace}} variable, which drives the agentic path',
            rule: {
              type: 'llm_as_judge',
              model: { name: JEV_MODEL, temperature: 0 },
              messages: [{ role: 'USER', content: 'Judge this run: {{trace}}' }],
              variables: { trace: 'trace' },
              schema: BOOLEAN_SCHEMA,
            },
            message: `Decisions models don't support the '{{trace}}' variable, model '${JEV_MODEL}'`,
          },
          {
            label: 'jev-thread-scope',
            why: 'thread scope, which has no decisions equivalent',
            rule: {
              type: 'trace_thread_llm_as_judge',
              model: { name: JEV_MODEL, temperature: 0 },
              messages: [{ role: 'USER', content: 'Judge this conversation: {{context}}' }],
              schema: BOOLEAN_SCHEMA,
            },
            message: `Thread rules don't support decisions models, model '${JEV_MODEL}'`,
          },
        ];

        for (const refusal of refusals) {
          await test.step(`A decisions rule with ${refusal.why} is refused`, async () => {
            const answer = await write(refusal.label, refusal.rule);
            expect(
              answer.status,
              `${refusal.label} answered ${answer.status}: ${answer.message}`,
            ).toBe(400);
            expect(answer.message, `the message for ${refusal.label}`).toBe(refusal.message);
            expect(answer.ruleId, `${refusal.label} must store nothing`).toBeNull();
          });
        }

        await test.step('The same INTEGER score is accepted off the decisions path', async () => {
          // The control, and the reason the INTEGER refusal above is evidence
          // about decisions rules rather than about INTEGER scores. The model is
          // the SAME dual-API one that took `api: decisions` in the test above;
          // the only difference is that this rule does not ask for the Decisions
          // API, so the validator passes it through. Without this, a build that
          // refused every INTEGER judge rule in the product would look correct.
          const accepted = await write('dual-api-chat-integer', {
            type: 'llm_as_judge',
            model: { name: DUAL_API_MODEL, temperature: 0 },
            messages: ONE_USER_MESSAGE,
            variables: OUTPUT_VARIABLE,
            schema: INTEGER_SCHEMA,
          });
          expect(
            accepted.status,
            `an INTEGER-scored chat rule on ${DUAL_API_MODEL} answered ${accepted.status}: ` +
              `${accepted.message} — the refusals above are then not specific to decisions rules`,
          ).toBe(201);
          expect(
            await backendClient.getLlmJudgeModelApi(accepted.ruleId as string),
            'a rule that asked for no API carries none',
          ).toBeUndefined();
          expect(
            await backendClient.getLlmJudgeScoreTypes(accepted.ruleId as string),
            'and kept the INTEGER score the decisions path refuses',
          ).toEqual(['INTEGER']);
        });

        await test.step('Only the accepted rule is stored under the project', async () => {
          // The refusals' own counterpart, asserted by exhaustion: each `400`
          // above says nothing stored by its own `Location`, but a create that
          // wrote the row and then failed the validator would leave a rule
          // behind with no Location at all. One rule, and it is the control.
          const stored = await backendClient.listAutomationRulesForProject(project.id);
          expect(
            stored.map((rule) => rule.name),
            'exactly the one accepted rule exists in the project',
          ).toEqual([`${testNamespace}-dual-api-chat-integer`]);
        });
      },
    );
  },
);
