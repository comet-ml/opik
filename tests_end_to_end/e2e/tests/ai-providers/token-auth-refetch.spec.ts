import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { mockAuthRevokeAll, mockAuthStats } from '@e2e/core/mock-auth';

/**
 * Reactive token refetch (OPIK-7940): when the gateway rejects a cached bearer
 * (revoked server-side while still fresh in Opik's cache), the backend must
 * invalidate, fetch a new token, and retry once — invisibly to the user.
 *
 * Clock-free by design: revocation is forced via the mock's /revoke hook and the
 * refetch is asserted through /stats deltas (counts, not timing), scoped to this
 * test's unique model name so parallel specs sharing the mock can't interfere.
 * /revoke itself is global, which is benign: a concurrent spec swept up by it just
 * exercises the same transparent retry and still passes.
 */
test.describe('AI Providers — OAuth2 reactive token refetch', { tag: ['@t1-smoke', '@area:configuration'] }, () => {
  test('a revoked token is transparently refetched and the request retried', { tag: ['@cap:configuration.ai-provider-token-refetch'] }, async ({
    page,
    project,
    testNamespace,
    providerKeys,
  }) => {
    test.setTimeout(180_000);

    const providerName = `${testNamespace}-oauth-refetch`;
    // unique model name so parallel specs can't collide in the model selector
    const modelName = `${testNamespace}-mock-model`;

    await providerKeys.createOauth({ providerName, modelName });

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

    const refusedKey = `chat_refused_unknown:${modelName}`;

    // Under parallel load the backend may legitimately degrade to a direct, uncached
    // token fetch (Redis lock contention) — then a revocation has no cached bearer to
    // expose and no refusal occurs even though behavior is correct. Retry the
    // revoke -> prompt cycle until a run genuinely presents a stale bearer.
    let observedRefusal = false;
    for (let cycle = 1; cycle <= 3 && !observedRefusal; cycle++) {
      observedRefusal = await test.step(`Revoke, rerun, and check for a refused stale bearer (cycle ${cycle})`, async () => {
        const before = (await mockAuthStats())[refusedKey] ?? 0;
        await mockAuthRevokeAll();

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

        // poll: the SSE stream can render before the mock finishes accounting
        const deadline = Date.now() + 5_000;
        while (Date.now() < deadline) {
          if (((await mockAuthStats())[refusedKey] ?? 0) > before) {
            return true;
          }
          await new Promise((resolve) => setTimeout(resolve, 250));
        }
        return false;
      });
    }

    expect(
      observedRefusal,
      'a revoked bearer was presented, refused, and transparently retried within 3 cycles',
    ).toBe(true);
  });
});
