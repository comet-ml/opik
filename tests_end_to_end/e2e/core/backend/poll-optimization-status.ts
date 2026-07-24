import type { OptimizationRef } from './client';

export type OptimizationStatus =
  | 'initialized'
  | 'running'
  | 'completed'
  | 'cancelled'
  | 'error';

const TERMINAL_STATUSES: OptimizationStatus[] = ['completed', 'cancelled', 'error'];

export interface PollOptimizationStatusOpts {
  timeoutMs?: number;
  pollIntervalMs?: number;
}

/**
 * Poll an optimization's REST view until it reaches `target`, or throw. A studio
 * run enqueues an RQ job that the python-backend worker executes asynchronously,
 * so the status walks initialized → running → completed out of band from the UI.
 * Reaching a terminal status other than `target` (error/cancelled) fails fast
 * rather than waiting out the whole timeout.
 */
export async function pollOptimizationStatus(
  getOptimization: (id: string) => Promise<OptimizationRef | null>,
  optimizationId: string,
  target: OptimizationStatus,
  opts: PollOptimizationStatusOpts = {},
): Promise<OptimizationRef> {
  const timeoutMs = opts.timeoutMs ?? 240_000;
  const pollIntervalMs = opts.pollIntervalMs ?? 3_000;
  const start = Date.now();
  let last: OptimizationRef | null = null;

  while (Date.now() - start < timeoutMs) {
    last = await getOptimization(optimizationId);
    if (last?.status === target) return last;
    if (last && TERMINAL_STATUSES.includes(last.status) && last.status !== target) {
      throw new Error(
        `optimization ${optimizationId} reached terminal status "${last.status}" ` +
          `while waiting for "${target}"`,
      );
    }
    await new Promise((r) => setTimeout(r, pollIntervalMs));
  }

  const elapsed = Date.now() - start;
  throw new Error(
    `pollOptimizationStatus timed out after ${elapsed}ms waiting for status "${target}" ` +
      `on optimization ${optimizationId}. Last status: ${last?.status ?? '<not found>'}`,
  );
}
