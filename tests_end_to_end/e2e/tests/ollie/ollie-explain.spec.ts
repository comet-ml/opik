import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { OlliePage } from '@e2e/pom/ollie.page';

/**
 * Ollie — the per-cell "Explain" owl button on the Traces table (OPIK-6425).
 * Cloud/client-only, like the rest of Ollie — see ollie-smoke.spec.ts.
 */
function skipIfOllieDisabled(envConfig: { features: { ollie: boolean } }): void {
  test.skip(!envConfig.features.ollie, 'Ollie is cloud/client-only (OLLIE_ENABLED off)');
}

// Ollie's wording varies per call (it's a non-deterministic LLM agent), so
// assertions stay topical rather than exact-match. Cost/duration explanations
// are grounded in the same fixed facts regardless of phrasing (a dollar
// figure, a second count), so one shared pattern per kind holds up across
// runs. Error explanations vary far more (e.g. one rate-limit explanation
// never said "fail" or "exceed"), so those are checked per-trace against
// `errorKeywordSource` instead — anchored to the seeded error's concrete
// subject (rate limit, document store, timeout, ...), which Ollie's answer
// reliably references even when the surrounding phrasing differs.
const KEYWORD_PATTERN: Record<'cost' | 'duration', RegExp> = {
  cost: /cost/i,
  duration: /duration|second|end time|running/i,
};

// The popover and the sidebar chat bubble render the same markdown through
// two independent components (the popover per ExplainPopover.tsx; the
// sidebar is the separately-deployed Ollie iframe), so compare on normalized
// text with `toContain` rather than exact equality: the sidebar message's
// textContent also picks up surrounding UI chrome (a status marker, a "Copy"
// button label) that isn't part of the answer itself.
const normalize = (text: string) => text.replace(/\s+/g, ' ').trim();

test.describe('Ollie — explain cells', { tag: ['@t3-nightly', '@ollie'] }, () => {
  test.beforeEach(({ envConfig }) => skipIfOllieDisabled(envConfig));

  test('Explain renders on-topic text for the Errors, Estimated cost, and Duration cell of every seeded trace', async ({
    project,
    explainTraces,
    page,
  }) => {
    test.setTimeout(600_000);
    const logs = new LogsPage(page);

    await test.step('Open Logs and wait for the seeded traces to render', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      expect(await logs.countTraces()).toBe(explainTraces.length);
    });

    for (const trace of explainTraces) {
      await test.step(`Explain the Errors cell for "${trace.name}"`, async () => {
        await logs.openExplain(trace.id, 'error');
        const text = await logs.readExplanation();
        expect(text.length).toBeGreaterThan(0);
        expect(text).toMatch(new RegExp(trace.errorKeywordSource, 'i'));
        await logs.closeExplain();
      });
    }

    for (const trace of explainTraces) {
      await test.step(`Explain the Estimated cost cell for "${trace.name}" (cost=${trace.cost ?? 'NA'})`, async () => {
        await logs.openExplain(trace.id, 'cost');
        const text = await logs.readExplanation();
        expect(text.length).toBeGreaterThan(0);
        expect(text).toMatch(KEYWORD_PATTERN.cost);
        await logs.closeExplain();
      });
    }

    for (const trace of explainTraces) {
      await test.step(`Explain the Duration cell for "${trace.name}" (duration=${trace.durationSeconds ?? 'NA'})`, async () => {
        await logs.openExplain(trace.id, 'duration');
        const text = await logs.readExplanation();
        expect(text.length).toBeGreaterThan(0);
        expect(text).toMatch(KEYWORD_PATTERN.duration);
        await logs.closeExplain();
      });
    }
  });

  test('"Continue conversation" hands the same explanation off to the Ollie sidebar', async ({
    project,
    explainTraces,
    page,
  }) => {
    test.setTimeout(180_000);
    const logs = new LogsPage(page);
    const ollie = new OlliePage(page, project.id);
    const trace = explainTraces[0];

    await test.step('Open Logs and wait for the seeded traces to render', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
    });

    await test.step('Open Explain on the Errors cell and read its settled text', async () => {
      await logs.openExplain(trace.id, 'error');
    });

    const explanation = await logs.readExplanation();

    await test.step('Continue the conversation and confirm the sidebar shows the same answer', async () => {
      const beforeCount = await ollie.messages().count();
      await logs.continueConversation();
      const sidebarText = await ollie.awaitContinuedConversation(beforeCount);
      expect(normalize(sidebarText)).toContain(normalize(explanation));
    });
  });
});
