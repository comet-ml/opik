import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * A prose judge prompt that opens with a well-formed content part and then
 * keeps going.
 *
 * This is the shape that lost data rather than merely erroring: the old reader
 * stopped at the first complete JSON array, so everything after
 * `[{"type": "text", …}]` — the instruction the prompt actually exists for —
 * never came back, and the next save wrote the truncation down. The trailing
 * `Now grade the output above.` is therefore not decoration; it is the payload
 * of the assertion.
 */
const ARRAY_THEN_PROSE_PROMPT =
  '[{"type": "text", "text": "example"}] Now grade the output above.\n\nOUTPUT:\n{{output}}';

/** A genuinely multimodal message, for the opposite regression. */
const STRUCTURED_TEXT = 'Grade the answer against the screenshot.\n\nOUTPUT:\n{{output}}';
const STRUCTURED_IMAGE_URL = 'https://example.com/reference-screenshot.png';
const STRUCTURED_IMAGE_DETAIL = 'low';

test.describe('Online Evaluation — judge prompt round trip', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('An example-array-then-prose prompt survives the edit dialog and a re-save byte for byte', { tag: ['@cap:online-evaluation.edit-rule'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    const ruleName = `${testNamespace}-array-then-prose`;

    const ruleId = await test.step('Seed the judge rule through the API', async () => {
      // Seeded over REST because the create dialog cannot produce this string:
      // it only emits the canned templates, none of which opens with a bracket.
      return backendClient.createLlmJudgeRule({
        projectId: project.id,
        name: ruleName,
        messages: [{ role: 'USER', content: ARRAY_THEN_PROSE_PROMPT }],
      });
    });

    await test.step('The API reads the prompt back as the exact string that was written', async () => {
      const messages = await backendClient.getLlmJudgeMessages(ruleId);
      expect(messages, 'the rule has exactly the one seeded message').toHaveLength(1);
      const [message] = messages;
      expect(
        message.content,
        'prose that merely opens with a content part is prose, and must come back whole',
      ).toBe(ARRAY_THEN_PROSE_PROMPT);
      expect(
        message.contentArray,
        'and it must not be promoted to structured content',
      ).toBeNull();
    });

    const onlineEval = new OnlineEvaluationPage(page);

    await test.step('The edit dialog hydrates the prompt with the trailing instruction intact', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await onlineEval.openEditDialogByName(ruleName);
      expect(
        await onlineEval.readPromptMessageText('user'),
        'the dialog shows what the author typed, not a prefix of it',
      ).toBe(ARRAY_THEN_PROSE_PROMPT);
    });

    await test.step('Saving the dialog unchanged persists the prompt unchanged', async () => {
      // The read bug only became data loss here: the dialog writes back whatever
      // it was given, so a truncated read submitted once was a truncated prompt
      // for good. Submitting without touching anything is exactly the gesture a
      // user makes when they open a rule to change its sampling rate.
      await onlineEval.submitDialog();

      await onlineEval.openEditDialogByName(ruleName);
      expect(
        await onlineEval.readPromptMessageText('user'),
        'a no-op save must not shorten the prompt',
      ).toBe(ARRAY_THEN_PROSE_PROMPT);
      await onlineEval.cancelDialog();
    });

    await test.step('And the server still holds it as a plain string', async () => {
      const messages = await backendClient.getLlmJudgeMessages(ruleId);
      expect(messages, 'the save must not have added or dropped a message').toHaveLength(1);
      expect(messages[0].content, 'byte-identical after the round trip').toBe(
        ARRAY_THEN_PROSE_PROMPT,
      );
      expect(messages[0].contentArray, 'still not structured content').toBeNull();
    });
  });

  test('A genuinely multimodal judge message still reads back as structured content', { tag: ['@cap:online-evaluation.edit-rule'] }, async ({
    project,
    backendClient,
    testNamespace,
    automationRulesCleanup,
  }) => {
    // The guard against over-correcting the fix above. Falling back to "it is
    // all just a string" for anything hard to parse would make the truncation
    // test pass while quietly destroying every real multimodal rule — the
    // image part would come back as JSON text in the prompt box and the judge
    // would stop seeing the image.
    //
    // API-level throughout: the dialog renders only the text part of a
    // multimodal message, so the image URL and its `detail` — the fields that
    // would be lost — are not observable in the UI at all.
    const ruleName = `${testNamespace}-structured-content`;

    const ruleId = await test.step('Seed a judge rule with a text part and an image part', async () =>
      backendClient.createLlmJudgeRule({
        projectId: project.id,
        name: ruleName,
        messages: [
          {
            role: 'USER',
            contentArray: [
              { type: 'text', text: STRUCTURED_TEXT },
              {
                type: 'image_url',
                image_url: { url: STRUCTURED_IMAGE_URL, detail: STRUCTURED_IMAGE_DETAIL },
              },
            ],
          },
        ],
      }));

    const assertStructured = async (when: string): Promise<void> => {
      const messages = await backendClient.getLlmJudgeMessages(ruleId);
      expect(messages, `${when}: exactly the one seeded message`).toHaveLength(1);
      const [message] = messages;
      expect(message.content, `${when}: must not be flattened into a string`).toBeNull();
      expect(message.contentArray, `${when}: content parts survive`).toEqual([
        { type: 'text', text: STRUCTURED_TEXT, imageUrl: null },
        {
          type: 'image_url',
          text: null,
          imageUrl: { url: STRUCTURED_IMAGE_URL, detail: STRUCTURED_IMAGE_DETAIL },
        },
      ]);
    };

    await test.step('The API reads it back as content parts, url and detail intact', async () =>
      assertStructured('on first read'));

    await test.step('A re-save of exactly what was read leaves it structured', async () => {
      await backendClient.resaveAutomationRuleFromReadBack(ruleId, project.id);
      await assertStructured('after a no-op re-save');
    });
  });
});
