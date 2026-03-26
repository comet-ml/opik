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
  { entrypoint: true, name: "echo", params: [{ name: "message", type: "string" }] },
  async (message: string): Promise<string> => {
    return `echo: ${message}`;
  }
);

const EchoConfig = z
  .object({ greeting: z.string() })
  .describe("EchoConfig");

const echo_config = track(
  { entrypoint: true, name: "echo_config", params: [{ name: "message", type: "string" }] },
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

// Keep the process alive until killed by the test harness (SIGTERM via afterAll).
// process.stdin.resume() holds the event loop open regardless of timer unref state.
process.stdin.resume();

process.on("unhandledRejection", (err) => {
  console.error("Unhandled rejection in echo_app:", err);
});

void echo;
void echo_config;
