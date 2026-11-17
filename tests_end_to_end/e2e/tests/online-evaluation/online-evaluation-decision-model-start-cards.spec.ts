import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * The two prompts `decisionModelTemplates.ts` builds its six cards from. Which
 * card gets which is the whole subject of the first test, so they are spelled
 * out here rather than derived from the card list.
 */
const INPUT_AND_OUTPUT = 'INPUT:\n{{input}}\n\nOUTPUT:\n{{output}}';
const OUTPUT_ONLY = 'OUTPUT:\n{{output}}';

interface DecisionModelCard {
  /** Card label, which is also the name the card gives an unnamed rule. */
  label: string;
  prompt: string;
  /** Every question the card applies, in order. */
  questions: string[];
  /**
   * The score names the card applies, in order — `null` where the template
   * leaves the score unnamed and the Questions card falls back to the rule
   * name, which by then is the card's own label.
   */
  names: (string | null)[];
}

const DECISION_MODEL_CARDS: DecisionModelCard[] = [
  {
    label: 'Task completion',
    prompt: INPUT_AND_OUTPUT,
    questions: ['Does the output complete the task the user asked for in the input?'],
    names: [null],
  },
  {
    label: 'Hallucination',
    prompt: INPUT_AND_OUTPUT,
    questions: ['Does the output make claims that the input does not support?'],
    names: [null],
  },
  {
    label: 'Answer relevance',
    prompt: INPUT_AND_OUTPUT,
    questions: ['Does the output directly answer the question in the input?'],
    names: [null],
  },
  {
    label: 'Moderation',
    prompt: OUTPUT_ONLY,
    questions: ['Does the output contain unsafe, harmful or offensive content?'],
    names: [null],
  },
  {
    label: 'Compliance risk',
    prompt: OUTPUT_ONLY,
    questions: [
      'Does the output give legal, medical or financial advice that could create a compliance risk?',
    ],
    names: [null],
  },
  {
    label: 'Support reply',
    prompt: INPUT_AND_OUTPUT,
    questions: [
      'Does the output solve the problem the user describes?',
      'Is the tone of the output polite and professional?',
      'Does the output ask the user to contact a person or another team?',
    ],
    names: [null, 'Polite tone', 'Escalation'],
  },
];

function cardByLabel(label: string): DecisionModelCard {
  const card = DECISION_MODEL_CARDS.find((candidate) => candidate.label === label);
  if (!card) throw new Error(`no decision-model card expectation named '${label}'`);
  return card;
}

/** What `readQuestionNames` must show once `label` has auto-named the rule. */
function displayedNames(card: DecisionModelCard): string[] {
  return card.names.map((name) => name ?? card.label);
}

/**
 * The six decision-model start cards a new rule opens on (opik#9143,
 * OPIK-8372), and what each one writes.
 *
 * A card is a prompt plus a question list, and the editor shows both — but what
 * reaches the judge is the PERSISTED prompt, score names and variable map, and
 * only the prompt and the questions are on screen. So the apply is asserted
 * through the editor and the save through the API, which is the disagreement
 * worth catching: a card wired to another card's prompt, or one that applied
 * the first of its questions and dropped the rest, looks identical to a correct
 * one until the rule runs.
 *
 * `online-evaluation-jev-decision-model.spec.ts` already creates a rule from
 * the Task completion card, and deliberately does not cover this: it overwrites
 * the applied question with its own before saving, and asserts roles and score
 * TYPES rather than what the template put there. Both specs would stay green
 * for a Support reply card that lost two of its three questions.
 *
 * Deterministic and free. No provider call is ever made — the rules are created
 * and never scored — and a decisions model needs no provider key for the editor
 * to offer it, so nothing here depends on what the workspace already holds.
 */
