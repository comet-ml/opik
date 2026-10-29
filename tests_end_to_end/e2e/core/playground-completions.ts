import { expect, type Page } from '@playwright/test';

/**
 * Driving the playground's completion proxy from a spec without ever letting a
 * request reach a provider.
 *
 * Shared because two specs now assert on the REQUEST the playground builds
 * rather than on any answer to it — which parameters a model's panel sends
 * (`playground-reasoning-model-parameters`) and which reasoning effort an
 * OpenAI key's pipeline mode coerces it to
 * (`playground-openai-effort-pipeline-mode`). Both need the same two things:
 * the proxy short-circuited at the browser, and the outgoing body captured.
 */

/** The completion proxy, on both the `/opik/api` and bare `/api` mounts. */
export function isChatCompletion(url: string): boolean {
  return new URL(url).pathname.endsWith('/v1/private/chat/completions');
}

/**
 * Run `act` and return the body of the completion request the browser sent.
 *
 * The route is installed (idempotently — Playwright keeps the most recent
 * matching handler) so the request is answered in the browser and never reaches
 * the backend proxy: every assertion built on this is on what was SENT, and
 * letting it through would have a provider generate a completion nothing reads.
 *
 * The waiter is armed before `act` because the POST is in flight the moment Run
 * is pressed, so subscribing afterwards would race it.
 */
export async function captureCompletionBody(
  page: Page,
  act: () => Promise<void>,
): Promise<Record<string, unknown>> {
  await page.route(
    (url) => isChatCompletion(url.toString()),
    (route) =>
      route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'data: [DONE]\n\n',
      }),
  );
  const sent = page.waitForRequest(
    (r) => r.method() === 'POST' && isChatCompletion(r.url()),
    { timeout: 60_000 },
  );
  await act();
  const body = (await sent).postDataJSON() as Record<string, unknown> | null;
  // Asserted, not defaulted: a Run that sent no body at all would otherwise
  // read as a body with none of the forbidden keys, which is the exact shape
  // a "this parameter must not be sent" assertion is looking for.
  expect(body, 'the Run posted a JSON completion body').not.toBeNull();
  return body!;
}

/**
 * Wait until the playground is idle again after a run.
 *
 * `captureCompletionBody` returns as soon as the POST goes out, which is while
 * the run is still in flight — the button is in its "stop" mode and the
 * parameters gear is unusable. A spec that captures one body, edits the panel
 * and runs again must wait here in between, or the edit races the running
 * request and the second capture reads the first run's config.
 *
 * The run button's `data-mode` is the state machine's own flag: `run` before
 * the first run, `re-run` once one has finished.
 */
export async function expectPlaygroundIdle(page: Page): Promise<void> {
  await expect(
    page
      .getByTestId('playground-run-button')
      .and(page.locator('[data-mode="run"], [data-mode="re-run"]')),
    'the run finished and the playground is idle again',
  ).toBeVisible();
}
