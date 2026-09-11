import platform
import sys
from pathlib import Path
from typing import Dict

import pytest

from opik.runner.snapshot import build_checklist


class TestBuildChecklist:
    def test_build_checklist__project_root__returned_as_string(
        self, tmp_path: Path
    ) -> None:
        result = build_checklist(tmp_path, command=None)

        assert result["project_root"] == str(tmp_path)

    def test_build_checklist__empty_repo__all_expected_keys_present(
        self, tmp_path: Path
    ) -> None:
        result = build_checklist(tmp_path, command=None)

        expected_keys = {
            "runner_type",
            "command",
            "platform",
            "project_root",
            "python_executable",
            "file_tree",
            "instrumentation",
            "instrumentation_matches",
        }
        assert set(result.keys()) == expected_keys

        assert result["python_executable"] == sys.executable
        assert result["platform"] == platform.system().lower()
        assert result["project_root"] != result["python_executable"]

    def test_build_checklist__repo_with_instrumented_files__tree_and_matches_populated(
        self, tmp_path: Path
    ) -> None:
        (tmp_path / "app.py").write_text("import opik\n")
        (tmp_path / "main.ts").write_text('from "opik-openai";\n')

        result = build_checklist(tmp_path, command=["python", "app.py"])

        assert "app.py" in result["file_tree"]
        assert "main.ts" in result["file_tree"]
        assert result["instrumentation"]["tracing"] is True
        assert result["command"] == "python app.py"

        matches = result["instrumentation_matches"]
        assert isinstance(matches, list)
        assert len(matches) > 0
        for match in matches:
            assert isinstance(match, str)
            assert match.count(":") >= 2

    def test_build_checklist__command_none__serialized_as_none(
        self, tmp_path: Path
    ) -> None:
        result = build_checklist(tmp_path, command=None)

        assert result["command"] is None

    @pytest.mark.parametrize(
        "files,expected",
        [
            pytest.param(
                {"app.py": "x = 1\n"},
                {"tracing": False, "entrypoint": False, "prompts": False},
                id="empty_repo",
            ),
            pytest.param(
                {"app.py": "import opik\n@opik.track\ndef f():\n    pass\n"},
                {"tracing": True, "entrypoint": False, "prompts": False},
                id="tracing_only",
            ),
            pytest.param(
                {"agent.py": "entrypoint = True\n"},
                {"tracing": False, "entrypoint": True, "prompts": False},
                id="entrypoint_python",
            ),
            pytest.param(
                {"agent.ts": "export const config = { entrypoint: true };\n"},
                {"tracing": False, "entrypoint": True, "prompts": False},
                id="entrypoint_ts",
            ),
            pytest.param(
                {"app.py": "client.get_prompt('hello')\n"},
                {"tracing": False, "entrypoint": False, "prompts": True},
                id="get_prompt_python",
            ),
            pytest.param(
                {"app.py": "client.create_prompt('hello', 'tmpl')\n"},
                {"tracing": False, "entrypoint": False, "prompts": True},
                id="create_prompt_python",
            ),
            pytest.param(
                {"app.py": "client.get_chat_prompt('hello')\n"},
                {"tracing": False, "entrypoint": False, "prompts": True},
                id="get_chat_prompt_python",
            ),
            pytest.param(
                {"app.py": "client.create_chat_prompt('hello', [])\n"},
                {"tracing": False, "entrypoint": False, "prompts": True},
                id="create_chat_prompt_python",
            ),
            pytest.param(
                {"main.ts": "await client.getPrompt('hello');\n"},
                {"tracing": False, "entrypoint": False, "prompts": True},
                id="get_prompt_ts",
            ),
            pytest.param(
                {"main.ts": "await client.prompts.createPrompt({});\n"},
                {"tracing": False, "entrypoint": False, "prompts": True},
                id="create_prompt_ts",
            ),
            pytest.param(
                {"main.ts": "await client.getChatPrompt('hello');\n"},
                {"tracing": False, "entrypoint": False, "prompts": True},
                id="get_chat_prompt_ts",
            ),
            pytest.param(
                {"main.ts": "await client.createChatPrompt({});\n"},
                {"tracing": False, "entrypoint": False, "prompts": True},
                id="create_chat_prompt_ts",
            ),
            pytest.param(
                {
                    "app.py": (
                        "import opik\n"
                        "entrypoint = True\n"
                        "client.get_chat_prompt('hello')\n"
                    ),
                },
                {"tracing": True, "entrypoint": True, "prompts": True},
                id="all_flags_set",
            ),
        ],
    )
    def test_build_checklist__instrumentation_flags(
        self,
        tmp_path: Path,
        files: Dict[str, str],
        expected: Dict[str, bool],
    ) -> None:
        for name, content in files.items():
            (tmp_path / name).write_text(content)

        result = build_checklist(tmp_path, command=None)

        assert result["instrumentation"] == expected
