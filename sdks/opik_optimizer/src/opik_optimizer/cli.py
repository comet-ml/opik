"""CLI entrypoint for opik_optimizer.

This provides an agent-friendly TUI surface with optional JSON event output.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from rich.console import Console

from .utils.toolcalling.normalize.config_input import cursor_mcp_config_to_tools
from .utils.toolcalling.normalize.tool_factory import resolve_toolcalling_tools


@dataclass(frozen=True)
class CliEvent:
    """Serializable event emitted by the CLI."""

    event: str
    payload: dict[str, Any]


def _emit_event(*, console: Console, event: CliEvent, json_mode: bool) -> None:
    if json_mode:
        print(
            json.dumps(
                {
                    "event": event.event,
                    "payload": event.payload,
                },
                sort_keys=True,
            )
        )
        return

    console.print(f"[{event.event}]")
    for key, value in event.payload.items():
        console.print(f"- {key}: {value}")


def _load_json_file(path: str) -> dict[str, Any]:
    with Path(path).expanduser().open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError("expected a JSON object at the root")
    return data


def _status_payload() -> dict[str, Any]:
    api_key = os.getenv("OPIK_API_KEY") or os.getenv("COMET_API_KEY")
    workspace = os.getenv("COMET_WORKSPACE") or os.getenv("OPIK_WORKSPACE")
    return {
        "opik_configured": bool(api_key),
        "workspace_configured": bool(workspace),
        "python_version": platform.python_version(),
        "platform": platform.platform(),
    }


def _normalize_tools_payload(cursor_config: dict[str, Any]) -> dict[str, Any]:
    tools = cursor_mcp_config_to_tools(cursor_config)
    return {
        "tool_count": len(tools),
        "tools": tools,
    }


def _resolve_tools_payload(cursor_config: dict[str, Any]) -> dict[str, Any]:
    tools = cursor_mcp_config_to_tools(cursor_config)
    resolved_tools, _resolved_map = resolve_toolcalling_tools(tools, function_map=None)
    function_names = [
        tool.get("function", {}).get("name")
        for tool in resolved_tools
        if isinstance(tool, dict)
    ]
    return {
        "normalized_tool_count": len(tools),
        "resolved_tool_count": len(resolved_tools),
        "resolved_function_names": [
            name for name in function_names if isinstance(name, str) and name
        ],
        "tools": resolved_tools,
    }


def _bash_completion_script(prog: str) -> str:
    return f"""_{prog.replace('-', '_')}_complete() {{
    local cur prev
    COMPREPLY=()
    cur="${{COMP_WORDS[COMP_CWORD]}}"
    prev="${{COMP_WORDS[COMP_CWORD-1]}}"

    if [[ $COMP_CWORD -eq 1 ]]; then
        COMPREPLY=( $(compgen -W "tui completion" -- "$cur") )
        return
    fi

    case "${{COMP_WORDS[1]}}" in
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
}}
complete -F _{prog.replace('-', '_')}_complete {prog}
"""


def _zsh_completion_script(prog: str) -> str:
    return f"""#compdef {prog}

_{prog.replace('-', '_')}() {{
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
}}

_{prog.replace('-', '_')} "$@"
"""


def _fish_completion_script(prog: str) -> str:
    return "\n".join(
        [
            f"complete -c {prog} -f -n '__fish_use_subcommand' -a 'tui'",
            f"complete -c {prog} -f -n '__fish_use_subcommand' -a 'completion'",
            f"complete -c {prog} -f -n '__fish_seen_subcommand_from tui' -a 'status normalize-tools resolve-tools'",
            f"complete -c {prog} -l json -n '__fish_seen_subcommand_from tui'",
            f"complete -c {prog} -l cursor-config -r -n '__fish_seen_subcommand_from tui normalize-tools resolve-tools'",
            f"complete -c {prog} -l shell -r -a 'bash zsh fish' -n '__fish_seen_subcommand_from completion'",
            f"complete -c {prog} -l prog -r -n '__fish_seen_subcommand_from completion'",
        ]
    )


def _render_completion(*, shell: str, prog: str) -> str:
    if shell == "bash":
        return _bash_completion_script(prog)
    if shell == "zsh":
        return _zsh_completion_script(prog)
    if shell == "fish":
        return _fish_completion_script(prog)
    raise ValueError(f"Unsupported shell: {shell}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="opik-optimizer",
        description="Opik Optimizer CLI",
    )
    subparsers = parser.add_subparsers(dest="command")

    tui_parser = subparsers.add_parser("tui", help="Agent-friendly TUI commands")
    tui_subparsers = tui_parser.add_subparsers(dest="tui_command")

    status_parser = tui_subparsers.add_parser(
        "status", help="Print Opik and runtime status"
    )
    status_parser.add_argument(
        "--json", action="store_true", help="Emit JSON event output"
    )

    normalize_parser = tui_subparsers.add_parser(
        "normalize-tools", help="Normalize Cursor MCP config into tool entries"
    )
    normalize_parser.add_argument(
        "--cursor-config", required=True, help="Path to Cursor MCP config JSON file"
    )
    normalize_parser.add_argument(
        "--json", action="store_true", help="Emit JSON event output"
    )

    resolve_parser = tui_subparsers.add_parser(
        "resolve-tools", help="Resolve MCP tools into function-call tool definitions"
    )
    resolve_parser.add_argument(
        "--cursor-config", required=True, help="Path to Cursor MCP config JSON file"
    )
    resolve_parser.add_argument(
        "--json", action="store_true", help="Emit JSON event output"
    )

    completion_parser = subparsers.add_parser(
        "completion", help="Print shell completion script"
    )
    completion_parser.add_argument(
        "--shell",
        required=True,
        choices=["bash", "zsh", "fish"],
        help="Target shell",
    )
    completion_parser.add_argument(
        "--prog",
        default="opik-optimizer",
        help="Program name used in the generated script",
    )

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    console = Console()

    try:
        if args.command == "completion":
            console.print(_render_completion(shell=args.shell, prog=args.prog))
            return 0

        if args.command == "tui":
            if args.tui_command == "status":
                _emit_event(
                    console=console,
                    event=CliEvent("status", _status_payload()),
                    json_mode=bool(args.json),
                )
                return 0

            if args.tui_command == "normalize-tools":
                payload = _normalize_tools_payload(_load_json_file(args.cursor_config))
                _emit_event(
                    console=console,
                    event=CliEvent("normalized_tools", payload),
                    json_mode=bool(args.json),
                )
                return 0

            if args.tui_command == "resolve-tools":
                payload = _resolve_tools_payload(_load_json_file(args.cursor_config))
                _emit_event(
                    console=console,
                    event=CliEvent("resolved_tools", payload),
                    json_mode=bool(args.json),
                )
                return 0

        parser.print_help()
        return 1
    except Exception as exc:
        _emit_event(
            console=console,
            event=CliEvent(
                "error",
                {
                    "error": str(exc),
                    "type": type(exc).__name__,
                },
            ),
            json_mode=bool(getattr(args, "json", False)),
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
