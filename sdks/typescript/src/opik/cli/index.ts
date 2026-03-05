import { OpikClient } from "@/client/Client";

export type CliIo = {
  out: (line: string) => void;
  err: (line: string) => void;
};

export type CliDeps = {
  createClient: () => OpikClient;
};

const defaultIo: CliIo = {
  out: (line: string) => process.stdout.write(`${line}\n`),
  err: (line: string) => process.stderr.write(`${line}\n`),
};

const defaultDeps: CliDeps = {
  createClient: () => new OpikClient(),
};

function emitEvent(io: CliIo, event: string, payload: Record<string, unknown>, json: boolean): void {
  if (json) {
    io.out(JSON.stringify({ event, payload }));
    return;
  }

  io.out(`[${event}]`);
  Object.entries(payload).forEach(([key, value]) => {
    io.out(`- ${key}: ${String(value)}`);
  });
}

function flag(args: string[], name: string): boolean {
  return args.includes(name);
}

function value(args: string[], name: string): string | undefined {
  const idx = args.indexOf(name);
  if (idx < 0 || idx + 1 >= args.length) {
    return undefined;
  }
  return args[idx + 1];
}

function required(args: string[], name: string): string {
  const v = value(args, name);
  if (!v) {
    throw new Error(`Missing required option: ${name}`);
  }
  return v;
}

function intValue(args: string[], name: string, defaultValue: number): number {
  const raw = value(args, name);
  if (!raw) {
    return defaultValue;
  }
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed) || parsed <= 0) {
    throw new Error(`Invalid numeric value for ${name}: ${raw}`);
  }
  return parsed;
}

function completionScript(shell: string): string {
  if (shell === "bash") {
    return `# Bash completion for opik
_opik_complete() {
  local cur
  cur="\${COMP_WORDS[COMP_CWORD]}"
  if [[ $COMP_CWORD -eq 1 ]]; then
    COMPREPLY=( $(compgen -W "query" -- "$cur") )
    return
  fi
  if [[ "\${COMP_WORDS[1]}" == "query" ]]; then
    if [[ $COMP_CWORD -eq 2 ]]; then
      COMPREPLY=( $(compgen -W "projects datasets dataset prompts prompt traces spans completion" -- "$cur") )
      return
    fi
    COMPREPLY=( $(compgen -W "--json --name --commit --chat --project-name --trace-id --filter --limit --shell" -- "$cur") )
  fi
}
complete -F _opik_complete opik`;
  }

  if (shell === "zsh") {
    return `#compdef opik

_opik() {
  local -a subcommands
  subcommands=(
    'query:Query Opik resources'
  )

  if (( CURRENT == 2 )); then
    _describe 'command' subcommands
    return
  fi

  if [[ "$words[2]" == "query" ]]; then
    _arguments '1: :((projects datasets dataset prompts prompt traces spans completion))' \\
      '--json[Emit JSON event output]' \\
      '--name[Name filter]' \\
      '--commit[Prompt commit]' \\
      '--chat[Use chat prompt retrieval]' \\
      '--project-name[Project name]' \\
      '--trace-id[Trace id]' \\
      '--filter[OQL filter string]' \\
      '--limit[Limit]' \\
      '--shell[Target shell]:shell:(bash zsh fish)'
  fi
}

_opik "$@"`;
  }

  if (shell === "fish") {
    return [
      "complete -c opik -f -n '__fish_use_subcommand' -a 'query'",
      "complete -c opik -f -n '__fish_seen_subcommand_from query' -a 'projects datasets dataset prompts prompt traces spans completion'",
      "complete -c opik -l json -n '__fish_seen_subcommand_from query'",
      "complete -c opik -l name -r -n '__fish_seen_subcommand_from query'",
      "complete -c opik -l commit -r -n '__fish_seen_subcommand_from query prompt'",
      "complete -c opik -l chat -n '__fish_seen_subcommand_from query prompt'",
      "complete -c opik -l project-name -r -n '__fish_seen_subcommand_from query traces spans'",
      "complete -c opik -l trace-id -r -n '__fish_seen_subcommand_from query spans'",
      "complete -c opik -l filter -r -n '__fish_seen_subcommand_from query traces spans'",
      "complete -c opik -l limit -r -n '__fish_seen_subcommand_from query projects datasets traces spans'",
      "complete -c opik -l shell -r -a 'bash zsh fish' -n '__fish_seen_subcommand_from query completion'",
    ].join("\n");
  }

  throw new Error(`Unsupported shell: ${shell}`);
}

