import { AsyncLocalStorage } from "node:async_hooks";

export interface RunnerJobContext {
  traceId?: string;
  jobId: string;
}

export const runnerJobStorage = new AsyncLocalStorage<RunnerJobContext>();

export function getRunnerJobContext(): RunnerJobContext | undefined {
  return runnerJobStorage.getStore();
}

export function getPresetTraceId(): string | undefined {
  return runnerJobStorage.getStore()?.traceId;
}

export function runWithJobContext<T>(
  ctx: RunnerJobContext,
  fn: () => T
): T {
  return runnerJobStorage.run(ctx, fn);
}
