import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

test.describe('Trace spans — panel depth', { tag: ['@t2-cuj', '@trace-explore'] }, () => {
  test('Span tree renders the seeded nested hierarchy with working expand/collapse', async ({
    tracedAgent,
    project,
    page,
  }) => {
    const logs = new LogsPage(page);

    const panel = await test.step('Open the seeded multi-span trace', async () => {
      await logs.goto(project.id);
      const panel = await logs.openTraceById(tracedAgent.id);
      await panel.waitForFullyLoaded();
      return panel;
    });

    await test.step('Verify the span count and the nested tree nodes', async () => {
      await expect(panel.spansCountLabel(tracedAgent.spanCount)).toBeVisible();
      // Root span and its two children are all visible in the tree.
      await expect(panel.spanTreeNode('plan')).toBeVisible();
      await expect(panel.spanTreeNode('llm-call')).toBeVisible();
      await expect(panel.spanTreeNode('search-tool')).toBeVisible();
    });

    await test.step('Collapsing the root span hides its children, expanding restores them', async () => {
      await panel.collapseSpan('plan');
      await expect(panel.spanTreeNode('llm-call')).toBeHidden();
      await expect(panel.spanTreeNode('search-tool')).toBeHidden();

      await panel.expandSpan('plan');
      await expect(panel.spanTreeNode('llm-call')).toBeVisible();
      await expect(panel.spanTreeNode('search-tool')).toBeVisible();
    });
  });

  test('Selecting the LLM span shows its model, token usage, and cost', async ({
    tracedAgent,
    project,
    page,
  }) => {
    const logs = new LogsPage(page);

    const panel = await test.step('Open the trace and select the LLM span', async () => {
      await logs.goto(project.id);
      const panel = await logs.openTraceById(tracedAgent.id);
      await panel.waitForFullyLoaded();
      await panel.selectSpan(tracedAgent.llmSpan.name);
      return panel;
    });

    await test.step('Verify the LLM span detail surfaces model, tokens, and cost', async () => {
      await expect(panel.spanModelChip).toContainText(tracedAgent.llmSpan.model);
      // Token usage renders the seeded total in the Token usage section. Anchor
      // to the whole line so it doesn't also match `original_usage.total_tokens`.
      await expect(
        panel.panelText(new RegExp(`^total_tokens: ${tracedAgent.llmSpan.totalTokens}$`)),
      ).toBeVisible();
      // The seeded cost is small; the UI rounds it to "<$0.01" rather than $0.
      await expect(panel.panelText('<$0.01').first()).toBeVisible();
    });
  });

  test('A tag can be added to a trace from the panel and removed', async ({
    tracedAgent,
    project,
    page,
  }) => {
    const logs = new LogsPage(page);
    const newTag = 'reviewed';

    const panel = await test.step('Open the trace', async () => {
      await logs.goto(project.id);
      const panel = await logs.openTraceById(tracedAgent.id);
      await panel.waitForFullyLoaded();
      return panel;
    });

    await test.step('Add a tag and verify the chip appears', async () => {
      // Seeded tags are already present; the new one is added alongside them.
      await expect(panel.tagChip(tracedAgent.tags[0])).toBeVisible();
      await panel.addTag(newTag);
      await expect(panel.tagChip(newTag)).toBeVisible();
    });
  });

  test('A manual feedback score can be added, edited, and deleted from the Annotate panel', async ({
    tracedAgent,
    feedbackDefinition,
    project,
    page,
  }) => {
    const logs = new LogsPage(page);
    const scoreName = feedbackDefinition.name;

    const panel = await test.step('Open the trace and the Annotate panel', async () => {
      await logs.goto(project.id);
      const panel = await logs.openTraceById(tracedAgent.id);
      await panel.waitForFullyLoaded();
      await panel.openAnnotate();
      return panel;
    });

    await test.step('Add a score and verify it renders', async () => {
      await panel.setAnnotateScore(scoreName, 0.7);
      await expect.poll(() => panel.readFeedbackScoreTagValue(scoreName)).toBe('0.7');
    });

    await test.step('Edit the score and verify the new value', async () => {
      await panel.setAnnotateScore(scoreName, 0.3);
      await expect.poll(() => panel.readFeedbackScoreTagValue(scoreName)).toBe('0.3');
    });

    await test.step('Delete the score and verify the tag disappears', async () => {
      await panel.clearAnnotateScore(scoreName);
      await expect(panel.feedbackScoreTag(scoreName)).toBeHidden();
    });
  });
});