export async function runCli(
  argv: string[],
  io: CliIo = defaultIo,
  deps: CliDeps = defaultDeps
): Promise<number> {
  try {
    if (argv[0] !== "query") {
      io.err("Usage: opik query <projects|datasets|dataset|prompts|prompt|traces|spans|completion> [options]");
      return 1;
    }

    const subcommand = argv[1];
    const asJson = flag(argv, "--json");
    const client = deps.createClient();

    if (subcommand === "projects") {
      const name = value(argv, "--name");
      const limit = intValue(argv, "--limit", 25);
      const response = await client.api.projects.findProjects({ name, size: limit });
      const items = response.content ?? [];
      emitEvent(io, "projects", { count: items.length, items }, asJson);
      return 0;
    }

    if (subcommand === "datasets") {
      const name = value(argv, "--name");
      const limit = intValue(argv, "--limit", 25);
      const response = await client.api.datasets.findDatasets({ name, size: limit });
      const items = response.content ?? [];
      emitEvent(io, "datasets", { count: items.length, items }, asJson);
      return 0;
    }

    if (subcommand === "dataset") {
      const name = required(argv, "--name");
      const dataset = await client.getDataset(name);
      emitEvent(io, "dataset", { name, dataset }, asJson);
      return 0;
    }

    if (subcommand === "prompts") {
      const filterString = value(argv, "--filter");
      const prompts = await client.searchPrompts(filterString);
      emitEvent(io, "prompts", { count: prompts.length, items: prompts }, asJson);
      return 0;
    }

    if (subcommand === "prompt") {
      const name = required(argv, "--name");
      const commit = value(argv, "--commit");
      const chat = flag(argv, "--chat");
      const result = chat
        ? await client.getChatPrompt({ name, commit })
        : await client.getPrompt({ name, commit });
      emitEvent(io, chat ? "chat_prompt" : "prompt", { name, commit, result }, asJson);
      return 0;
    }

    if (subcommand === "traces") {
      const projectName = value(argv, "--project-name");
      const filterString = value(argv, "--filter");
      const limit = intValue(argv, "--limit", 100);
      const items = await client.searchTraces({
        projectName,
        filterString,
        maxResults: limit,
      });
      emitEvent(io, "traces", { count: items.length, items }, asJson);
      return 0;
    }

    if (subcommand === "spans") {
      const projectName = value(argv, "--project-name");
      const filterString = value(argv, "--filter");
      const traceId = value(argv, "--trace-id");
      const limit = intValue(argv, "--limit", 100);
      const spanFilter = [traceId ? `trace_id = "${traceId}"` : null, filterString]
        .filter((part): part is string => Boolean(part))
        .join(" AND ");
      const items = await client.searchSpans({
        projectName,
        filterString: spanFilter.length > 0 ? spanFilter : undefined,
        maxResults: limit,
      });
      emitEvent(io, "spans", { count: items.length, items }, asJson);
      return 0;
    }

    if (subcommand === "completion") {
      const shell = required(argv, "--shell");
      io.out(completionScript(shell));
      return 0;
    }

    io.err(`Unknown subcommand: ${subcommand ?? "<missing>"}`);
    return 1;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    emitEvent(io, "error", { error: message }, argv.includes("--json"));
    return 1;
  }
}
