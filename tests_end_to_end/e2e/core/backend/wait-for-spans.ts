import type { SpanRowRef } from './client';

export interface WaitForSpansOpts {
  timeoutMs?: number;
  pollIntervalMs?: number;
}

/**
 * Poll a project's spans until every name in `names` is present, then return
 * those spans in the order the names were given.
 *
 * OTLP ingestion answers 200 before the span is queryable — the endpoint hands
 * the batch to an asynchronous writer — so a read issued straight after the
 * POST legitimately comes back empty. This is the wait for that, gated on the
 * seeded names rather than on a row count, because a project can hold spans
 * from an earlier step and "some rows exist" would let a partially-landed batch
 * through.
 *
 * Throws naming the missing spans rather than returning what it found: a test
 * that carried on with two of three seeded spans would go green having asserted
 * over the wrong set.
 */
export async function waitForSpansByName(
  listSpans: (args: { projectId: string }) => Promise<{ total: number; spans: SpanRowRef[] }>,
  projectId: string,
  names: string[],
  opts: WaitForSpansOpts = {},
): Promise<SpanRowRef[]> {
  const timeoutMs = opts.timeoutMs ?? 60_000;
  const pollIntervalMs = opts.pollIntervalMs ?? 1_000;

  const start = Date.now();
  let seen: string[] = [];

  while (Date.now() - start < timeoutMs) {
    const { spans } = await listSpans({ projectId });
    seen = spans.map((s) => s.name);
    const byName = new Map(spans.map((s) => [s.name, s]));
    const found = names.map((name) => byName.get(name));
    if (found.every((s): s is SpanRowRef => s !== undefined)) {
      return found;
    }

    // Never sleep past the deadline, so the effective timeout doesn't overrun
    // by up to one poll interval.
    const remaining = timeoutMs - (Date.now() - start);
    if (remaining <= 0) break;
    await new Promise((r) => setTimeout(r, Math.min(pollIntervalMs, remaining)));
  }

  const missing = names.filter((name) => !seen.includes(name));
  throw new Error(
    `waitForSpansByName timed out after ${Date.now() - start}ms on project ${projectId}: ` +
      `never saw span(s) [${missing.join(', ')}]. Present: [${seen.join(', ') || '<none>'}]`,
  );
}
