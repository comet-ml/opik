/**
 * Integration tests for the TypeScript local runner.
 *
 * Test 1 (basic): register echo agent, create job, verify job result and trace output.
 * Test 2 (mask):  register echo_config agent, create mask, create job with
 *                 mask_id, verify the mask value appears in the job result and trace output.
 *
 * Requires a running Opik backend (local or cloud).
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { spawn, type ChildProcess } from "node:child_process";
import path from "node:path";
import { Opik } from "@/index";
import { AgentConfigManager } from "@/agent-config";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();
const ECHO_APP = path.join(__dirname, "echo_app.ts");
const PROJECT_NAME = `ts-runner-e2e-${Date.now()}`;

const JOB_COMPLETION_TIMEOUT = 30_000;
const TRACE_PROPAGATION_TIMEOUT = 30_000;
const AGENT_REGISTRATION_TIMEOUT = 10_000;
const RUNNER_STARTUP_TIMEOUT = 15_000;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function poll<T>(
  fn: () => Promise<T | null>,
  timeoutMs: number,
  intervalMs = 500
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = await fn().catch(() => null);
    if (result !== null) return result;
    await sleep(intervalMs);
  }
  throw new Error(`Polling timed out after ${timeoutMs}ms`);
}

describe.skipIf(!shouldRunApiTests)("Runner Integration Tests", () => {
  let client: Opik;
  let projectId: string;
  let runnerId: string;
  let runnerProcess: ChildProcess;
  let outputLines: string[] = [];

  beforeAll(async () => {
    console.log(getIntegrationTestStatus());
    if (!shouldRunApiTests) return;

    client = new Opik({ projectName: PROJECT_NAME });

    // Ensure project exists
    try {
      await client.api.projects.createProject({ name: PROJECT_NAME });
    } catch {
      // Project may already exist
    }

    const project = await client.api.projects.retrieveProject({
      name: PROJECT_NAME,
    });
    projectId = project.id!;

    // Generate pairing code
    const pair = await client.api.runners.generatePairingCode({
      projectId,
    });

    // Connect runner to get runner credentials
    const connection = await client.api.runners.connectRunner({
      pairingCode: pair.pairingCode!,
      runnerName: `ts-e2e-runner-${Date.now()}`,
    });
    runnerId = connection.runnerId!;

    // Build env for the subprocess
    const env: Record<string, string> = {
      ...process.env as Record<string, string>,
      OPIK_RUNNER_MODE: "true",
      OPIK_RUNNER_ID: runnerId,
      OPIK_PROJECT_NAME: PROJECT_NAME,
    };

    if (process.env.OPIK_URL_OVERRIDE) {
      env.OPIK_URL_OVERRIDE = process.env.OPIK_URL_OVERRIDE;
    }
    if (process.env.OPIK_API_KEY) {
      env.OPIK_API_KEY = process.env.OPIK_API_KEY;
    }
    if (process.env.OPIK_WORKSPACE) {
      env.OPIK_WORKSPACE = process.env.OPIK_WORKSPACE;
    }

    // Spawn echo_app.ts with tsx
    outputLines = [];
    runnerProcess = spawn("npx", ["tsx", ECHO_APP], {
      env,
      cwd: path.resolve(__dirname, "../../.."),
      stdio: ["pipe", "pipe", "pipe"],
    });

    runnerProcess.stdout?.on("data", (chunk: Buffer) => {
      const lines = chunk.toString().split("\n").filter(Boolean);
      outputLines.push(...lines);
    });

    runnerProcess.stderr?.on("data", (chunk: Buffer) => {
      const lines = chunk.toString().split("\n").filter(Boolean);
      outputLines.push(...lines);
    });

    // Wait for both agents to be registered
    await poll(async () => {
      const runnersPage = await client.api.runners.listRunners({
        projectId,
        size: 50,
      });
      const registeredNames = new Set<string>();
      if (runnersPage.content) {
        for (const runner of runnersPage.content) {
          if (runner.id !== runnerId) continue;
          if (runner.agents) {
            for (const agent of runner.agents) {
              if (agent.name) registeredNames.add(agent.name);
            }
          }
        }
      }
      if (registeredNames.has("echo") && registeredNames.has("echo_config")) {
        return true;
      }
      return null;
    }, AGENT_REGISTRATION_TIMEOUT);
  }, RUNNER_STARTUP_TIMEOUT + AGENT_REGISTRATION_TIMEOUT + 5_000);

  afterAll(async () => {
    if (runnerProcess) {
      runnerProcess.kill("SIGTERM");
      await new Promise<void>((resolve) => {
        const timeout = setTimeout(() => {
          runnerProcess.kill("SIGKILL");
          resolve();
        }, 3_000);
        runnerProcess.on("exit", () => {
          clearTimeout(timeout);
          resolve();
        });
      });
    }
    if (client) {
      await client.flush();
    }
    if (client && projectId) {
      await client.api.projects.deleteProjectById(projectId).catch(() => {});
    }
  });

  it(
    "should execute a job and produce a trace",
    async () => {
      const message = `hello-e2e-${Date.now()}`;

      // Submit a job
      await client.api.runners.createJob({
        agentName: "echo",
        inputs: { message },
        projectId,
      });

      // Wait for job completion
      const completedJob = await poll(async () => {
        const page = await client.api.runners.listJobs(runnerId, {
          size: 20,
        });
        if (page.content) {
          for (const job of page.content) {
            if (job.inputs && JSON.stringify(job.inputs).includes(message)) {
              if (job.status === "failed") {
                throw new Error(`Job failed: ${JSON.stringify(job.error ?? job)}`);
              }
              if (job.status === "completed") {
                return job;
              }
            }
          }
        }
        return null;
      }, JOB_COMPLETION_TIMEOUT);

      expect(completedJob).toBeDefined();
      expect(completedJob.result).toBeDefined();
      expect(JSON.stringify(completedJob.result)).toContain(`echo: ${message}`);

      // Wait for trace propagation
      const trace = await poll(async () => {
        const page = await client.api.traces.getTracesByProject({
          projectName: PROJECT_NAME,
          size: 20,
        });
        if (page.content) {
          for (const t of page.content) {
            if (
              t.input &&
              JSON.stringify(t.input).includes(message) &&
              t.output
            ) {
              return t;
            }
          }
        }
        return null;
      }, TRACE_PROPAGATION_TIMEOUT);

      expect(trace).toBeDefined();
      expect(JSON.stringify(trace.output)).toContain(`echo: ${message}`);
    },
    JOB_COMPLETION_TIMEOUT + TRACE_PROPAGATION_TIMEOUT + 5_000
  );

  it(
    "should apply mask values via agent config context",
    async () => {
      const message = `mask-e2e-${Date.now()}`;
      const customGreeting = `custom-greeting-${Date.now()}`;

      // Create a blueprint with a default greeting
      const manager = new AgentConfigManager(PROJECT_NAME, client);
      await manager.createBlueprint({
        values: [
          { key: "EchoConfig.greeting", value: "default-greeting", type: "string" },
        ],
      });

      // Create a mask that overrides the greeting
      const maskId = await manager.createMask({
        values: [
          { key: "EchoConfig.greeting", value: customGreeting, type: "string" },
        ],
      });

      // Submit a job with the mask
      await client.api.runners.createJob({
        agentName: "echo_config",
        inputs: { message },
        projectId,
        maskId,
      });

      // Wait for job completion
      const completedJob = await poll(async () => {
        const page = await client.api.runners.listJobs(runnerId, {
          size: 20,
        });
        if (page.content) {
          for (const job of page.content) {
            if (job.inputs && JSON.stringify(job.inputs).includes(message)) {
              if (job.status === "failed") {
                throw new Error(`Job failed: ${JSON.stringify(job.error ?? job)}`);
              }
              if (job.status === "completed") {
                return job;
              }
            }
          }
        }
        return null;
      }, JOB_COMPLETION_TIMEOUT);

      expect(completedJob).toBeDefined();
      expect(completedJob.result).toBeDefined();
      expect(JSON.stringify(completedJob.result)).toContain(customGreeting);

      // Wait for trace propagation
      const trace = await poll(async () => {
        const page = await client.api.traces.getTracesByProject({
          projectName: PROJECT_NAME,
          size: 20,
        });
        if (page.content) {
          for (const t of page.content) {
            if (
              t.input &&
              JSON.stringify(t.input).includes(message) &&
              t.output
            ) {
              return t;
            }
          }
        }
        return null;
      }, TRACE_PROPAGATION_TIMEOUT);

      expect(trace).toBeDefined();
      expect(JSON.stringify(trace.output)).toContain(customGreeting);
    },
    JOB_COMPLETION_TIMEOUT + TRACE_PROPAGATION_TIMEOUT + 5_000
  );
});
