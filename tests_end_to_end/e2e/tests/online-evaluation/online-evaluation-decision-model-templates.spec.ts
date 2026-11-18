import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * The two prompts `decisionModelTemplates.ts` builds its six templates from.
 * Which template gets which is the whole subject of the first test, so they are
 * spelled out here rather than derived from the template list.
 */
const INPUT_AND_OUTPUT = 'INPUT:\n{{input}}\n\nOUTPUT:\n{{output}}';
const OUTPUT_ONLY = 'OUTPUT:\n{{output}}';

interface DecisionModelTemplate {
  /** Tab label, which is also the name the template gives an unnamed rule. */
  label: string;
  prompt: string;
  /** Every question the template applies, in order. */
  questions: string[];
  /**
   * The score names the template applies, in order — `null` for the main
   * score, which takes the rule name, and by then that is the template's own
   * label.
   */
  names: (string | null)[];
}

const DECISION_MODEL_TEMPLATES: DecisionModelTemplate[] = [
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

function templateByLabel(label: string): DecisionModelTemplate {
  const template = DECISION_MODEL_TEMPLATES.find((candidate) => candidate.label === label);
  if (!template) throw new Error(`no decision-model template expectation named '${label}'`);
  return template;
}

/** What `readQuestionNames` must show once `label` has auto-named the rule. */
function displayedNames(template: DecisionModelTemplate): string[] {
  return template.names.map((name) => name ?? template.label);
}

/**
 * The six decision-model template tabs (opik#9143, OPIK-8372), what each one
 * writes, and the Custom tab that keeps the author's own work while they try
 * a template.
 *
 * A template is a prompt plus a question list, and the editor shows both — but what
 * reaches the judge is the PERSISTED prompt, score names and variable map, and
 * only the prompt and the questions are on screen. So the apply is asserted
 * through the editor and the save through the API, which is the disagreement
 * worth catching: a template wired to another template's prompt, or one that applied
 * the first of its questions and dropped the rest, looks identical to a correct
 * one until the rule runs.
 *
 * `online-evaluation-jev-decision-model.spec.ts` already creates a rule from
 * the Task completion template, and deliberately does not cover this: it overwrites
 * the applied question with its own before saving, and asserts roles and score
 * TYPES rather than what the template put there. Both specs would stay green
 * for a Support reply template that lost two of its three questions.
 *
 * Deterministic and free. No provider call is ever made — the rules are created
 * and never scored — and a decisions model needs no provider key for the editor
 * to offer it, so nothing here depends on what the workspace already holds.
 */
test.describe(
  'Online Evaluation — decision model template tabs',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'every template applies its own prompt and its whole question list',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, page }) => {
        const rules = new OnlineEvaluationPage(page);

        await test.step('Open the online evaluation page on an empty project', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
        });

        for (const template of DECISION_MODEL_TEMPLATES) {
          // A fresh panel per template, so each assertion is about what the
          // template APPLIES and not about what survived the one before it.
          // Cancelled rather than saved: the save path is the next two tests.
          await test.step(`The "${template.label}" template applies its prompt and questions`, async () => {
            await rules.openCreateRulePanel('Decision model');
            // Untouched, so the template's own auto-naming is observable below.
            await expect(rules.nameInput, 'a new rule starts unnamed').toHaveValue('');

            await rules.chooseTemplateTab(template.label);

            await expect(
              rules.nameInput,
              'choosing a template names the rule after it',
            ).toHaveValue(template.label);
            expect(
              await rules.readPromptText(),
              `the "${template.label}" template's prompt`,
            ).toBe(template.prompt);
            // Compared as a whole list, not searched: a partial apply keeps the
            // first question and drops the rest, which a per-question check
            // would walk straight past.
            expect(
              await rules.readQuestions(),
              `the "${template.label}" template's questions, all of them and in order`,
            ).toEqual(template.questions);
            expect(
              await rules.readQuestionNames(),
              `the "${template.label}" template's score names`,
            ).toEqual(displayedNames(template));

            await rules.cancelPanel();
          });
        }
      },
    );

    test(
      'a rule saved from the Support reply template keeps all three of its questions',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const template = templateByLabel('Support reply');
        const ruleName = `${testNamespace}-support-reply`;
        const rules = new OnlineEvaluationPage(page);

        const ruleId = await test.step('Create a rule from the Support reply template', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanel('Decision model', template.label);
          // The template has just auto-named the rule after itself; this replaces
          // that with the run-namespaced name, which is what the main score
          // ends up being filed under and what the assertions below expect.
          await rules.nameInput.fill(ruleName);
          await expect(
            rules.questionInputs,
            'the panel being saved is showing all three questions',
          ).toHaveCount(template.questions.length);
          return rules.submitPanelAndCaptureRuleId();
        });

        await test.step('The stored prompt is the template\'s, as one user turn', async () => {
          const messages = await backendClient.getLlmJudgeMessages(ruleId);
          expect(messages, 'a decisions model takes a single message').toHaveLength(1);
          expect(messages[0].role, 'and it is a USER turn').toBe('USER');
          expect(messages[0].content, 'carrying the template\'s prompt').toBe(template.prompt);
        });

        await test.step('All three questions persist, keeping the names the template gave them', async () => {
          const { code } = await backendClient.getLlmJudgeRuleBody(ruleId);
          // The whole schema compared at once — every field of every entry.
          // Each question is one score, so a dropped question is a score the
          // judge never answers, and the names are what the feedback scores are
          // filed under; both are invisible in the editor after the save.
          expect(
            code.schema,
            'the three questions the template applied, as three BOOLEAN scores',
          ).toEqual([
            {
              // The main score follows the rule name as it is typed.
              name: ruleName,
              type: 'BOOLEAN',
              description: template.questions[0],
            },
            { name: 'Polite tone', type: 'BOOLEAN', description: template.questions[1] },
            { name: 'Escalation', type: 'BOOLEAN', description: template.questions[2] },
          ]);
        });

        await test.step('And the variable map is exactly the two the prompt names', async () => {
          const { code } = await backendClient.getLlmJudgeRuleBody(ruleId);
          expect(
            code.variables,
            'both of the template prompt\'s variables, and nothing else',
          ).toEqual({ input: 'input', output: 'output' });
        });

        await test.step('Reopening the rule shows the template\'s questions, on its tab', async () => {
          await expect(rules.ruleRow(ruleName)).toHaveCount(1);
          await rules.openEditRulePanelByName(ruleName);
          // The main score is now stored under the rule name, and the tab still
          // matches: the main score name is the rule's, not the template's.
          await expect(
            rules.templateTab(template.label),
            'a rule saved unchanged from a template reopens on that template',
          ).toHaveAttribute('aria-pressed', 'true');
          expect(await rules.readQuestions(), 'the questions hydrate back').toEqual(
            template.questions,
          );
          expect(await rules.readQuestionNames(), 'and so do their names').toEqual([
            ruleName,
            'Polite tone',
            'Escalation',
          ]);
          await rules.cancelPanel();
        });
      },
    );

    test(
      'a rule saved from an output-only template does not gain an input variable',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const template = templateByLabel('Moderation');
        const ruleName = `${testNamespace}-moderation`;
        const rules = new OnlineEvaluationPage(page);

        const ruleId = await test.step('Create a rule from the Moderation template', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanel('Decision model', template.label);
          await rules.nameInput.fill(ruleName);
          return rules.submitPanelAndCaptureRuleId();
        });

        await test.step('The stored prompt judges the output alone', async () => {
          const messages = await backendClient.getLlmJudgeMessages(ruleId);
          expect(messages, 'a decisions model takes a single message').toHaveLength(1);
          expect(messages[0].content, 'the output-only prompt').toBe(template.prompt);
        });

        await test.step('So the variable map names the output and nothing else', async () => {
          const { code } = await backendClient.getLlmJudgeRuleBody(ruleId);
          // The interesting half of this spec, and the reason an output-only
          // template is worth saving at all: the variable map is derived from the
          // prompt at submit time, so a map still carrying `input` would hand
          // the judge a field this template never asks about — and the editor
          // shows no variable map at all, so nothing on screen would say so.
          expect(
            code.variables,
            'an output-only template must not leak an input variable',
          ).toEqual({ output: 'output' });
          expect(code.schema, 'and it declares its one question').toEqual([
            { name: ruleName, type: 'BOOLEAN', description: template.questions[0] },
          ]);
        });
      },
    );

    test(
      'Custom keeps the author\'s own question while they try a template, and takes an edited template',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, page }) => {
        const OWN_QUESTION = 'Does the output offer the user a refund?';
        const EDITED_QUESTION = 'Does the output contain profanity?';
        const supportReply = templateByLabel('Support reply');
        const rules = new OnlineEvaluationPage(page);

        await test.step('Write a question of your own on the Custom tab', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanel('Decision model');
          await expect(
            rules.templateTab('Custom'),
            'a new rule opens on the Custom tab',
          ).toHaveAttribute('aria-pressed', 'true');
          await rules.mainQuestionInput.fill(OWN_QUESTION);
        });

        await test.step('A template replaces it on screen', async () => {
          await rules.chooseTemplateTab(supportReply.label);
          expect(await rules.readQuestions(), 'the template\'s questions').toEqual(
            supportReply.questions,
          );
        });

        await test.step('Custom gives it back, and drops the name the template set', async () => {
          await rules.chooseTemplateTab('Custom');
          // The whole list: a restore that merged the template into Custom
          // would keep the author's question and still be wrong.
          expect(await rules.readQuestions(), 'only the author\'s own question').toEqual([
            OWN_QUESTION,
          ]);
          await expect(rules.nameInput, 'the rule is unnamed again').toHaveValue('');
        });

        await test.step('An edit on a template tab moves the content to Custom', async () => {
          await rules.chooseTemplateTab('Moderation');
          await rules.mainQuestionInput.fill(EDITED_QUESTION);
          await expect(
            rules.templateTab('Custom'),
            'the edited template is now the Custom content',
          ).toHaveAttribute('aria-pressed', 'true');
          expect(await rules.readQuestions()).toEqual([EDITED_QUESTION]);
          await rules.cancelPanel();
        });
      },
    );
  },
);
