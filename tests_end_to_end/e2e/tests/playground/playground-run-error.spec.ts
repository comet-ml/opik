import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { PlaygroundLogsSidebarPage } from '@e2e/pom/playground-logs-sidebar.page';

/**
 * OPIK-7963: a playground run that fails must be recorded as failed. Before that change the
 * three error channels the completions proxy can answer on were folded into the output string
 * only, so a failed run was indistinguishable from a successful one in the Logs table, the
 * error-rate KPI and any error_type filter.
 *
 * The provider is seeded unreachable rather than forced to a status: the connection is refused
 * before a request is written, so the failure needs no mock gateway and no provider key, and
 * arrives on the same channel a real unreachable provider would use.
 */
/**
 * The channel the playground assigns when the completions proxy itself reports the failure,
 * which is what an unreachable provider produces: the connection never carries a provider
 * response to relay.
 */
const UNREACHABLE_EXCEPTION_TYPE = 'OpikError';

test.describe('Playground — failed run', { tag: ['@t2-cuj', '@area:playground', '@cap:playground.verify-trace-from-run', '@cap:playground.run-error-info'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  test('A run against an unreachable provider is logged as an errored trace', async ({
    page,
    project,
    providerKeys,
    testNamespace,
  }) => {
    test.setTimeout(120_000);

    const systemPrompt = 'You are a concise assistant.';
    const userPrompt = 'Say hello in exactly one word.';

    const modelId = await test.step('Seed an unreachable custom provider', async () => {
      return providerKeys.createUnreachable({
        providerName: `${testNamespace}-unreachable`,
        modelName: `${testNamespace}-dead-model`,
      });
    });

    const playground = new PlaygroundPage(page, project.id);

    await test.step('Compose and run against the unreachable model', async () => {
      await playground.goto();
      await playground.waitForReady();
      await playground.configureVariant(0, {
        systemPrompt,
        userPrompt,
        modelDisplayName: modelId.split('/').pop(),
      });

      const traceLogged = page.waitForResponse(
        (r) => r.url().includes('/traces/batch') && r.ok(),
        { timeout: 60_000 },
      );
      await playground.runFreeMode(60_000);
      await traceLogged;
    });

    const sidebar = new PlaygroundLogsSidebarPage(page);

    await test.step('Open the logged trace', async () => {
      await playground.openLogsPanel();
      await sidebar.waitForOpen();
      await sidebar.waitForTraceRow(15_000);
      await sidebar.openFirstTrace();
    });

    await test.step('Verify the trace is marked as errored', async () => {
      await expect(sidebar.errorCallout()).toBeVisible();
      await sidebar.expandErrorCallout();
      // The channel label is ours, so it is safe to pin; the message is the backend's copy.
      await expect(sidebar.errorCalloutBody()).toContainText(`exception_type: ${UNREACHABLE_EXCEPTION_TYPE}`);
    });

    const assistantText = await test.step('Verify the run output carries the failure text', async () => {
      await expect(sidebar.messagesTab()).toBeVisible();
      await sidebar.expectMessagesTabSelectedByDefault();
      await sidebar.clickMessagesTab();
      await expect(sidebar.messageRole('Assistant')).toBeVisible();

      const text = (await sidebar.messageBody('Assistant').innerText()).trim();
      expect(text, 'Expected the assistant turn to carry the failure text').not.toBe('');
      return text;
    });

    await test.step('Verify the error block reports that same text', async () => {
      const errorText = await sidebar.errorCalloutBody().innerText();
      // Compared rather than pinned: the wording is the backend's. Both sides are
      // whitespace-normalized because the error block line-folds long scalars, and the
      // doubled apostrophes YAML uses to escape one are collapsed back.
      const normalize = (value: string) =>
        value.replace(/''/g, "'").replace(/\s+/g, ' ').trim();
      expect(normalize(errorText)).toContain(normalize(assistantText));
    });
  });
});
