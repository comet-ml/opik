import type { SpanPage } from './client';

export interface PollOtelSpansOpts {
  timeoutMs?: number;
  pollIntervalMs?: number;
}

/**
 * Poll a project's spans until the expected number have been ingested, or throw
 * with a verbose diagnostic after the timeout. OTLP ingestion is asynchronous —
 * the endpoint answers 200 before the spans are queryable — so a read taken
 * straight after the POST sees a partial batch.
 *
 * Waiting on the *exact* count (not "at least one") is what makes the fixture
 * discriminating: a batch that half-landed would otherwise let a test assert
 * over the rows that did arrive and report coverage it never had.
 */
export async function pollProjectForSpanCount(
  listSpans: (projectId: string) => Promise<SpanPage>,
  projectId: string,
  expectedCount: number,
  opts: PollOtelSpansOpts = {},
): Promise<SpanPage> {
  const timeoutMs = opts.timeoutMs ?? 60_000;
  const pollIntervalMs = opts.pollIntervalMs ?? 1_000;
  const start = Date.now();
  let last: SpanPage | null = null;

  while (Date.now() - start < timeoutMs) {
    last = await listSpans(projectId);
    if (last.content.length === expectedCount) return last;
    await new Promise((r) => setTimeout(r, pollIntervalMs));
  }

  const elapsed = Date.now() - start;
  const seen = last === null ? '<never polled>' : last.content.map((s) => s.name).join(', ');
  throw new Error(
    `pollProjectForSpanCount timed out after ${elapsed}ms waiting for ${expectedCount} ` +
      `span(s) in project ${projectId}. Last seen (${last?.content.length ?? 0}): [${seen}]`,
  );
}
