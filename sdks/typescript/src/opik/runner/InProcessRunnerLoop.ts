/* eslint-disable @typescript-eslint/no-explicit-any */

import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import type { LocalRunnerJob } from "@/rest_api/api/types/LocalRunnerJob";
import { OpikApiError } from "@/rest_api/errors/OpikApiError";
import { GoneError } from "@/rest_api/api/errors/GoneError";
import { agentConfigContext } from "@/agent-config/configContext";
import { flushAll } from "@/utils/flushAll";
import { logger } from "@/utils/logger";
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

  private pollTick(backoff: number): void {
    if (this.shutdownRequested) return;

    const execute = async () => {
      if (this.shutdownRequested) return;

      let nextBackoff = backoff;
      try {
        const job = await this.api.runners.nextJob(this.runnerId);
        nextBackoff = 1_000;
        this.spawnJob(job);
      } catch (err) {
        if (err instanceof OpikApiError && err.statusCode === 204) {
          nextBackoff = 1_000;
          this.scheduleNextPoll(POLL_IDLE_INTERVAL_MS, nextBackoff);
          return;
        }
        logger.debug("Poll error", { error: err });
        const wait = this.jitteredBackoff(nextBackoff);
        nextBackoff = Math.min(nextBackoff * 2, this.backoffCapMs);
        this.scheduleNextPoll(wait, nextBackoff);
        return;
      }

      this.scheduleNextPoll(0, nextBackoff);
    };

    execute().catch((err) => {
      logger.debug("Poll tick error", { error: err });
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
    const inputs = (job.inputs as Record<string, any>) ?? {};
    const traceId = job.traceId;
    const maskId = job.maskId;

    if (this.cancelledJobs.has(jobId)) {
      this.cancelledJobs.delete(jobId);
      return;
    }

    const registry = getAll();
    const entry = registry.get(agentName);
    if (!entry) {
      logger.error(`Unknown agent '${agentName}' for job ${jobId}`);
      try {
        await this.api.runners.reportJobResult(jobId, {
          status: "failed",
          error: `Unknown agent: ${agentName}`,
        });
      } catch {
        logger.debug(`Failed to report error for job ${jobId}`);
      }
      return;
    }

    try {
      const timeout = job.timeout;
      const args = entry.params.map((p) => inputs[p.name]);

      const execute = () =>
        runWithJobContext({ traceId, jobId }, () => {
          if (maskId) {
            return agentConfigContext(maskId, () => entry.func(...args));
          }
          return entry.func(...args);
        });

      const rawResult = execute();
      const resultPromise = rawResult instanceof Promise ? rawResult : Promise.resolve(rawResult);

      let result: any;
      if (timeout && timeout > 0) {
        result = await Promise.race([
          resultPromise,
          new Promise<never>((_, reject) => {
            const t = setTimeout(
              () => reject(new TimeoutError("Job timed out")),
              timeout * 1000
            );
            t.unref();
          }),
        ]);
      } else {
        result = await resultPromise;
      }

      await flushAll().catch((err) => {
        logger.debug("Flush error after job execution", { error: err });
      });

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

      const reportResult =
        typeof result === "object" && result !== null && !Array.isArray(result)
          ? result
          : { result };

      await this.sendJobLogs(jobId);

      try {
        await this.api.runners.reportJobResult(jobId, {
          status: "completed",
          result: reportResult,
          traceId,
        });
      } catch {
        logger.debug(`Failed to report result for job ${jobId}`);
      }
    } catch (err) {
      await flushAll().catch(() => {});
      await this.sendJobLogs(jobId);

      const errorMessage =
        err instanceof TimeoutError
          ? "Job timed out"
          : err instanceof Error
            ? `${err.name}: ${err.message}`
            : String(err);

      if (err instanceof TimeoutError) {
        logger.warn(`Job ${jobId} timed out`);
      } else {
        logger.error(`Job ${jobId} failed: ${errorMessage}`);
      }

      try {
        await this.api.runners.reportJobResult(jobId, {
          status: "failed",
          error: errorMessage,
          traceId,
        });
      } catch {
        logger.debug(`Failed to report error for job ${jobId}`);
      }
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
      if (timestamp > cutoff) break;
      this.cancelledJobs.delete(key);
    }
    while (this.cancelledJobs.size > CANCELLED_JOBS_MAX_SIZE) {
      const firstKey = this.cancelledJobs.keys().next().value;
      if (firstKey !== undefined) this.cancelledJobs.delete(firstKey);
    }
  }

  private jitteredBackoff(backoff: number): number {
    return Math.min(backoff, this.backoffCapMs) * (0.5 + Math.random() * 0.5);
  }
}

class TimeoutError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TimeoutError";
  }
}
