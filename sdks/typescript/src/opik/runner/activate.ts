import { logger } from "@/utils/logger";
import { OpikClient } from "@/client/Client";
import { getAll, onRegister, type RegistryEntry } from "./registry";
import { InProcessRunnerLoop } from "./InProcessRunnerLoop";
import { installPrefixedOutput } from "./prefixedOutput";

let _started = false;
let _shutdownBySignal = false;

export function activateRunner(): void {
  if (process.env.OPIK_RUNNER_MODE !== "true") return;
  if (_started) return;
  _started = true;

  _run().catch((err) => {
    logger.error("Runner activation failed", { error: err });
  });
}

async function _run(): Promise<void> {
  const runnerId = process.env.OPIK_RUNNER_ID ?? "";
  const projectName = process.env.OPIK_PROJECT_NAME ?? "";

  if (!runnerId) {
    logger.error("OPIK_RUNNER_ID not set, cannot activate runner");
    return;
  }

  printBanner(runnerId, projectName);
  installPrefixedOutput();

  const client = new OpikClient();
  const api = client.api;

  function toPayload(entry: RegistryEntry): Record<string, unknown> {
    return {
      description: entry.docstring,
      language: "typescript",
      params: entry.params.map((p) => ({ name: p.name, type: p.type })),
      timeout: 0,
    };
  }

  function syncAgent(_name: string): void {
    const all = getAll();
    const body: Record<string, unknown> = {};
    for (const [name, entry] of all) {
      body[name] = toPayload(entry);
    }
    api.runners
      .registerAgents(runnerId, { body })
      .catch((err) => {
        logger.debug("Failed to sync agents after new registration", { error: err });
      });
  }

  // Defer until after all synchronous module-level track() calls have run.
  // Promises (microtasks) can interleave with remaining synchronous code, but
  // setImmediate fires only after the current call stack and all microtasks
  // are drained — by which point the user's module has fully loaded.
  await new Promise<void>((resolve) => setImmediate(resolve));

  const entrypoints = getAll();
  if (entrypoints.size > 0) {
    const body: Record<string, unknown> = {};
    for (const [name, entry] of entrypoints) {
      body[name] = toPayload(entry);
    }
    try {
      await api.runners.registerAgents(runnerId, { body });
    } catch {
      logger.debug("Failed to register agents on startup");
    }
  }

  onRegister(syncAgent);

  logger.info("Runner activated");

  const loop = new InProcessRunnerLoop(api, runnerId);
  loop.start();

  const shutdownHandler = () => {
    _shutdownBySignal = true;
    logger.info("Received shutdown signal, stopping runner...");
    loop.shutdown();
    client.flush().catch(() => {}).finally(() => process.exit(0));
  };

  process.once("SIGTERM", shutdownHandler);
  process.once("SIGINT", shutdownHandler);

  process.on("exit", () => {
    if (!_shutdownBySignal) {
      console.error(
        "\nWarning: The process exited without blocking. " +
        "The runner needs the process to stay alive to process jobs.\n" +
        "Use a server framework like express or fastify to keep the process running.\n"
      );
    }
  });
}

function printBanner(runnerId: string, projectName: string): void {
  const parts = [
    "  \u2800\u20dd",
    "opik  ",
    `runner: ${runnerId}`,
  ];
  if (projectName) {
    parts.push(`  project: ${projectName}`);
  }
  console.log(parts.join(""));
  console.log();
}