test.describe(
  'Online Evaluation — decision model start cards',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'every card applies its own prompt and its whole question list',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, page }) => {
        const rules = new OnlineEvaluationPage(page);

        await test.step('Open the online evaluation page on an empty project', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
        });

        for (const card of DECISION_MODEL_CARDS) {
          // A fresh panel per card, so each assertion is about what the card
          // APPLIES and not about what survived the card before it. Cancelled
          // rather than saved: the save path is the next two tests.
          await test.step(`The "${card.label}" card applies its prompt and questions`, async () => {
            await rules.openCreateRulePanelOnStartCards('Decision model');
            // Untouched, so the card's own auto-naming is observable below.
            await expect(rules.nameInput, 'a new rule starts unnamed').toHaveValue('');

            await rules.chooseStartCard(card.label);

            await expect(
              rules.nameInput,
              'choosing a card names the rule after it',
            ).toHaveValue(card.label);
            expect(
              await rules.readPromptText(),
              `the "${card.label}" card's prompt`,
            ).toBe(card.prompt);
            // Compared as a whole list, not searched: a partial apply keeps the
            // first question and drops the rest, which a per-question check
            // would walk straight past.
            expect(
              await rules.readQuestions(),
              `the "${card.label}" card's questions, all of them and in order`,
            ).toEqual(card.questions);
            expect(
              await rules.readQuestionNames(),
              `the "${card.label}" card's score names`,
            ).toEqual(displayedNames(card));

            await rules.cancelPanel();
          });
        }
      },
    );

    test(
      'a rule saved from the Support reply card keeps all three of its questions',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const card = cardByLabel('Support reply');
        const ruleName = `${testNamespace}-support-reply`;
        const rules = new OnlineEvaluationPage(page);

        const ruleId = await test.step('Create a rule from the Support reply card', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanelOnStartCards('Decision model');
          await rules.chooseStartCard(card.label);
          // The card has just auto-named the rule after itself; this replaces
          // that with the run-namespaced name, which is what the main score
          // ends up being filed under and what the assertions below expect.
          await rules.nameInput.fill(ruleName);
          await expect(
            rules.questionInputs,
            'the panel being saved is showing all three questions',
          ).toHaveCount(card.questions.length);
          return rules.submitPanelAndCaptureRuleId();
        });

        await test.step('The stored prompt is the card\'s, as one user turn', async () => {
          const messages = await backendClient.getLlmJudgeMessages(ruleId);
          expect(messages, 'a decisions model takes a single message').toHaveLength(1);
          expect(messages[0].role, 'and it is a USER turn').toBe('USER');
          expect(messages[0].content, 'carrying the card\'s prompt').toBe(card.prompt);
        });

        await test.step('All three questions persist, keeping the names the card gave them', async () => {
          const { code } = await backendClient.getLlmJudgeRuleBody(ruleId);
          // The whole schema compared at once — every field of every entry.
          // Each question is one score, so a dropped question is a score the
          // judge never answers, and the names are what the feedback scores are
          // filed under; both are invisible in the editor after the save.
          expect(
            code.schema,
            'the three questions the card applied, as three BOOLEAN scores',
          ).toEqual([
            {
              // `prepareForSubmit` names the unnamed main score after the rule.
              name: ruleName,
              type: 'BOOLEAN',
              description: card.questions[0],
            },
            { name: 'Polite tone', type: 'BOOLEAN', description: card.questions[1] },
            { name: 'Escalation', type: 'BOOLEAN', description: card.questions[2] },
          ]);
        });

        await test.step('And the variable map is exactly the two the prompt names', async () => {
          const { code } = await backendClient.getLlmJudgeRuleBody(ruleId);
          expect(
            code.variables,
            'both of the card prompt\'s variables, and nothing else',
          ).toEqual({ input: 'input', output: 'output' });
        });

        await test.step('Reopening the rule shows the card\'s questions, not the cards', async () => {
          await expect(rules.ruleRow(ruleName)).toHaveCount(1);
          await rules.openEditRulePanelByName(ruleName);
          await expect(
            rules.startCards,
            'a rule that already has a prompt opens on the editor, not on the cards',
          ).toHaveCount(0);
          expect(await rules.readQuestions(), 'the questions hydrate back').toEqual(
            card.questions,
          );
          expect(
            await rules.readQuestionNames(),
            'and so do their names, the main one now stored rather than falling back',
          ).toEqual([ruleName, 'Polite tone', 'Escalation']);
          await rules.cancelPanel();
        });
      },
    );

    test(
      'a rule saved from an output-only card does not gain an input variable',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const card = cardByLabel('Moderation');
        const ruleName = `${testNamespace}-moderation`;
        const rules = new OnlineEvaluationPage(page);

        const ruleId = await test.step('Create a rule from the Moderation card', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanelOnStartCards('Decision model');
          await rules.chooseStartCard(card.label);
          await rules.nameInput.fill(ruleName);
          return rules.submitPanelAndCaptureRuleId();
        });

        await test.step('The stored prompt judges the output alone', async () => {
          const messages = await backendClient.getLlmJudgeMessages(ruleId);
          expect(messages, 'a decisions model takes a single message').toHaveLength(1);
          expect(messages[0].content, 'the output-only prompt').toBe(card.prompt);
        });

        await test.step('So the variable map names the output and nothing else', async () => {
          const { code } = await backendClient.getLlmJudgeRuleBody(ruleId);
          // The interesting half of this spec, and the reason an output-only
          // card is worth saving at all: the variable map is derived from the
          // prompt at submit time, so a map still carrying `input` would hand
          // the judge a field this template never asks about — and the editor
          // shows no variable map at all, so nothing on screen would say so.
          expect(
            code.variables,
            'an output-only template must not leak an input variable',
          ).toEqual({ output: 'output' });
          expect(code.schema, 'and it declares its one question').toEqual([
            { name: ruleName, type: 'BOOLEAN', description: card.questions[0] },
          ]);
        });
      },
    );
  },
);
