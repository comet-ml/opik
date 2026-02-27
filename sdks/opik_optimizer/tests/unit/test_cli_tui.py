from __future__ import annotations

import json

from opik_optimizer import cli


def test_tui_status_json_output(capsys) -> None:
    code = cli.main(["tui", "status", "--json"])
    assert code == 0

    payload = json.loads(capsys.readouterr().out.strip())
    assert payload["event"] == "status"
    assert "opik_configured" in payload["payload"]


def test_tui_normalize_tools_json_output(tmp_path, capsys) -> None:
    config_file = tmp_path / "cursor.json"
    config_file.write_text(
        json.dumps(
            {
                "mcpServers": {
                    "context7": {
                        "url": "https://mcp.context7.com/mcp",
                    }
                }
            }
        ),
        encoding="utf-8",
    )

    code = cli.main(
        [
            "tui",
            "normalize-tools",
            "--cursor-config",
            str(config_file),
            "--json",
        ]
    )
    assert code == 0

    payload = json.loads(capsys.readouterr().out.strip())
    assert payload["event"] == "normalized_tools"
    assert payload["payload"]["tool_count"] == 1


def test_tui_resolve_tools_json_output(monkeypatch, tmp_path, capsys) -> None:
    config_file = tmp_path / "cursor.json"
    config_file.write_text(
        json.dumps(
            {
                "mcpServers": {
                    "context7": {
                        "url": "https://mcp.context7.com/mcp",
                    }
                }
            }
        ),
        encoding="utf-8",
    )

    def _fake_resolve_toolcalling_tools(_tools, function_map=None):
        return (
            [
                {
                    "type": "function",
                    "function": {
                        "name": "context7_resolve_library_id",
                        "description": "Resolve Context7 library",
                        "parameters": {"type": "object", "properties": {}},
                    },
                }
            ],
            function_map or {},
        )

    monkeypatch.setattr(cli, "resolve_toolcalling_tools", _fake_resolve_toolcalling_tools)

    code = cli.main(
        [
            "tui",
            "resolve-tools",
            "--cursor-config",
            str(config_file),
            "--json",
        ]
    )
    assert code == 0

    payload = json.loads(capsys.readouterr().out.strip())
    assert payload["event"] == "resolved_tools"
    assert payload["payload"]["resolved_function_names"] == ["context7_resolve_library_id"]


def test_completion_bash_script_contains_program_name(capsys) -> None:
    code = cli.main(["completion", "--shell", "bash", "--prog", "opik-optimizer"])
    assert code == 0

    output = capsys.readouterr().out
    assert "complete -F _opik_optimizer_complete opik-optimizer" in output
