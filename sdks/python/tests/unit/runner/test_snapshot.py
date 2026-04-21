import platform
import sys
from pathlib import Path

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
