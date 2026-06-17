import * as path from 'node:path';
import * as fs from 'node:fs/promises';
import { test, expect } from '@e2e/fixtures';
import { OlliePage } from '@e2e/pom/ollie.page';
import { startConnectRunner, type ConnectRunner } from '@e2e/core/local-runner/connect';
import {
  evaluatePassRate,
  seedFailingTraces,
  KNOWN_FAILING_DIR,
} from '@e2e/agents/known-failing/harness';

/**
 * Ollie — Local Runner flows (OPIK-6951 #4/#5; supersedes the abandoned
 * OPIK-6118/6119/6131/6132 sketches). These drive the heavy, fixture-dependent
 * agentic loops that depend on a paired `opik connect` runner:
 *
 *   - /instrument: connect an UNinstrumented golden agent, run /instrument,
 *     then assert the agent source gained opik imports + @track decorators.
 *   - /improve pass-rate loop: connect the known-failing golden agent, run
 *     /improve, and assert the eval-suite pass rate moves in the right direction.
 *
 * Cloud-only and slow (minutes): pairing + an approval-gated LLM agent loop.
 * Tagged @t3-nightly so they never gate the faster tiers. Ollie is
 * non-deterministic — assertions are structural (trace shape) and directional
 * (pass rate up), never on wording or exact values.
 *
 * Each test runs `opik connect` as a child process that watches a *scratch
 * clone* of the golden agent, so Ollie's edits never touch the committed
 * fixture. The connect daemon is always stopped in a finally block.
 */
function skipIfOllieDisabled(envConfig: { features: { ollie: boolean }; apiKey: string | null }): void {
  test.skip(!envConfig.features.ollie, 'Ollie is cloud/client-only (OLLIE_ENABLED off)');
  test.skip(!envConfig.apiKey, 'Local Runner pairing needs an OPIK_API_KEY');
}

const AGENTS_DIR = path.resolve(__dirname, '../../agents');

