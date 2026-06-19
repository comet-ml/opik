import * as path from 'node:path';
import { test, expect } from '@e2e/fixtures';
import { AgentPlaygroundPage } from '@e2e/pom/agent-playground.page';
import { startEndpointRunner, type EndpointRunner } from '@e2e/core/local-runner/connect';

const AGENTS_DIR = path.resolve(__dirname, '../../agents');

test.describe('Agent Playground — Local Runner', { tag: ['@t3-nightly', '@agent-playground'] }, () => {
  test('drives a connected entrypoint agent from the Agent Playground', async ({
    project,
    scratchDir,
    envConfig,
    page,
  }) => {
    test.setTimeout(300_000);
    const playground = new AgentPlaygroundPage(page, project.id);
    let runner: EndpointRunner | null = null;

    try {
      await test.step('Clone the entrypoint golden agent into a scratch dir', async () => {
        await scratchDir.clone(path.join(AGENTS_DIR, 'playground-entrypoint'));
      });

      // OSS local stack accepts any API key (no auth wall); the cloud envs mint a real
      // one via globalSetup and propagate it through OPIK_API_KEY.
      const apiKey = envConfig.apiKey ?? 'opik-local-key';

      runner = await test.step('Start opik endpoint against the scratch agent', async () =>
        startEndpointRunner({
          projectName: project.name,
          projectId: project.id,
          workspace: envConfig.workspace,
          apiKey,
          cwd: scratchDir.path,
          command: ['python', path.join(scratchDir.path, 'agent.py')],
          apiBaseUrl: envConfig.apiBaseUrl,
        }));

      await test.step('Navigate to Agent Playground', async () => {
        await playground.goto();
        await playground.waitForHeading();
        await expect(
          page.getByRole('heading', { name: 'Agent playground', level: 1 }),
        ).toBeVisible();
      });

      await test.step('Wait for connected state', async () => {
        await expect(playground.connectionBadge()).toBeVisible({ timeout: 60_000 });
        await expect(playground.testInputPanel()).toBeVisible({ timeout: 60_000 });
      });

      const query = 'capital of france';

      await test.step('Fill input and run', async () => {
        await playground.fillInput('query', query);
        await playground.clickRun();
      });

      await test.step('Wait for result', async () => {
        await expect(playground.runningIndicator()).toHaveCount(0, { timeout: 180_000 });
        await expect(playground.resultText(query)).toBeVisible({ timeout: 30_000 });
      });
    } finally {
      runner?.stop();
    }
  });
});
