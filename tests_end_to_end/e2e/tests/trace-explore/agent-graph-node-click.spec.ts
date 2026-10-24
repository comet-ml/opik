import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Clicking an agent-graph node opens the span it ran as. Rendered by the real
 * Mermaid build, so a Mermaid upgrade that changes the SVG's element ids fails
 * here rather than in production.
 */
test.describe('Trace panel — agent graph', { tag: ['@t1-smoke', '@area:traces'] }, () => {
  test.setTimeout(300_000);

  test(
    'clicking a graph node selects its span',
    { tag: ['@cap:traces.agent-graph-tab'] },
    async ({ agentGraphTrace, project, page }) => {
      const logs = new LogsPage(page);

      const panel = await test.step('Open the seeded trace', async () => {
        await logs.goto(project.id);
        const panel = await logs.openTraceById(agentGraphTrace.traceId);
        await panel.waitForFullyLoaded();
        return panel;
      });

      await test.step('Every graph node renders as clickable', async () => {
        for (const node of ['Orchestrator', 'Researcher', 'Planner', 'Booking']) {
          await expect(panel.agentGraphNode(node), `graph node ${node} renders`).toBeVisible();
          await expect(
            panel.agentGraphNode(node),
            `graph node ${node} matches its span and shows a pointer`,
          ).toHaveCSS('cursor', 'pointer');
        }
      });

      for (const node of ['Planner', 'Researcher'] as const) {
        await test.step(`Clicking ${node} selects the ${node} span`, async () => {
          await panel.agentGraphNode(node).click();
          await expect(page).toHaveURL(
            (url) => url.searchParams.get('span') === agentGraphTrace.spanIdOf[node],
          );
        });
      }
    },
  );
});
