import type { AnnotationQueueItemRef } from './client';

export interface WaitForQueueItemsSettledOpts {
  /** How long the membership set must stay unchanged before it counts as settled. */
  quietPeriodMs?: number;
  timeoutMs?: number;
  pollIntervalMs?: number;
}

/**
 * Poll a queue's membership for `itemIds` until it stops changing for
 * `quietPeriodMs`, then return it.
 *
 * Annotation-queue routing is deliberately delayed: an entity's first feedback
 * score opens a debounce window (`annotationQueueRouting.debounceDelay`, 5s by
 * default) and a flush job (`jobInterval`, 2s) sweeps elapsed windows, so an
 * item appears roughly 6-8s after the score that earns it lands. That makes
 * every assertion in this area a wait, in both directions:
 *
 *  - **Positive** — "the item routed" cannot be read immediately after scoring.
 *  - **Negative** — "the item did NOT route" is only meaningful once the window
 *    that would have routed it has elapsed. Read too early it passes vacuously,
 *    which is the failure mode this whole helper exists to prevent.
 *
 * Settling covers both with one rule, and it covers a third case a plain "wait
 * until present" poll would miss: an item that routes correctly and is then
 * routed a SECOND time. A poll that returns on first sight would be long gone.
 *
 * The default quiet period is sized against the observed routing latency (6-8s
 * on a single-node install) with headroom, not picked round: shorter than the
 * debounce plus flush interval and a negative assertion is just a fixed sleep
 * that happens to be too short.
 *
 * Throws on timeout rather than returning what it last saw — a membership set
 * that never stops changing is a real finding (a routing loop), not a value to
 * assert on.
 */
export async function waitForQueueItemsSettled(
  findItems: (queueId: string, itemIds: string[]) => Promise<AnnotationQueueItemRef[]>,
  queueId: string,
  itemIds: string[],
  opts: WaitForQueueItemsSettledOpts = {},
): Promise<AnnotationQueueItemRef[]> {
  const quietPeriodMs = opts.quietPeriodMs ?? 12_000;
  const timeoutMs = opts.timeoutMs ?? 90_000;
  const pollIntervalMs = opts.pollIntervalMs ?? 2_000;

  const start = Date.now();
  let lastFingerprint: string | null = null;
  let lastChangeAt = Date.now();
  let lastItems: AnnotationQueueItemRef[] = [];

  while (Date.now() - start < timeoutMs) {
    lastItems = await findItems(queueId, itemIds);

    const fingerprint = lastItems
      .map((item) => `${item.id}:${item.source}`)
      .sort()
      .join(',');

    if (fingerprint !== lastFingerprint) {
      lastFingerprint = fingerprint;
      lastChangeAt = Date.now();
    } else if (Date.now() - lastChangeAt >= quietPeriodMs) {
      return lastItems;
    }

    // Never sleep past the deadline, so the effective timeout does not overrun
    // by up to one poll interval — this wait is sized to fit inside a test
    // budget that already holds several of them.
    const remaining = timeoutMs - (Date.now() - start);
    if (remaining <= 0) break;
    await new Promise((r) => setTimeout(r, Math.min(pollIntervalMs, remaining)));
  }

  throw new Error(
    `waitForQueueItemsSettled timed out after ${Date.now() - start}ms on queue ${queueId}: ` +
      `membership never stayed unchanged for ${quietPeriodMs}ms. ` +
      `Last observed: [${lastFingerprint ?? '<none>'}]`,
  );
}
