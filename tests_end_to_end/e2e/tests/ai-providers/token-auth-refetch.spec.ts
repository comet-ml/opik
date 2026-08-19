import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import {
  MOCK_AUTH_CLIENT_ID,
  MOCK_AUTH_CLIENT_SECRET,
  mockAuthRevokeAll,
  mockAuthStats,
  mockGatewayUrlForBackend,
  mockTokenUrlForBackend,
} from '@e2e/core/mock-auth';
import { createProviderKey, deleteProviderKeyByName } from '@e2e/core/provider-keys';

/**
 * Reactive token refetch (OPIK-7940): when the gateway rejects a cached bearer
 * (revoked server-side while still fresh in Opik's cache), the backend must
 * invalidate, fetch a new token, and retry once — invisibly to the user.
 *
 * Clock-free by design: revocation is forced via the mock's /revoke hook and the
 * refetch is asserted through /stats deltas (counts, not timing). The mock's
 * counters are global across the run, so assertions are >= deltas.
 */
test.describe('AI Providers — OAuth2 reactive token refetch', { tag: ['@t1-smoke', '@area:ai-providers'] }, () => {
  test('a revoked token is transparently refetched and the request retried', async ({
    page,
    project,
    testNamespace,
  }) => {
    test.setTimeout(180_000);

    const providerName = `${testNamespace}-oauth-refetch`;
    // unique model name so parallel specs can't collide in the model selector
    const modelName = `${testNamespace}-mock-model`;

    try {
      await test.step('Seed an OAuth2 provider via REST', async () => {
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: mockGatewayUrlForBackend,
          configuration: { models: `custom-llm/${providerName}/${modelName}` },
          auth_config: {
            token_url: mockTokenUrlForBackend,
            send_as: 'basic',
            credentials: [
              { key: 'grant_type', value: 'client_credentials', secret: false },
              { key: 'client_id', value: MOCK_AUTH_CLIENT_ID, secret: false },
              { key: 'client_secret', value: MOCK_AUTH_CLIENT_SECRET, secret: true },
            ],
          },
        });
      });

      const playground = new PlaygroundPage(page, project.id);

      await test.step('First Playground run fetches a token and succeeds', async () => {
        await playground.goto();
        await playground.waitForReady();
        const result = await playground.runSimplePromptAndAwaitResponse({
          modelDisplayName: modelName,
          prompt: 'Say hello.',
          timeoutMs: 60_000,
        });
        expect(result.isError, 'no error indicator').toBe(false);
        expect(result.outputText.length, 'non-empty response').toBeGreaterThan(0);
      });

      const before = await test.step('Revoke every issued token at the gateway', async () => {
        const stats = await mockAuthStats();
        await mockAuthRevokeAll();
        return stats;
      });

      await test.step('Second run still succeeds — Opik retried with a fresh token', async () => {
        // fresh navigation: rerunning on the same page can satisfy the output wait
        // with the previous run's still-rendered response, skipping the request
        await playground.goto();
        await playground.waitForReady();
        const result = await playground.runSimplePromptAndAwaitResponse({
          modelDisplayName: modelName,
          prompt: 'Say hello again.',
          timeoutMs: 60_000,
        });
        expect(result.isError, 'no error indicator after revocation').toBe(false);
        expect(result.outputText.length, 'non-empty response after revocation').toBeGreaterThan(0);
      });

      await test.step('The gateway saw the stale bearer refused and a new token issued', async () => {
        // poll: the SSE stream can render before the mock finishes accounting
        await expect
          .poll(async () => (await mockAuthStats()).chat_refused_unknown ?? 0, {
            timeout: 10_000,
          })
          .toBeGreaterThan(before.chat_refused_unknown ?? 0);
        const after = await mockAuthStats();
        expect(after.tokens_issued ?? 0).toBeGreaterThan(before.tokens_issued ?? 0);
        expect(after.chat_ok ?? 0).toBeGreaterThan(before.chat_ok ?? 0);
      });
    } finally {
      await deleteProviderKeyByName(providerName);
    }
  });
});
