import fs from "node:fs";
import os from "node:os";

export type CliEvent = {
  event: string;
  payload: Record<string, unknown>;
};

export type CursorMcpServer = {
  url?: string;
  command?: string;
  args?: unknown[];
  headers?: Record<string, unknown>;
  auth?: Record<string, unknown>;
  env?: Record<string, unknown>;
  allowed_tools?: string[];
};

export type CursorMcpConfig = {
  mcpServers: Record<string, CursorMcpServer>;
};

export type NormalizedToolEntry = {
  type: "mcp";
  server_label: string;
  server_url?: string;
  command?: string;
  args?: unknown[];
  headers?: Record<string, string>;
  auth?: Record<string, string>;
  env?: Record<string, string>;
  allowed_tools?: string[];
};

export type CliIo = {
  out: (line: string) => void;
  err: (line: string) => void;
};

const defaultIo: CliIo = {
  out: (line: string) => {
    process.stdout.write(`${line}\n`);
  },
  err: (line: string) => {
    process.stderr.write(`${line}\n`);
  },
};

function resolveEnvValue(value: unknown, envKey: string): string {
  if (typeof value !== "string") {
    return String(value);
  }
  if (value.startsWith("${env:") && value.endsWith("}")) {
    const envName = value.slice(6, -1);
    return process.env[envName] ?? "";
  }
  if (value === "") {
    return process.env[envKey] ?? "";
  }
  return value;
}

function resolveEnvMapping(mapping: Record<string, unknown>): Record<string, string> {
  return Object.entries(mapping).reduce<Record<string, string>>((acc, [key, value]) => {
    acc[key] = resolveEnvValue(value, key);
    return acc;
  }, {});
}

export function normalizeCursorMcpConfig(config: CursorMcpConfig): NormalizedToolEntry[] {
  if (!config || typeof config !== "object" || typeof config.mcpServers !== "object") {
    throw new Error("Cursor MCP config must include 'mcpServers'.");
  }

  return Object.entries(config.mcpServers).map(([serverLabel, server]) => {
    if (!server || typeof server !== "object") {
      throw new Error(`MCP server '${serverLabel}' must be an object.`);
    }

    const entry: NormalizedToolEntry = {
      type: "mcp",
      server_label: serverLabel,
    };

    if (typeof server.url === "string" && server.url.length > 0) {
      entry.server_url = server.url;
      if (server.headers && typeof server.headers === "object") {
        entry.headers = resolveEnvMapping(server.headers);
      }
      if (server.auth && typeof server.auth === "object") {
        entry.auth = resolveEnvMapping(server.auth);
      }
    } else {
      entry.command = server.command;
      entry.args = Array.isArray(server.args) ? server.args : [];
      if (server.env && typeof server.env === "object") {
        entry.env = resolveEnvMapping(server.env);
      }
    }

    if (Array.isArray(server.allowed_tools)) {
      entry.allowed_tools = server.allowed_tools.filter((name) => typeof name === "string");
    }

    return entry;
  });
}

export function buildResolvedTools(entries: NormalizedToolEntry[]): {
  resolved_tool_count: number;
  resolved_function_names: string[];
  tools: Array<Record<string, unknown>>;
} {
  const tools: Array<Record<string, unknown>> = [];
  const names: string[] = [];

  entries.forEach((entry) => {
    const allowed = entry.allowed_tools && entry.allowed_tools.length > 0 ? entry.allowed_tools : ["tool"];

    allowed.forEach((toolName) => {
      const functionName = `${entry.server_label}_${toolName}`.replace(/[^a-zA-Z0-9_]/g, "_");
      names.push(functionName);
      tools.push({
        type: "function",
        function: {
          name: functionName,
          description: `MCP tool ${toolName} from ${entry.server_label}`,
          parameters: {
            type: "object",
            properties: {},
          },
        },
      });
    });
  });

  return {
    resolved_tool_count: tools.length,
    resolved_function_names: names,
    tools,
  };
}

function emitEvent(io: CliIo, event: CliEvent, jsonMode: boolean): void {
  if (jsonMode) {
    io.out(JSON.stringify(event));
    return;
  }

  io.out(`[${event.event}]`);
  Object.entries(event.payload).forEach(([key, value]) => {
    io.out(`- ${key}: ${String(value)}`);
  });
}

function statusPayload(): Record<string, unknown> {
  const apiKey = process.env.OPIK_API_KEY || process.env.COMET_API_KEY;
  const workspace = process.env.COMET_WORKSPACE || process.env.OPIK_WORKSPACE;

  return {
    opik_configured: Boolean(apiKey),
    workspace_configured: Boolean(workspace),
    node_version: process.version,
    platform: os.platform(),
  };
}

function readCursorConfig(pathValue: string): CursorMcpConfig {
  const raw = fs.readFileSync(pathValue, "utf-8");
  const parsed = JSON.parse(raw);
  if (!parsed || typeof parsed !== "object") {
    throw new Error("Cursor MCP config file must contain a JSON object.");
  }
  return parsed as CursorMcpConfig;
}

