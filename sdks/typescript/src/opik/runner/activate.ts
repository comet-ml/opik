import { logger } from "@/utils/logger";
import { getAll, onRegister, type RegistryEntry } from "./registry";
import { InProcessRunnerLoop } from "./InProcessRunnerLoop";
import { installPrefixedOutput } from "./prefixedOutput";

let _started = false;

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

  const { OpikClient } = await import("@/client/Client");
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

  function syncAgent(name: string): void {
    const all = getAll();
    const entry = all.get(name);
    if (!entry) return;
    api.runners
      .registerAgents(runnerId, {
        body: { [name]: toPayload(entry) },
      })
      .catch(() => {
        logger.debug(`Failed to register agent '${name}'`);
      });
  }

  onRegister(syncAgent);

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

  logger.info("Runner activated");

  const loop = new InProcessRunnerLoop(api, runnerId);
  loop.start();

  const shutdownHandler = () => {
    logger.info("Received shutdown signal, stopping runner...");
    loop.shutdown();
  };

  process.on("SIGTERM", shutdownHandler);
  process.on("SIGINT", shutdownHandler);
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
