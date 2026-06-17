import { test, expect } from '@e2e/fixtures';
import { OlliePage } from '@e2e/pom/ollie.page';

/**
 * Ollie — deeper agentic coverage (OPIK-6951), building on the initial smoke
 * coverage (OPIK-6950). Three flows beyond surface-mount + basic completion:
 *
 *   1. Sidebar surface  — the Ollie sidebar iframe mounts on a project page and
 *                          persists across navigation. Deterministic. @t1-smoke.
 *   2. Context awareness — the "Traces: N" badge reflects the seeded trace count
 *                          for the active project. @t2-cuj.
 *   3. /analyze flow     — running /analyze on a project with traces renders a
 *                          structured, non-empty response (directional). @t3-nightly.
 *
 * All cloud-only — Ollie does not exist on OSS/local. Ollie is a non-deterministic
 * LLM agent, so assertions are structural/directional, never on exact wording.
 */
function skipIfOllieDisabled(envConfig: { features: { ollie: boolean } }): void {
  test.skip(!envConfig.features.ollie, 'Ollie is cloud/client-only (OLLIE_ENABLED off)');
}

test.describe('Ollie — sidebar surface', { tag: ['@t1-smoke', '@ollie'] }, () => {
  test.beforeEach(({ envConfig }) => skipIfOllieDisabled(envConfig));

  test('Ollie sidebar mounts on a project page and persists across navigation', async ({
    project,
    page,
  }) => {
    test.setTimeout(180_000);
    const ollie = new OlliePage(page, project.id);

    await test.step('Open the Logs page and confirm the Ollie sidebar mounts', async () => {
      await ollie.gotoSidebarSurface('logs');
      await ollie.waitForSidebarReady();
      expect(await ollie.isMounted()).toBe(true);
    });

    await test.step('Navigate to Experiments and confirm the sidebar persists', async () => {
      await ollie.gotoSidebarSurface('experiments');
      // The host re-renders the page content but keeps the sidebar mounted; key
      // on the iframe being present + usable rather than a full cold re-provision.
      await ollie.waitForSidebarReady();
      expect(await ollie.isMounted()).toBe(true);
    });
  });
});

test.describe('Ollie — context awareness', { tag: ['@t2-cuj', '@ollie'] }, () => {
  test.beforeEach(({ envConfig }) => skipIfOllieDisabled(envConfig));

  const SEEDED_TRACES = 3;

  test('Ollie reflects the seeded trace count in its context badge', async ({
    project,
    sdkClient,
    page,
  }) => {
    test.setTimeout(240_000);
    const ollie = new OlliePage(page, project.id);

    await test.step(`Seed ${SEEDED_TRACES} traces via the Python SDK`, async () => {
      for (let i = 0; i < SEEDED_TRACES; i++) {
        await sdkClient.python.createTrace({
          project_name: project.name,
          name: `ctx-trace-${i}`,
          input: `question ${i}`,
          output: `answer ${i}`,
        });
      }
    });

    await test.step('Open Ollie and verify the "Traces: N" badge matches the seed', async () => {
      await ollie.goto();
      await ollie.waitForReady();
      // Trace ingestion is eventually consistent; contextTraceCount polls the
      // badge into visibility. Assert it reports the count we seeded.
      expect(await ollie.contextTraceCount()).toBe(SEEDED_TRACES);
    });
  });
});

test.describe('Ollie — /analyze flow', { tag: ['@t3-nightly', '@ollie'] }, () => {
  test.beforeEach(({ envConfig }) => skipIfOllieDisabled(envConfig));

  test('Ollie returns a structured response for /analyze on a project with traces', async ({
    project,
    sdkClient,
    page,
  }) => {
    test.setTimeout(300_000);
    const ollie = new OlliePage(page, project.id);

    await test.step('Seed a trace so /analyze has data to work with', async () => {
      await sdkClient.python.createTrace({
        project_name: project.name,
        name: 'analyze-trace',
        input: 'why did this fail?',
        output: 'timeout in the search tool',
      });
    });

    await test.step('Open Ollie and confirm the /analyze action is offered', async () => {
      await ollie.goto();
      await ollie.waitForReady();
      await expect(ollie.analyzeButton()).toBeVisible();
    });

    await test.step('Run /analyze and verify a non-empty response renders', async () => {
      const reply = await ollie.runAnalyze();
      expect(reply.length).toBeGreaterThan(0);
      // User-initiated /analyze plus Ollie's response are now in the log.
      expect(await ollie.messages().count()).toBeGreaterThanOrEqual(1);
    });
  });
});
