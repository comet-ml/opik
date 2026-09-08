import type { FeedbackScoreRef, SpanDetail, TraceDetail } from './client';

export interface PollFeedbackScoreOpts {
  timeoutMs?: number;
  pollIntervalMs?: number;
}

/** The only shape either poller needs: something that carries feedback scores. */
interface ScoredEntity {
  feedbackScores: FeedbackScoreRef[];
}

/**
 * Poll an entity's REST view until a feedback_score with the given name appears,
 * or throw with a verbose diagnostic message after the timeout. Used to assert
 * on asynchronously-produced state landed by the online evaluation engine.
 *
 * `kind` is only ever used in the failure message, but it earns its place
 * there: "no score on span X" and "no score on trace X" fail for different
 * reasons (a span-scope rule and a trace-scope rule ride different Redis
 * streams), and the id alone does not say which one was being polled.
 */
async function pollForFeedbackScore<T extends ScoredEntity>(
  kind: 'trace' | 'span',
  getEntity: (id: string) => Promise<T | null>,
  id: string,
  scoreName: string,
  opts: PollFeedbackScoreOpts,
): Promise<FeedbackScoreRef> {
  const timeoutMs = opts.timeoutMs ?? 60_000;
  const pollIntervalMs = opts.pollIntervalMs ?? 2_000;
  const start = Date.now();
  let last: T | null = null;

  while (Date.now() - start < timeoutMs) {
    last = await getEntity(id);
    const hit = last?.feedbackScores.find((fs) => fs.name === scoreName);
    if (hit) return hit;
    await new Promise((r) => setTimeout(r, pollIntervalMs));
  }

  const elapsed = Date.now() - start;
  const lastScores =
    last === null
      ? `<${kind} not found>`
      : `[${last.feedbackScores.map((fs) => `${fs.name}=${fs.value}`).join(', ')}]`;
  throw new Error(
    `pollForFeedbackScore timed out after ${elapsed}ms ` +
      `waiting for feedback_score "${scoreName}" on ${kind} ${id}. ` +
      `Last polled feedback_scores: ${lastScores}`,
  );
}

export async function pollTraceForFeedbackScore(
  getTrace: (traceId: string) => Promise<TraceDetail | null>,
  traceId: string,
  scoreName: string,
  opts: PollFeedbackScoreOpts = {},
): Promise<FeedbackScoreRef> {
  return pollForFeedbackScore('trace', getTrace, traceId, scoreName, opts);
}

/**
 * The span-scope counterpart. A span-scope rule writes to the span, never to
 * its parent trace, so polling the trace for a span rule's score waits forever.
 */
export async function pollSpanForFeedbackScore(
  getSpan: (spanId: string) => Promise<SpanDetail | null>,
  spanId: string,
  scoreName: string,
  opts: PollFeedbackScoreOpts = {},
): Promise<FeedbackScoreRef> {
  return pollForFeedbackScore('span', getSpan, spanId, scoreName, opts);
}