function bashCompletionScript(prog: string): string {
  const fn = prog.replace(/-/g, "_");
  return `_${fn}_complete() {
    local cur
    cur="\${COMP_WORDS[COMP_CWORD]}"

    if [[ $COMP_CWORD -eq 1 ]]; then
        COMPREPLY=( $(compgen -W "tui completion" -- "$cur") )
        return
    fi

    case "\${COMP_WORDS[1]}" in
        tui)
            if [[ $COMP_CWORD -eq 2 ]]; then
                COMPREPLY=( $(compgen -W "status normalize-tools resolve-tools" -- "$cur") )
                return
            fi
            COMPREPLY=( $(compgen -W "--json --cursor-config --help" -- "$cur") )
            ;;
        completion)
            COMPREPLY=( $(compgen -W "--shell --prog --help bash zsh fish" -- "$cur") )
            ;;
    esac
}
complete -F _${fn}_complete ${prog}`;
}

function zshCompletionScript(prog: string): string {
  const fn = prog.replace(/-/g, "_");
  return `#compdef ${prog}

_${fn}() {
  local -a subcommands
  subcommands=(
    'tui:TUI commands'
    'completion:Print shell completion script'
  )

  if (( CURRENT == 2 )); then
    _describe 'command' subcommands
    return
  fi

  case "$words[2]" in
    tui)
      _arguments '1: :((status normalize-tools resolve-tools))' \\
        '--json[Emit newline-delimited JSON events]' \\
        '--cursor-config[Path to Cursor MCP config]:file:_files'
      ;;
    completion)
      _arguments '--shell[Target shell]:shell:(bash zsh fish)' '--prog[Program name]'
      ;;
  esac
}

_${fn} "$@"`;
}

function fishCompletionScript(prog: string): string {
  return [
    `complete -c ${prog} -f -n '__fish_use_subcommand' -a 'tui'`,
    `complete -c ${prog} -f -n '__fish_use_subcommand' -a 'completion'`,
    `complete -c ${prog} -f -n '__fish_seen_subcommand_from tui' -a 'status normalize-tools resolve-tools'`,
    `complete -c ${prog} -l json -n '__fish_seen_subcommand_from tui'`,
    `complete -c ${prog} -l cursor-config -r -n '__fish_seen_subcommand_from tui normalize-tools resolve-tools'`,
    `complete -c ${prog} -l shell -r -a 'bash zsh fish' -n '__fish_seen_subcommand_from completion'`,
    `complete -c ${prog} -l prog -r -n '__fish_seen_subcommand_from completion'`,
  ].join("\n");
}

export function getCompletionScript(shell: string, prog: string): string {
  if (shell === "bash") {
    return bashCompletionScript(prog);
  }
  if (shell === "zsh") {
    return zshCompletionScript(prog);
  }
  if (shell === "fish") {
    return fishCompletionScript(prog);
  }

  throw new Error(`Unsupported shell: ${shell}`);
}

function requireFlagValue(args: string[], flag: string): string {
  const idx = args.indexOf(flag);
  if (idx === -1 || idx === args.length - 1) {
    throw new Error(`Missing required flag: ${flag}`);
  }
  return args[idx + 1] ?? "";
}

export function runCli(argv: string[], io: CliIo = defaultIo): number {
  try {
    if (argv[0] === "completion") {
      const shell = requireFlagValue(argv, "--shell");
      const prog = argv.includes("--prog") ? requireFlagValue(argv, "--prog") : "opik-tui";
      io.out(getCompletionScript(shell, prog));
      return 0;
    }

    if (argv[0] === "tui") {
      const subcommand = argv[1];
      const jsonMode = argv.includes("--json");

      if (subcommand === "status") {
        emitEvent(io, { event: "status", payload: statusPayload() }, jsonMode);
        return 0;
      }

      if (subcommand === "normalize-tools") {
        const configPath = requireFlagValue(argv, "--cursor-config");
        const tools = normalizeCursorMcpConfig(readCursorConfig(configPath));
        emitEvent(
          io,
          {
            event: "normalized_tools",
            payload: {
              tool_count: tools.length,
              tools,
            },
          },
          jsonMode,
        );
        return 0;
      }

      if (subcommand === "resolve-tools") {
        const configPath = requireFlagValue(argv, "--cursor-config");
        const normalizedTools = normalizeCursorMcpConfig(readCursorConfig(configPath));
        const resolved = buildResolvedTools(normalizedTools);
        emitEvent(
          io,
          {
            event: "resolved_tools",
            payload: {
              normalized_tool_count: normalizedTools.length,
              ...resolved,
            },
          },
          jsonMode,
        );
        return 0;
      }
    }

    io.err("Usage: opik-tui <tui|completion> ...");
    return 1;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    emitEvent(
      io,
      {
        event: "error",
        payload: {
          error: message,
        },
      },
      argv.includes("--json"),
    );
    return 1;
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(runCli(process.argv.slice(2)));
}
