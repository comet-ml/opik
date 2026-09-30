import { createHash, randomUUID } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import ini from "ini";

/**
 * Reports what this command did to Comet's stats collector, which forwards to
 * Segment and on to PostHog - the same route the Python SDK's own
 * `opik mcp configure` events take, so a run started here and a run started with
 * `uvx` land in one funnel and can be compared.
 *
 * Event names follow the Python SDK's scheme, `opik_<sdk>__<component>__<path>`:
 * splitting on `__` gives the path back. The path says `cli` rather than
 * anything about uv or npx, because those are how this command reaches the Opik
 * CLI today, not what it is - `launcher` reports that as a property instead.
 *
 * Everything here is best-effort and must never delay or fail the command it is
 * reporting on.
 */

const ANALYTICS_URL_DEFAULT = "https://stats.comet.com/notify/event/";
const EVENT_NAME_PREFIX = "opik_typescript_sdk__configuration__cli";
const SEND_TIMEOUT_MS = 3_000;
const WORKSPACE_DEFAULT_NAME = "default";

declare const __OPIK_SDK_VERSION__: string;

const SDK_VERSION =
  typeof __OPIK_SDK_VERSION__ === "undefined"
    ? "unknown"
    : __OPIK_SDK_VERSION__;

export type EventName = "invoked" | "uv_detected" | "handoff" | "error_shown";

export type PropertyValue = string | number | boolean | undefined;

export interface Event {
  anonymous_id: string;
  event_type: string;
  event_properties: Record<string, PropertyValue>;
}

export function eventName(name: EventName): string {
  return `${EVENT_NAME_PREFIX}__${name}`;
}

/** `OPIK_ANALYTICS_ENABLE=false` - the same switch the Python SDK honours. */
export function analyticsEnabled(
  env: NodeJS.ProcessEnv = process.env,
): boolean {
  const value = env.OPIK_ANALYTICS_ENABLE;
  if (value === undefined) {
    return true;
  }
  return !["0", "false", "no"].includes(value.trim().toLowerCase());
}

/**
 * The arguments, minus anything a user could have typed a secret into.
 *
 * Flag *names* say which paths people take (`--ai-client`, `--skills`); flag
 * values can be an API key, so only the names are reported.
 */
export function summarizeArgs(args: string[]): {
  command: string;
  flags: string;
} {
  const command: string[] = [];
  const flags: string[] = [];
  let afterFlag = false;

  for (const arg of args) {
    if (arg.startsWith("-")) {
      flags.push(arg.split("=")[0]);
      afterFlag = !arg.includes("=");
      continue;
    }
    if (!afterFlag) {
      command.push(arg);
    }
    afterFlag = false;
  }

  return { command: command.join(" "), flags: flags.join(",") };
}

/**
 * Who is running this, derived the way the Python SDK derives it, so the same
 * machine reports one identity across both.
 */
export function anonymousId(
  env: NodeJS.ProcessEnv = process.env,
  home: string = os.homedir(),
): string {
  const workspace = (
    env.OPIK_WORKSPACE ?? workspaceFromConfigFile(env, home)
  ).trim();

  if (workspace !== "" && workspace !== WORKSPACE_DEFAULT_NAME) {
    return workspace;
  }

  const machine = createHash("sha256")
    .update(os.hostname() + userName())
    .digest("hex");
  return `${WORKSPACE_DEFAULT_NAME}_${machine}`;
}

export function createReporter(
  args: string[],
  env: NodeJS.ProcessEnv = process.env,
) {
  const enabled = analyticsEnabled(env);
  const url = env.OPIK_ANALYTICS_URL ?? ANALYTICS_URL_DEFAULT;
  const id = enabled ? anonymousId(env) : "";
  const { command, flags } = summarizeArgs(args);
  // Ties the three events of one run together, since they are separate requests.
  const runId = randomUUID();
  const inFlight: Promise<void>[] = [];

  return {
    track(
      name: EventName,
      properties: Record<string, PropertyValue> = {},
    ): void {
      if (!enabled || url === "") {
        return;
      }
      const event: Event = {
        anonymous_id: id,
        event_type: eventName(name),
        event_properties: {
          run_id: runId,
          launcher: "npx",
          sdk_version: SDK_VERSION,
          command,
          flags,
          node_version: process.versions.node,
          os: process.platform,
          arch: process.arch,
          ci: Boolean(env.CI),
          ...properties,
        },
      };
      inFlight.push(send(url, event));
    },

    /** Lets the queued reports finish, but never holds the command up for long. */
    async flush(): Promise<void> {
      if (inFlight.length === 0) {
        return;
      }
      await Promise.race([
        Promise.allSettled(inFlight),
        new Promise((resolve) => setTimeout(resolve, SEND_TIMEOUT_MS).unref()),
      ]);
    },
  };
}

async function send(url: string, event: Event): Promise<void> {
  try {
    await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "User-Agent": `opik-typescript-sdk/${SDK_VERSION}`,
      },
      body: JSON.stringify(event),
      signal: AbortSignal.timeout(SEND_TIMEOUT_MS),
    });
  } catch {
    // An unreachable collector is not the user's problem.
  }
}

function workspaceFromConfigFile(env: NodeJS.ProcessEnv, home: string): string {
  const configPath = env.OPIK_CONFIG_PATH ?? path.join(home, ".opik.config");

  try {
    const parsed = ini.parse(fs.readFileSync(configPath, "utf8"));
    const workspace = parsed?.opik?.workspace;
    return typeof workspace === "string" ? workspace : "";
  } catch {
    return "";
  }
}

function userName(): string {
  try {
    return os.userInfo().username;
  } catch {
    return "";
  }
}
