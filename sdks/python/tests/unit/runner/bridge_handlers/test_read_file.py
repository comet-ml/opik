from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.read_file import ReadFileHandler


class TestReadFile:
    def test_read_file__small_file__returns_full_content(self, tmp_path: Path) -> None:
        f = tmp_path / "small.py"
        f.write_text("line1\nline2\nline3\n")
        handler = ReadFileHandler(tmp_path)
        result = handler.execute({"path": "small.py"}, timeout=30.0)
        assert result["content"] == "line1\nline2\nline3\n"
        assert result["total_lines"] == 3
        assert result["truncated"] is False
        assert result["encoding"] == "utf-8"

    def test_read_file__large_file__truncates_by_lines(self, tmp_path: Path) -> None:
        f = tmp_path / "big.py"
        f.write_text("".join(f"line {i}\n" for i in range(5000)))
        handler = ReadFileHandler(tmp_path)
        result = handler.execute({"path": "big.py"}, timeout=30.0)
        assert result["total_lines"] == 5000
        assert result["truncated"] is True
        lines = result["content"].splitlines()
        assert len(lines) <= 2000

    def test_read_file__large_file__truncates_by_bytes(self, tmp_path: Path) -> None:
        f = tmp_path / "huge.py"
        f.write_text("x" * (600 * 1024))
        handler = ReadFileHandler(tmp_path)
        result = handler.execute({"path": "huge.py"}, timeout=30.0)
        assert result["truncated"] is True
        assert len(result["content"].encode("utf-8")) <= 512 * 1024

    def test_read_file__offset_and_limit__returns_slice(self, tmp_path: Path) -> None:
        f = tmp_path / "lines.py"
        f.write_text("".join(f"line{i}\n" for i in range(200)))
        handler = ReadFileHandler(tmp_path)
        result = handler.execute(
            {"path": "lines.py", "offset": 100, "limit": 50}, timeout=30.0
        )
        assert result["content"].startswith("line100\n")
        lines = result["content"].splitlines()
        assert len(lines) == 50

    def test_read_file__offset_beyond_file__returns_empty(self, tmp_path: Path) -> None:
        f = tmp_path / "short.py"
        f.write_text("one\ntwo\n")
        handler = ReadFileHandler(tmp_path)
        result = handler.execute({"path": "short.py", "offset": 9999}, timeout=30.0)
        assert result["content"] == ""
        assert result["total_lines"] == 2

    def test_read_file__binary_file__raises_error(self, tmp_path: Path) -> None:
        f = tmp_path / "bin.dat"
        f.write_bytes(b"\x00\x01\x02")
        handler = ReadFileHandler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "bin.dat"}, timeout=30.0)
        assert exc_info.value.code == "binary_file"

    def test_read_file__file_not_found__raises_error(self, tmp_path: Path) -> None:
        handler = ReadFileHandler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "nope.py"}, timeout=30.0)
        assert exc_info.value.code == "file_not_found"

    def test_read_file__path_traversal__raises_error(self, tmp_path: Path) -> None:
        handler = ReadFileHandler(tmp_path)
        with pytest.raises(CommandError) as exc_info:
            handler.execute({"path": "../../etc/passwd"}, timeout=30.0)
        assert exc_info.value.code == "path_traversal"

    def test_read_file__utf8_content__preserves_encoding(self, tmp_path: Path) -> None:
        f = tmp_path / "unicode.py"
        f.write_text("café = '☕'\n", encoding="utf-8")
        handler = ReadFileHandler(tmp_path)
        result = handler.execute({"path": "unicode.py"}, timeout=30.0)
        assert "café" in result["content"]
        assert "☕" in result["content"]

    def test_read_file__empty_file__returns_empty_content(self, tmp_path: Path) -> None:
        f = tmp_path / "empty.py"
        f.write_text("")
        handler = ReadFileHandler(tmp_path)
        result = handler.execute({"path": "empty.py"}, timeout=30.0)
        assert result["content"] == ""
        assert result["total_lines"] == 0
        assert result["truncated"] is False
