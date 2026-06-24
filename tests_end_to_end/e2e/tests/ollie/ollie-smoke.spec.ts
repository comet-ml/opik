import { test, expect } from '@e2e/fixtures';
import { OlliePage } from '@e2e/pom/ollie.page';

function skipIfOllieDisabled(envConfig: { features: { ollie: boolean } }): void {
  test.skip(!envConfig.features.ollie, 'Ollie is cloud/client-only (OLLIE_ENABLED off)');
}

test.describe('Ollie — smoke', { tag: ['@t1-smoke', '@ollie'] }, () => {
  test.beforeEach(({ envConfig }) => skipIfOllieDisabled(envConfig));

  test('Ollie surface mounts and reaches a ready state', async ({ project, page }) => {
    test.setTimeout(180_000);
    const ollie = new OlliePage(page, project.id);

    await test.step('Open Ollie and wait for it to be ready', async () => {
      await ollie.goto();
      await ollie.waitForReady();
    });

    await test.step('Ready state shows a usable input', async () => {
      await expect(ollie.inputTextbox()).toBeEnabled();
    });
  });
});

test.describe('Ollie — completion', { tag: ['@t2-cuj', '@ollie'] }, () => {
  test.beforeEach(({ envConfig }) => skipIfOllieDisabled(envConfig));

  test('Ollie returns a completion for a basic prompt', async ({ project, page }) => {
    test.setTimeout(240_000);
    const ollie = new OlliePage(page, project.id);

    await test.step('Open Ollie and start a fresh chat', async () => {
      await ollie.goto();
      await ollie.waitForReady();
      await ollie.startNewChat();
    });

    await test.step('Send a prompt and verify a non-empty reply renders', async () => {
      // Keep the prompt inside Ollie's domain — it is tuned to answer
      // Opik-related questions and may decline off-topic asks (e.g. arithmetic),
      // which would make an off-topic prompt a flaky completion signal.
      const reply = await ollie.sendMessageAndAwaitReply(
        'In one sentence, what is a trace in Opik?',
      );
      expect(reply.length).toBeGreaterThan(0);
      // At least the user echo and the assistant reply are now in the log.
      expect(await ollie.messages().count()).toBeGreaterThanOrEqual(2);
    });
  });
});
