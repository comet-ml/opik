/* eslint-disable @typescript-eslint/no-explicit-any */

import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import type { LocalRunnerJob } from "@/rest_api/api/types/LocalRunnerJob";
import type { LocalRunnerJobResultRequest } from "@/rest_api/api/resources/runners/client/requests/LocalRunnerJobResultRequest";
import { OpikApiError } from "@/rest_api/errors/OpikApiError";
import { GoneError } from "@/rest_api/api/errors/GoneError";
import { agentConfigContext } from "@/agent-config/configContext";
import { deserializeValue } from "@/typeHelpers";
import { flushAll } from "@/utils/flushAll";
import { logger } from "@/utils/logger";
import { generateId } from "@/utils/generateId";
import { getAll } from "./registry";
import { runWithJobContext } from "./context";
import { getAndClearJobLogs } from "./prefixedOutput";

const POLL_IDLE_INTERVAL_MS = 500;
const CANCELLED_JOBS_TTL_MS = 300_000;
const CANCELLED_JOBS_MAX_SIZE = 10_000;

export class InProcessRunnerLoop {
  private readonly api: OpikApiClientTemp;
  private readonly runnerId: string;
  private readonly heartbeatIntervalMs: number;
  private readonly backoffCapMs: number;

  private shutdownRequested = false;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly cancelledJobs = new Map<string, number>();
  private readonly activeTasks = new Set<Promise<void>>();

  constructor(
    api: OpikApiClientTemp,
    runnerId: string,
    options?: {
      heartbeatIntervalMs?: number;
      backoffCapMs?: number;
    }
  ) {
    this.api = api;
    this.runnerId = runnerId;
    this.heartbeatIntervalMs = options?.heartbeatIntervalMs ?? 5_000;
    this.backoffCapMs = options?.backoffCapMs ?? 30_000;
  }

  start(): void {
    this.startHeartbeat();
    this.startPolling();
  }

