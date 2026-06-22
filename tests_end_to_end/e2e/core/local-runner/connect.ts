import { spawn, type ChildProcess, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import * as path from 'node:path';

const execFileAsync = promisify(execFile);

const E2E_DIR = path.resolve(__dirname, '../..');
/** The opik-sdk-driver bridge project — `uv run` resolves its pinned venv from here. */
export const BRIDGE_DIR = path.join(E2E_DIR, 'services/opik-sdk-driver');

// The connect daemon renders the pairing URL as a Rich OSC-8 terminal
// hyperlink — `ESC]8;;<URL>ESC\<label>…`. The full URL (with its activation
// fragment) lives inside the OSC payload, so match a URL token bounded by
// whitespace/BEL/ESC. Matching the visible plain-text echo instead truncates at
// the terminal wrap width and corrupts the fragment.
// eslint-disable-next-line no-control-regex
const PAIR_URL_RE = /https?:\/\/[^\s\x07\x1b]*pair\/v1[^\s\x07\x1b]*/;

export interface ConnectRunner {
  /** The scraped `…/opik/pair/v1?…#<fragment>` URL to open in the browser. */
  pairUrl: string;
  /** Accumulated daemon stdout/stderr (ANSI included), for diagnostics. */
  output(): string;
  /** Stop the daemon. */
  stop(): void;
}

export interface StartConnectOpts {
  /** Project the runner attaches to (must already exist). */
  projectName: string;
  workspace: string;
  apiKey: string;
  /** Agent working directory the daemon watches (Ollie reads/edits code here). */
  cwd: string;
  /** How long to wait for the pairing URL to appear. */
  scrapeTimeoutMs?: number;
}

/**
 * Start an `opik connect` daemon (Local Runner) against the bridge venv and
 * return once its pairing URL has been scraped from stdout. `connect`'s `_run`
 * takes only options — no trailing command — and watches `cwd` so Ollie can see
 * and instrument the agent source there. Pair by opening `pairUrl` in an
 * authenticated browser (see `OlliePage.pairConnectRunner`).
 */
export async function startConnectRunner(opts: StartConnectOpts): Promise<ConnectRunner> {
  const scrapeTimeoutMs = opts.scrapeTimeoutMs ?? 40_000;
  let out = '';

  const proc: ChildProcess = spawn(
    'uv',
    [
      'run',
      'opik',
      '--api-key',
      opts.apiKey,
      'connect',
      '--project',
      opts.projectName,
      '--workspace',
      opts.workspace,
      '--non-interactive',
    ],
    {
      cwd: opts.cwd,
      env: {
        ...process.env,
        OPIK_API_KEY: opts.apiKey,
        OPIK_WORKSPACE: opts.workspace,
        // `uv run` resolves the bridge venv from this project dir, so the
        // command uses the same pinned opik the suite seeds with.
        UV_PROJECT: BRIDGE_DIR,
      },
    },
  );
  proc.stdout?.on('data', (d) => (out += d.toString()));
  proc.stderr?.on('data', (d) => (out += d.toString()));

  const stop = () => {
    try {
      proc.kill();
    } catch {
      /* already gone */
    }
  };

  const deadline = Date.now() + scrapeTimeoutMs;
  while (Date.now() < deadline) {
    const m = out.match(PAIR_URL_RE);
    if (m) {
      return { pairUrl: m[0], output: () => out, stop };
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  stop();
  throw new Error(
    `startConnectRunner: no pairing URL scraped within ${scrapeTimeoutMs}ms. Daemon output:\n${out.slice(0, 2000)}`,
  );
}

export interface StartEndpointOpts extends StartConnectOpts {
  /**
   * Command (and args) the endpoint runner spawns to host the agent process —
   * what goes after the `--` separator in `opik endpoint --project X -- …`.
   * The runner introspects the entrypoint inside this process to expose it to
   * the Agent Playground.
   */
  command: string[];
  /**
   * Opik backend API base URL (e.g. http://localhost:5173/api). Used to poll
   * `/v1/private/local-runners` until the runner appears as type=endpoint and
   * status=connected.
   */
  apiBaseUrl: string;
  /** Project id the runner attaches to — used as the poll filter. */
  projectId: string;
}

export interface EndpointRunner {
  /** Id of the registered runner (from the backend list). */
  runnerId: string;
  /** Accumulated daemon stdout/stderr, for diagnostics. */
  output(): string;
  /** Stop the daemon. */
  stop(): void;
}

/**
 * Start an `opik endpoint` daemon in `--headless` mode and return once the
 * runner appears as type="endpoint" + status="connected" in the backend.
 *
 * `--headless` self-activates without browser pairing and emits no pairing URL
 * (Rich Live suppresses output on non-TTY). Readiness is therefore discovered
 * by polling `GET <apiBaseUrl>/v1/private/local-runners?project_id=<id>`,
 * which is the same endpoint the Agent Playground polls.
 *
 * The runner discovers the agent via `has_entrypoint(Path.cwd())`, which scans
 * git-tracked files first. The scratch dir lives under `.test-scratch/` in
 * the opik repo, which is gitignored — so we `git init` the scratch dir to
 * make it a standalone repo. Without this, the scan inherits the parent repo
 * and skips agent.py, producing "No entrypoint found".
 */
export async function startEndpointRunner(opts: StartEndpointOpts): Promise<EndpointRunner> {
  const scrapeTimeoutMs = opts.scrapeTimeoutMs ?? 40_000;
  let out = '';

  await execFileAsync('git', ['init', '-q'], { cwd: opts.cwd });

  const proc: ChildProcess = spawn(
    'uv',
    [
      'run',
      'opik',
      '--api-key',
      opts.apiKey,
      'endpoint',
      '--project',
      opts.projectName,
      '--workspace',
      opts.workspace,
      '--headless',
      '--non-interactive',
      '--',
      ...opts.command,
    ],
    {
      cwd: opts.cwd,
      env: {
        ...process.env,
        OPIK_API_KEY: opts.apiKey,
        OPIK_WORKSPACE: opts.workspace,
        UV_PROJECT: BRIDGE_DIR,
      },
    },
  );
  proc.stdout?.on('data', (d) => (out += d.toString()));
  proc.stderr?.on('data', (d) => (out += d.toString()));

  const stop = () => {
    try {
      proc.kill();
    } catch {
      /* already gone */
    }
  };

  const apiBase = opts.apiBaseUrl.replace(/\/+$/, '');
  const url = `${apiBase}/v1/private/local-runners?project_id=${encodeURIComponent(opts.projectId)}&size=50`;
  const headers: Record<string, string> = {
    'Comet-Workspace': opts.workspace,
    Accept: 'application/json',
  };
  if (opts.apiKey) headers['Authorization'] = opts.apiKey;

  const deadline = Date.now() + scrapeTimeoutMs;
  while (Date.now() < deadline) {
    if (proc.exitCode !== null) {
      throw new Error(
        `startEndpointRunner: opik endpoint exited (code=${proc.exitCode}) before runner connected. Output:\n${out.slice(0, 2000)}`,
      );
    }
    try {
      const res = await fetch(url, { headers });
      if (res.ok) {
        const body = (await res.json()) as { content?: Array<{ id: string; type?: string; status?: string }> };
        const runner = (body.content ?? []).find(
          (r) => r.type === 'endpoint' && r.status === 'connected',
        );
        if (runner) {
          return { runnerId: runner.id, output: () => out, stop };
        }
      }
    } catch {
      /* transient — keep polling until deadline */
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  stop();
  throw new Error(
    `startEndpointRunner: no connected endpoint runner appeared within ${scrapeTimeoutMs}ms. Daemon output:\n${out.slice(0, 2000)}`,
  );
}

/**
 * Run an inline Python snippet under `uv run` in the bridge project, so it uses
 * the same pinned opik the suite seeds with (agents decorated with
 * `@opik.track` need opik importable). Returns stdout. Generic plumbing —
 * agent-specific harnesses build their shim and parse the output.
 */
export async function runBridgePython(
  code: string,
  env: NodeJS.ProcessEnv = {},
  cwd?: string,
): Promise<string> {
  const { stdout } = await execFileAsync('uv', ['run', 'python', '-c', code], {
    cwd,
    env: { ...process.env, UV_PROJECT: BRIDGE_DIR, ...env },
  });
  return stdout;
}
