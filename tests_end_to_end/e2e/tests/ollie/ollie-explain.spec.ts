import type { Page } from '@playwright/test';
import { test, expect } from '@e2e/fixtures';
import { LogsPage, type ExplainKind } from '@e2e/pom/logs.page';
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
  duration: /duration|second|end time|running|\bms\b|millisecond/i,
};

// The assistant pod occasionally flips ready → down moments after coming up
// (see explainStore.ts's pod lifecycle), which kills an in-flight explain call
// instantly with this exact copy — a transient infra hiccup, not a rendering
// bug, so retry the whole open-and-read a few times before failing on it.
const UNAVAILABLE_PATTERN = /Ollie is unavailable/i;

// Mirrors AssistantErrorState.tsx's two error-state button labels
// ("retry now" pre-escalation, "retry" once retryCount > 0).
const ASSISTANT_RETRY_BUTTON = /^retry( now)?$/i;

// Nudges the host to reconnect after a pod blip. First pass: click the
// error state's own retry action, like a real user would. If that already
// happened and Ollie is still unavailable next time round, a soft in-app
// retry clearly isn't enough — escalate to a full reload.
async function recoverAssistant(
  page: Page,
  logs: LogsPage,
  ollie: OlliePage,
  escalate: boolean,
): Promise<void> {
  const retryButton = page.getByRole('button', { name: ASSISTANT_RETRY_BUTTON });
  if (!escalate && (await retryButton.isVisible({ timeout: 2_000 }).catch(() => false))) {
    console.log('[recoverAssistant] clicking the assistant\'s own retry button');
    await retryButton.click();
  } else {
    console.log(
      escalate
        ? '[recoverAssistant] retry button already tried, escalating to a full reload'
        : '[recoverAssistant] no retry button found, reloading the page',
    );
    await page.reload({ waitUntil: 'domcontentloaded' });
    await logs.waitForReady();
  }
  await ollie.waitForSidebarReady();
}

async function openExplainWithRetry(
  page: Page,
  logs: LogsPage,
  ollie: OlliePage,
  traceId: string,
  kind: ExplainKind,
  attempts = 3,
): Promise<string> {
  for (let attempt = 1; attempt <= attempts; attempt++) {
    await logs.openExplain(traceId, kind);
    const text = await logs.readExplanation();
    if (!UNAVAILABLE_PATTERN.test(text)) {
      // Kept permanently: surfaces pod-flapping frequency in CI output.
      if (attempt > 1) console.log(`[openExplainWithRetry] recovered on attempt ${attempt} (${kind})`);
      return text;
    }
    if (attempt === attempts) {
      console.log(`[openExplainWithRetry] gave up after ${attempts} attempts (${kind})`);
      return text;
    }
    console.log(`[openExplainWithRetry] hit "unavailable" on attempt ${attempt} (${kind}), retrying`);
    await logs.closeExplain();
    // First recovery tries the in-app retry button; if it's still
    // unavailable next time round, that button already didn't work.
    await recoverAssistant(page, logs, ollie, attempt > 1);
  }
  throw new Error('unreachable');
}

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
    const ollie = new OlliePage(page, project.id);

    await test.step('Open Logs and wait for the seeded traces to render', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      expect(await logs.countTraces()).toBe(explainTraces.length);
      // The explain popover shares the same on-demand Ollie pod/bridge as the
      // sidebar; opening it before the pod is ready fails instantly with
      // "unavailable" rather than waiting, so wait for the bridge here.
      await ollie.waitForSidebarReady();
    });

    for (const trace of explainTraces) {
      await test.step(`Explain the Errors cell for "${trace.name}"`, async () => {
        const text = await openExplainWithRetry(page, logs, ollie, trace.id, 'error');
        expect(text.length).toBeGreaterThan(0);
        expect(text).toMatch(new RegExp(trace.errorKeywordSource, 'i'));
        await logs.closeExplain();
      });
    }

    for (const trace of explainTraces) {
      await test.step(`Explain the Estimated cost cell for "${trace.name}" (cost=${trace.cost ?? 'NA'})`, async () => {
        const text = await openExplainWithRetry(page, logs, ollie, trace.id, 'cost');
        expect(text.length).toBeGreaterThan(0);
        expect(text).toMatch(KEYWORD_PATTERN.cost);
        await logs.closeExplain();
      });
    }

    for (const trace of explainTraces) {
      await test.step(`Explain the Duration cell for "${trace.name}" (duration=${trace.durationSeconds ?? 'NA'})`, async () => {
        const text = await openExplainWithRetry(page, logs, ollie, trace.id, 'duration');
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
      await ollie.waitForSidebarReady();
    });

    const explanation = await test.step(
      'Open Explain on the Errors cell and read its settled text',
      async () => openExplainWithRetry(page, logs, ollie, trace.id, 'error'),
    );

    await test.step('Continue the conversation and confirm the sidebar shows the same answer', async () => {
      const beforeCount = await ollie.messages().count();
      await logs.continueConversation();
      const sidebarText = await ollie.awaitContinuedConversation(beforeCount);
      expect(normalize(sidebarText)).toContain(normalize(explanation));
    });
  });
});