  shutdown(): void {
    this.shutdownRequested = true;
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    if (this.pollTimer) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  // -- Heartbeat --

  private startHeartbeat(): void {
    const timer = setInterval(() => {
      this.heartbeatTick().catch((err) => {
        logger.debug("Heartbeat tick error", { error: err });
      });
    }, this.heartbeatIntervalMs);
    timer.unref();
    this.heartbeatTimer = timer;
  }

  private async heartbeatTick(): Promise<void> {
    if (this.shutdownRequested) return;
    try {
      const resp = await this.api.runners.heartbeat(this.runnerId);
      const cancelledIds = resp.cancelledJobIds ?? [];
      const now = Date.now();
      for (const jid of cancelledIds) {
        this.cancelledJobs.set(jid, now);
      }
      this.pruneCancelledJobs(now);
    } catch (err) {
      if (err instanceof GoneError) {
        logger.info("Runner deregistered (410), shutting down");
        this.shutdown();
        return;
      }
      logger.debug("Heartbeat error", { error: err });
    }
  }

  // -- Polling --

  private startPolling(): void {
    this.pollTick(1_000);
  }

  private pollFailures = 0;

  private pollTick(backoff: number): void {
    if (this.shutdownRequested) return;

    const execute = async () => {
      if (this.shutdownRequested) return;

      let nextBackoff = backoff;
      try {
        const job = await this.api.runners.nextJob(this.runnerId);
        this.pollFailures = 0;
        nextBackoff = 1_000;
        this.spawnJob(job);
      } catch (err) {
        if (err instanceof OpikApiError && err.statusCode === 204) {
          this.pollFailures = 0;
          nextBackoff = 1_000;
          this.scheduleNextPoll(POLL_IDLE_INTERVAL_MS, nextBackoff);
          return;
        }
        this.pollFailures++;
        if (this.pollFailures === 1) {
          const statusCode = err instanceof OpikApiError ? err.statusCode : undefined;
          logger.warn("Unable to reach Opik server" + (statusCode ? ` (API ${statusCode})` : "") + ". Retrying...", { error: err });
        } else {
          logger.debug("Poll error", { error: err });
        }
        const wait = this.jitteredBackoff(nextBackoff);
        nextBackoff = Math.min(nextBackoff * 2, this.backoffCapMs);
        this.scheduleNextPoll(wait, nextBackoff);
        return;
      }

      this.scheduleNextPoll(0, nextBackoff);
    };

    execute().catch((err) => {
      this.pollFailures++;
      if (this.pollFailures === 1) {
        logger.warn("Unable to reach Opik server. Retrying...", { error: err });
      } else {
        logger.debug("Poll tick error", { error: err });
      }
      this.scheduleNextPoll(
        this.jitteredBackoff(backoff),
        Math.min(backoff * 2, this.backoffCapMs)
      );
    });
  }

  private scheduleNextPoll(delayMs: number, backoff: number): void {
    if (this.shutdownRequested) return;
    const timer = setTimeout(() => this.pollTick(backoff), delayMs);
    timer.unref();
    this.pollTimer = timer;
  }

  // -- Job execution --

  private spawnJob(job: LocalRunnerJob): void {
    const task = this.executeJob(job).finally(() => {
      this.activeTasks.delete(task);
    });
    this.activeTasks.add(task);
  }

  private async executeJob(job: LocalRunnerJob): Promise<void> {
    const jobId = job.id ?? "";
    const agentName = job.agentName ?? "";

    if (this.cancelledJobs.has(jobId)) {
      logger.debug(`Skipping cancelled job ${jobId}`);
      this.cancelledJobs.delete(jobId);
      return;
    }

    const entry = getAll().get(agentName);
    if (!entry) {
      logger.error(`Unknown agent '${agentName}' for job ${jobId}`);
      await this.reportJobResult(jobId, { status: "failed", error: `Unknown agent: ${agentName}`, traceId: job.traceId });
      return;
    }

    const traceId = generateId();

    await this.reportJobResult(jobId, { status: "running", traceId });

    try {
      const result = await this.invokeAgent(job, jobId, traceId);
      await flushAll().catch((err) => {
        logger.debug("Flush error after job execution", { error: err });
      });
      await this.sendJobLogs(jobId);
      await this.reportJobResult(jobId, {
        status: "completed",
        result: this.normalizeResult(result),
        traceId,
      });
    } catch (err) {
      await flushAll().catch(() => {});
      await this.sendJobLogs(jobId);

      const timeout = job.timeout;
      const errorMessage =
        err instanceof TimeoutError
          ? `Job timed out after ${timeout}s`
          : err instanceof Error
            ? `${err.name}: ${err.message}`
            : String(err);

      if (err instanceof TimeoutError) {
        logger.warn(`Job ${jobId} timed out after ${timeout}s`);
      } else {
        logger.error(`Job ${jobId} failed: ${errorMessage}`);
      }

      await this.reportJobResult(jobId, { status: "failed", error: errorMessage, traceId });
    }
  }

  private async invokeAgent(job: LocalRunnerJob, jobId: string, traceId: string): Promise<any> {
    const agentName = job.agentName ?? "";
    const inputs = (job.inputs as Record<string, any>) ?? {};
    const maskId = job.maskId;

    const entry = getAll().get(agentName)!;
    const args = entry.params.map((p) => castInputValue(inputs[p.name], p.type));

    const run = () =>
      runWithJobContext({ traceId, jobId }, () => {
        if (maskId) {
          return agentConfigContext(maskId, () => entry.func(...args));
        }
        return entry.func(...args);
      });

    const resultPromise = Promise.resolve(run());

    const timeout = job.timeout;
    if (timeout && timeout > 0) {
      return Promise.race([
        resultPromise,
        new Promise<never>((_, reject) => {
          const t = setTimeout(() => reject(new TimeoutError("Job timed out")), timeout * 1000);
          t.unref();
        }),
      ]);
    }

    return resultPromise;
  }

  private normalizeResult(result: any): any {
    if (
      result !== null &&
      result !== undefined &&
      typeof result !== "string" &&
      typeof result !== "number" &&
      typeof result !== "boolean" &&
      !Array.isArray(result) &&
      typeof result !== "object"
    ) {
      result = String(result);
    }
    return typeof result === "object" && result !== null && !Array.isArray(result)
      ? result
      : { result };
  }

  private async reportJobResult(
    jobId: string,
    payload: LocalRunnerJobResultRequest
  ): Promise<void> {
    try {
      await this.api.runners.reportJobResult(jobId, payload);
    } catch (err) {
      logger.warn(`Failed to report result for job ${jobId}`, { error: err });
    }
  }

  private async sendJobLogs(jobId: string): Promise<void> {
    const logs = getAndClearJobLogs(jobId);
    if (logs.length === 0) return;
    try {
      await this.api.runners.appendJobLogs(jobId, { body: logs });
    } catch {
      logger.debug(`Failed to send logs for job ${jobId}`);
    }
  }

  // -- Helpers --

  private pruneCancelledJobs(now: number): void {
    const cutoff = now - CANCELLED_JOBS_TTL_MS;
    for (const [key, timestamp] of this.cancelledJobs) {
      if (timestamp <= cutoff) {
        this.cancelledJobs.delete(key);
      }
    }
    if (this.cancelledJobs.size > CANCELLED_JOBS_MAX_SIZE) {
      const sorted = [...this.cancelledJobs.entries()].sort(
        (a, b) => a[1] - b[1]
      );
      const toRemove = sorted.length - CANCELLED_JOBS_MAX_SIZE;
      for (let i = 0; i < toRemove; i++) {
        this.cancelledJobs.delete(sorted[i][0]);
      }
    }
  }

  private jitteredBackoff(backoff: number): number {
    return Math.min(backoff, this.backoffCapMs) * (0.5 + Math.random() * 0.5);
  }
}

export function castInputValue(value: unknown, type: string): unknown {
  if (value === null || value === undefined) return value;
  switch (type) {
    case "boolean":
      if (typeof value === "boolean") return value;
      return deserializeValue(String(value), "boolean");
    case "number": {
      if (typeof value === "number") return value;
      const result = deserializeValue(String(value), "float");
      if (typeof result === "number" && Number.isNaN(result)) {
        throw new TypeError(`Cannot cast "${value}" to number`);
      }
      return result;
    }
    case "string":
    default:
      if (typeof value === "string") return value;
      if (Array.isArray(value) || (typeof value === "object" && value !== null))
        return JSON.stringify(value);
      return String(value);
  }
}

class TimeoutError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TimeoutError";
  }
}