test.describe('Ollie — Local Runner', { tag: ['@t3-nightly', '@ollie'] }, () => {
  test.beforeEach(({ envConfig }) => skipIfOllieDisabled(envConfig));

  test('/instrument adds Opik tracing to a connected uninstrumented agent', async ({
    project,
    scratchDir,
    envConfig,
    page,
  }) => {
    test.setTimeout(600_000);
    const ollie = new OlliePage(page, project.id);
    let runner: ConnectRunner | null = null;

    try {
      await test.step('Clone the uninstrumented golden agent into a scratch dir', async () => {
        await scratchDir.clone(path.join(AGENTS_DIR, 'uninstrumented'));
      });

      runner = await test.step('Start opik connect against the scratch agent', async () =>
        startConnectRunner({
          projectName: project.name,
          workspace: envConfig.workspace,
          apiKey: envConfig.apiKey!,
          cwd: scratchDir.path,
        }));

      await test.step('Pair the connected runner', async () => {
        await ollie.pairConnectRunner(runner!.pairUrl);
      });

      await test.step('Open Ollie, run /instrument, and drive the approval loop', async () => {
        await ollie.goto();
        await ollie.waitForReady();
        await ollie.startInstrument();
        await ollie.approveUntilSettled();
      });

      await test.step('Assert the agent source was instrumented with Opik tracing', async () => {
        // /instrument edits the agent code in the connected dir but does not
        // necessarily *run* it, so the deterministic, directly-checkable outcome
        // is the instrumentation itself: the previously-bare agent now imports
        // opik and carries at least one @opik.track decorator. Asserting on a
        // logged trace would couple to Ollie also executing the agent and to the
        // project name it picks — neither is guaranteed by /instrument. The
        // exact number of decorated functions varies run to run (Ollie is
        // non-deterministic), so assert presence (>=1), not a count.
        const agentSrc = await fs.readFile(path.join(scratchDir.path, 'agent.py'), 'utf8');
        expect(agentSrc, 'agent.py should import opik after /instrument').toMatch(/import opik|from opik/);
        const trackCount = (agentSrc.match(/@(opik\.)?track/g) ?? []).length;
        expect(trackCount, 'agent.py should gain at least one @track decorator').toBeGreaterThanOrEqual(1);
      });
    } finally {
      runner?.stop();
    }
  });

  // Note: /improve consumes Ollie tokens, so a workspace that exhausts its
  // credit will stall the loop on a "Manage credits" banner — keep an eye on
  // that if this flakes. The accept step is a chat reply ("apply"), not a
  // button (see OlliePage.applyProposal).
  test('/improve raises the eval-suite pass rate on a known-failing agent', async ({
    project,
    scratchDir,
    envConfig,
    page,
  }) => {
    test.setTimeout(600_000);
    const ollie = new OlliePage(page, project.id);
    let runner: ConnectRunner | null = null;

    try {
      const agentFile = path.join(scratchDir.path, 'agent.py');
      let agentSrcBefore = '';

      await test.step('Clone the known-failing golden agent into a scratch dir', async () => {
        await scratchDir.clone(KNOWN_FAILING_DIR);
        agentSrcBefore = await fs.readFile(agentFile, 'utf8');
      });

      const before = await test.step('Measure the baseline suite pass rate', async () =>
        evaluatePassRate(scratchDir.path));
      // The baseline prompt omits units, so the unit-checked half fails.
      expect(before.rate, 'baseline should be a partial (failing) pass rate').toBeLessThan(1);

      await test.step('Seed failing traces so Ollie has runs to improve from', async () => {
        // /improve only surfaces once the project has traces/evaluations — it
        // improves *from* observed failures. Log a trace per suite item first.
        await seedFailingTraces(scratchDir.path, {
          projectName: project.name,
          workspace: envConfig.workspace,
          apiKey: envConfig.apiKey!,
          apiUrl: envConfig.apiBaseUrl,
        });
      });

      runner = await test.step('Start opik connect against the scratch agent', async () =>
        startConnectRunner({
          projectName: project.name,
          workspace: envConfig.workspace,
          apiKey: envConfig.apiKey!,
          cwd: scratchDir.path,
        }));

      await test.step('Pair the connected runner', async () => {
        await ollie.pairConnectRunner(runner!.pairUrl);
      });

      await test.step('Open Ollie and run /improve to completion', async () => {
        await ollie.goto();
        await ollie.waitForReady();
        // The seeded traces are eventually consistent; wait for /improve to be
        // offered before clicking it.
        await expect(ollie.improveButton()).toBeVisible({ timeout: 60_000 });
        await ollie.startImprove();
        // Analyse phase: approve tool calls while Ollie reads the traces + code.
        await ollie.approveUntilSettled();
        // Ollie proposes a fix and waits for a chat "apply" to authorise it.
        await ollie.applyProposal();
        // Apply phase: approve the edit/run tools that land the change.
        await ollie.approveUntilSettled();
      });

      await test.step('Assert Ollie edited the agent and the pass rate improved', async () => {
        // First confirm /improve actually changed the agent source. This makes a
        // failure self-diagnosing: "Ollie made no edit" (proposed-but-not-applied,
        // out of credit, etc.) reads differently from "edited but the suite still
        // fails". Don't assert *what* changed — Ollie may word its fix any way.
        const agentSrcAfter = await fs.readFile(agentFile, 'utf8');
        expect(agentSrcAfter, '/improve should have edited agent.py').not.toBe(agentSrcBefore);

        const after = await evaluatePassRate(scratchDir.path);
        expect(
          after.rate,
          `pass rate should rise after /improve (before=${before.passed}/${before.total}, after=${after.passed}/${after.total})`,
        ).toBeGreaterThan(before.rate);
      });
    } finally {
      runner?.stop();
    }
  });
});
