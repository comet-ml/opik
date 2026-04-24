from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.common import (
    is_binary,
    resolve_text_file,
    revalidate_path,
    validate_path,
)


class TestValidatePath:
    def test_validate_path__relative_path__resolves_correctly(
        self, tmp_path: Path
    ) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "agent.py").write_text("code")
        result = validate_path("src/agent.py", tmp_path)
        assert result == tmp_path / "src" / "agent.py"

    def test_validate_path__absolute_inside_repo__returns_path(
        self, tmp_path: Path
    ) -> None:
        f = tmp_path / "file.py"
        f.write_text("x")
        result = validate_path(str(f), tmp_path)
        assert result == f

    def test_validate_path__dotdot_traversal__raises_error(
        self, tmp_path: Path
    ) -> None:
        with pytest.raises(CommandError) as exc_info:
            validate_path("../../etc/passwd", tmp_path)
        assert exc_info.value.code == "path_traversal"

    def test_validate_path__absolute_outside_repo__raises_error(
        self, tmp_path: Path
    ) -> None:
        with pytest.raises(CommandError) as exc_info:
            validate_path("/etc/passwd", tmp_path)
        assert exc_info.value.code == "path_traversal"

    def test_validate_path__symlink_escape__raises_error(self, tmp_path: Path) -> None:
        evil_target = tmp_path.parent / "evil.txt"
        evil_target.write_text("secret")
        link = tmp_path / "link.txt"
        link.symlink_to(evil_target)

        with pytest.raises(CommandError) as exc_info:
            validate_path("link.txt", tmp_path)
        assert exc_info.value.code == "path_traversal"

        evil_target.unlink()

    def test_validate_path__symlink_inside_repo__returns_resolved(
        self, tmp_path: Path
    ) -> None:
        real = tmp_path / "real.py"
        real.write_text("code")
        link = tmp_path / "link.py"
        link.symlink_to(real)
        result = validate_path("link.py", tmp_path)
        assert result == real

    def test_validate_path__empty_path__raises_error(self, tmp_path: Path) -> None:
        with pytest.raises(CommandError) as exc_info:
            validate_path("", tmp_path)
        assert exc_info.value.code == "path_traversal"

    def test_validate_path__normal_file__returns_path(self, tmp_path: Path) -> None:
        (tmp_path / "app.py").write_text("print('hi')")
        result = validate_path("app.py", tmp_path)
        assert result == tmp_path / "app.py"


class TestRevalidatePath:
    def test_revalidate_path__valid_path__no_error(self, tmp_path: Path) -> None:
        f = tmp_path / "file.py"
        f.write_text("x")
        revalidate_path(f, tmp_path)

    def test_revalidate_path__outside_repo__raises_error(self, tmp_path: Path) -> None:
        with pytest.raises(CommandError) as exc_info:
            revalidate_path(Path("/etc/passwd"), tmp_path)
        assert exc_info.value.code == "path_traversal"


class TestIsBinary:
    def test_is_binary__text_file__returns_false(self, tmp_path: Path) -> None:
        f = tmp_path / "code.py"
        f.write_text("def hello(): pass")
        assert is_binary(f) is False

    def test_is_binary__null_bytes__returns_true(self, tmp_path: Path) -> None:
        f = tmp_path / "binary.dat"
        f.write_bytes(b"header\x00\x01\x02data")
        assert is_binary(f) is True

    def test_is_binary__empty_file__returns_false(self, tmp_path: Path) -> None:
        f = tmp_path / "empty"
        f.write_text("")
        assert is_binary(f) is False

    def test_is_binary__nonexistent_file__returns_false(self, tmp_path: Path) -> None:
        assert is_binary(tmp_path / "nope") is False


class TestResolveTextFile:
    def test_resolve_text_file__directory_path__raises_error(
        self, tmp_path: Path
    ) -> None:
        subdir = tmp_path / "mydir"
        subdir.mkdir()
        with pytest.raises(CommandError) as exc_info:
            resolve_text_file("mydir", tmp_path)
        assert exc_info.value.code == "file_not_found"
