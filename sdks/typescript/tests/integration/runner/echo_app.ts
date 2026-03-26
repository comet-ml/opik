/**
 * Agent app used by the runner integration tests.
 *
 * Contains two entrypoints:
 * - echo: basic echo function
 * - echo_config: echo with a configurable greeting (used in mask tests)
 *
 * Stays alive via setInterval to simulate a long-running server process.
 */

import { z } from "zod";
import { OpikClient as Opik } from "@/client/Client";
import { track } from "@/decorators/track";

const echo = track(
  { entrypoint: true, name: "echo" },
  async (message: string): Promise<string> => {
    return `echo: ${message}`;
  }
);

const EchoConfig = z
  .object({ greeting: z.string() })
  .describe("EchoConfig");

const echo_config = track(
  { entrypoint: true, name: "echo_config" },
  async (message: string): Promise<string> => {
    const client = new Opik();
    await client.createAgentConfig(EchoConfig, {
      greeting: "default-greeting",
    });
    const cfg = await client.getAgentConfigVersion(EchoConfig, {
      fallback: { greeting: "fallback-greeting" },
      latest: true,
    });
    return `${cfg.greeting}: ${message}`;
  }
);

// Keep the process alive (simulates a running server like Express/Fastify)
const keepAlive = setInterval(() => {}, 60_000);

process.on("SIGTERM", () => {
  clearInterval(keepAlive);
  process.exit(0);
});

process.on("SIGINT", () => {
  clearInterval(keepAlive);
  process.exit(0);
});

process.on("unhandledRejection", (err) => {
  console.error("Unhandled rejection in echo_app:", err);
});

void echo;
void echo_config;
