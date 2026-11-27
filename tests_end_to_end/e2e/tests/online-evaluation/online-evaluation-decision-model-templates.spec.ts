import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

const INPUT_AND_OUTPUT = 'INPUT:\n{{input}}\n\nOUTPUT:\n{{output}}';
const OUTPUT_ONLY = 'OUTPUT:\n{{output}}';

interface DecisionModelTemplate {
  label: string;
  prompt: string;
  questions: string[];
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

test.describe(
  'Online Evaluation — decision model templates',
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
          await test.step(`The "${template.label}" template applies its prompt and questions`, async () => {
            await rules.openCreateRulePanel('Decision model');
            await expect(rules.nameInput, 'a new rule starts unnamed').toHaveValue('');

            await rules.chooseTemplate(template.label);

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

        await test.step('Reopening the rule shows the template\'s questions, and marks its template', async () => {
          await expect(rules.ruleRow(ruleName)).toHaveCount(1);
          await rules.openEditRulePanelByName(ruleName);
          expect(await rules.readQuestions(), 'the questions hydrate back').toEqual(
            template.questions,
          );
          expect(await rules.readQuestionNames(), 'and so do their names').toEqual([
            ruleName,
            'Polite tone',
            'Escalation',
          ]);
          await rules.openTemplates();
          await expect(
            rules.templateCard(template.label),
            'a rule saved unchanged from a template is marked as that template',
          ).toHaveAttribute('aria-pressed', 'true');
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
      'Ctrl+Z steps back through a template pick, a tab switch and typing',
      { tag: ['@cap:online-evaluation.create-llm-judge-rule'] },
      async ({ project, page }) => {
        const OWN_QUESTION = 'Does the output offer the user a refund?';
        const supportReply = templateByLabel('Support reply');
        const rules = new OnlineEvaluationPage(page);

        await test.step('Write a question of your own', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRulePanel('Decision model');
          await rules.mainQuestionInput.fill(OWN_QUESTION);
        });

        await test.step('A template replaces it on screen', async () => {
          await rules.chooseTemplate(supportReply.label);
          expect(await rules.readQuestions(), 'the template\'s questions').toEqual(
            supportReply.questions,
          );
        });

        await test.step('Undo steps back through the template pick, then the tab switch', async () => {
          await page.keyboard.press('ControlOrMeta+z');
          await expect(rules.startCards, 'the first undo returns to the Templates tab').toHaveCount(1);
          await expect(rules.nameInput, 'the rule is unnamed again').toHaveValue('');
          await page.keyboard.press('ControlOrMeta+z');
          await expect
            .poll(() => rules.readQuestions(), { message: 'only the author\'s own question' })
            .toEqual([OWN_QUESTION]);
        });

        await test.step('The next undo removes the typed question', async () => {
          await page.keyboard.press('ControlOrMeta+z');
          await expect
            .poll(() => rules.readQuestions(), { message: 'the question is empty again' })
            .toEqual(['']);
          await rules.cancelPanel();
        });
      },
    );
  },
);
