import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * One judge prompt per shape that makes the read-back mapper try, and fail, to
 * read a prose prompt as multimodal content parts.
 *
 * `AutomationModelEvaluatorMapper` infers the stored shape from the content
 * string, because a message's content is persisted as a plain String whether
 * the author typed prose or the UI built a content array. A leading `[` is the
 * only hint it has. Every row below is prose a human would plausibly type that
 * happens to start with one — and each reaches a different branch of the
 * fallback, which is why they are separate rules rather than one representative
 * string.
 *
 * The estate structurally cannot produce these through the UI: the create-rule
 * dialog only emits the canned templates and whatever a test types into them,
 * and no template opens with a bracket. That is why this spec seeds through the
 * REST boundary rather than driving the dialog.
 */
interface JsonLookingPrompt {
  /** Used in the rule name and in every failure message. */
  label: string;
  /** Which branch of the mapper's fallback this shape lands in. */
  why: string;
  content: string;
}

const OUTPUT_TAIL = '\n\nOUTPUT:\n{{output}}';

const JSON_LOOKING_PROMPTS: JsonLookingPrompt[] = [
  {
    label: 'source-text',
    why: 'not JSON at all — the customer prompt in OPIK-8250',
    content: `[Source Text] Grade the answer against the source above.${OUTPUT_TAIL}`,
  },
  {
    label: 'number-array',
    why: 'valid JSON, but the elements are numbers rather than content parts',
    content: `[1, 2] are the only scores you may return.${OUTPUT_TAIL}`,
  },
  {
    label: 'null-element',
    why: 'valid JSON array whose element is null, so no element can declare a type',
    content: `[null] means no reference answer was supplied.${OUTPUT_TAIL}`,
  },
  {
    label: 'empty-object',
    why: 'valid JSON object element carrying no `type` discriminator',
    content: `[{}] is the empty rubric — grade on correctness alone.${OUTPUT_TAIL}`,
  },
  {
    label: 'empty-array',
    why: 'parses, but an empty content list is prose, not a renderable message',
    content: `[] is the empty rubric — grade on correctness alone.${OUTPUT_TAIL}`,
  },
  {
    label: 'array-then-prose',
    why: 'opens with a well-formed content part and then continues in prose',
    content: `[{"type": "text", "text": "example"}] Now grade the output above.${OUTPUT_TAIL}`,
  },
];

/** An ordinary prose prompt. Nothing about it is unusual — that is its job. */
const CONTROL_PROMPT = `You are a strict grader. Score the answer below out of 1.${OUTPUT_TAIL}`;

test.describe('Online Evaluation — judge prompts that look like JSON', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('A project holding bracket-opening judge prompts still lists every rule, over the API and in the UI', { tag: ['@cap:online-evaluation.list-rules'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    const controlName = `${testNamespace}-control`;
    const seededNames = [
      controlName,
      ...JSON_LOOKING_PROMPTS.map((p) => `${testNamespace}-${p.label}`),
    ];

    await test.step(
      `Seed a prose control rule plus ${JSON_LOOKING_PROMPTS.length} bracket-opening ones`,
      async () => {
        // The control is what makes a green listing meaningful. The failure this
        // guards was collateral: one unreadable rule took down the listing for
        // every OTHER rule in the project too, so a spec that seeded only the
        // suspect shapes could not tell "the listing survived" from "the listing
        // returned nothing".
        await backendClient.createLlmJudgeRule({
          projectId: project.id,
          name: controlName,
          messages: [{ role: 'USER', content: CONTROL_PROMPT }],
        });
        for (const prompt of JSON_LOOKING_PROMPTS) {
          await backendClient.createLlmJudgeRule({
            projectId: project.id,
            name: `${testNamespace}-${prompt.label}`,
            messages: [{ role: 'USER', content: prompt.content }],
          });
        }
      },
    );

    await test.step('The project listing answers 200 and carries every seeded rule', async () => {
      const listing = await backendClient.findAutomationRuleEvaluatorsPage({
        projectId: project.id,
      });
      expect(
        listing.status,
        'a single unreadable rule used to turn this whole page into a 500',
      ).toBe(200);
      // The project is created by the fixture, so its rules are exactly the ones
      // seeded above: assert the whole collection, not that ours are somewhere
      // in it. A listing that dropped the unreadable rows rather than 500-ing
      // would pass a containment check and fail this one.
      expect(listing.names.slice().sort(), 'every seeded rule is listed').toEqual(
        seededNames.slice().sort(),
      );
      expect(listing.total, 'and the server agrees on the count').toBe(seededNames.length);
    });

    await test.step('The workspace-wide listing answers 200 as well', async () => {
      // The unscoped read is the one the online-scoring sampler issues per trace
      // batch, and it is shared by every project in the workspace — so an
      // unreadable rule in one project used to stop sampling for all of them.
      // Only the status is asserted: the workspace holds thousands of rules from
      // other runs, so its collection is not this test's to pin down.
      const workspaceWide = await backendClient.findAutomationRuleEvaluatorsPage();
      expect(
        workspaceWide.status,
        'the sampler-shared listing must not 500 because one rule is unreadable',
      ).toBe(200);
      expect(
        workspaceWide.total,
        'and it must actually return rules, not an empty 200',
      ).toBeGreaterThanOrEqual(seededNames.length);
    });

    await test.step('The rules page renders a row for every rule, with no 5xx on the wire', async () => {
      // Scoped to the evaluators endpoint on purpose. The page also fires
      // unrelated background requests that answer 403/404 regardless of rule
      // content (datasets/export-jobs, agent-configs, agent-insights); failing
      // on those would make this spec a monitor for someone else's endpoint.
      const evaluatorServerErrors: string[] = [];
      page.on('response', (res) => {
        if (res.url().includes('/automations/evaluators') && res.status() >= 500) {
          evaluatorServerErrors.push(`${res.status()} ${res.request().method()} ${res.url()}`);
        }
      });

      const onlineEval = new OnlineEvaluationPage(page);
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();

      for (const name of seededNames) {
        await expect(onlineEval.ruleRow(name), `row for ${name}`).toHaveCount(1);
      }
      await expect(
        onlineEval.ruleRows,
        'the list shows every seeded rule and nothing else',
      ).toHaveCount(seededNames.length);
      expect(evaluatorServerErrors, 'no 5xx from the evaluators endpoint').toEqual([]);
    });
  });
});
