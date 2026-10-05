import { createHash, randomBytes } from "node:crypto";
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

/** How the Opik CLI was reached. Reported here, and passed to the CLI itself. */
export const LAUNCHER = "npx";

/**
 * Tells the Python CLI how it was started, so its own events can say so too.
 * A run started by hand leaves this unset, which is what makes an npx run
 * separable from a `uvx` one on the other side of the handoff.
 */
export const LAUNCHER_ENV_VAR = "OPIK_CLI_LAUNCHER";

/**
 * Carries this run's `session_id` to the Python CLI (read in its
 * `environment_details`), so both halves of the run report the same one.
 */
export const SESSION_ID_ENV_VAR = "OPIK_CLI_SESSION_ID";

/** Nine ASCII letters, the same shape the Python SDK generates. */
const SESSION_ID_LENGTH = 9;
const SESSION_ID_ALPHABET =
  "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

export function newSessionId(): string {
  return Array.from(
    randomBytes(SESSION_ID_LENGTH),
    (byte) => SESSION_ID_ALPHABET[byte % SESSION_ID_ALPHABET.length],
  ).join("");
}

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
 * Flag *names* say which paths people take (`--ai-client`, `--skills`); a flag
 * value can be an API key. Nothing here can tell which flags take a value - the
 * flags belong to the Python CLI, not to this one - so rather than track option
 * structure, this reports only tokens *shaped* like a flag name and drops every
 * other token: a value keeps its secret even when it leads with a dash, and
 * everything after a bare `--` is a value by definition.
 *
 * The subcommand is read the same way: bare words before the first flag, which
 * is where `mcp configure` lives, and never a later token that could be a path
 * or an argument.
 */
const LONG_FLAG = /^--[a-z][a-z0-9-]*$/;
const SHORT_FLAG = /^-[A-Za-z]$/;
const SUBCOMMAND = /^[a-z][a-z0-9-]*$/;
const MAX_SUBCOMMAND_WORDS = 3;

export function summarizeArgs(args: string[]): {
  command: string;
  flags: string;
} {
  const command: string[] = [];
  const flags: string[] = [];
  let sawFlag = false;

  for (const arg of args) {
    if (arg === "--") {
      break;
    }

    if (arg.startsWith("-")) {
      sawFlag = true;
      const name = arg.split("=")[0];
      if (LONG_FLAG.test(name) || SHORT_FLAG.test(name)) {
        flags.push(name);
      }
      continue;
    }

    if (!sawFlag && command.length < MAX_SUBCOMMAND_WORDS && SUBCOMMAND.test(arg)) {
      command.push(arg);
    }
  }

  return {
    command: command.join(" "),
    flags: [...new Set(flags)].join(","),
  };
}

/**
 * Who is running this, derived the way the Python SDK derives it, so the same
 * machine reports one identity across both.
 *
 * Not an anonymous value despite the wire field it fills: a configured
 * workspace is reported as itself, exactly as `get_user_identifier` does, and
 * only the unconfigured case falls back to a hash.
 */
export function userIdentifier(
  env: NodeJS.ProcessEnv = process.env,
  home: string = os.homedir(),
): string {
  const workspace = (
    blankToUndefined(env.OPIK_WORKSPACE) ?? workspaceFromConfigFile(env, home)
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
  const id = enabled ? userIdentifier(env) : "";
  const { command, flags } = summarizeArgs(args);
  // Shared with the Python CLI, so its events join these.
  const sessionId = newSessionId();
  const inFlight: Promise<void>[] = [];

  return {
    /** Handed to the Python CLI so its events report the same run. */
    sessionId,

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
          session_id: sessionId,
          launcher: LAUNCHER,
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
  const configured = blankToUndefined(env.OPIK_CONFIG_PATH);
  // `~` is expanded the way the SDK's own loader expands it: a config path is
  // something people write by hand, and no shell expanded it for us here.
  const configPath =
    configured === undefined
      ? path.join(home, ".opik.config")
      : configured.trim().replace(/^~(?=$|\/|\\)/, home);

  try {
    const parsed = ini.parse(fs.readFileSync(configPath, "utf8"));
    const workspace = parsed?.opik?.workspace;
    return typeof workspace === "string" ? workspace : "";
  } catch {
    return "";
  }
}

function blankToUndefined(value: string | undefined): string | undefined {
  return value === undefined || value.trim() === "" ? undefined : value;
}

function userName(): string {
  try {
    return os.userInfo().username;
  } catch {
    return "";
  }
}
