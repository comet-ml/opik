from pathlib import Path

import pytest

from opik.runner.bridge_handlers import CommandError
from opik.runner.bridge_handlers.path_utils import is_binary, validate_path


class TestValidatePath:
    def test_relative_path__resolves(self, tmp_path: Path) -> None:
        (tmp_path / "src").mkdir()
        (tmp_path / "src" / "agent.py").write_text("code")
        result = validate_path("src/agent.py", tmp_path)
        assert result == tmp_path / "src" / "agent.py"

    def test_absolute_inside_repo__ok(self, tmp_path: Path) -> None:
        f = tmp_path / "file.py"
        f.write_text("x")
        result = validate_path(str(f), tmp_path)
        assert result == f

    def test_dotdot_traversal__raises(self, tmp_path: Path) -> None:
        with pytest.raises(CommandError) as exc_info:
            validate_path("../../etc/passwd", tmp_path)
        assert exc_info.value.code == "path_traversal"

    def test_absolute_outside_repo__raises(self, tmp_path: Path) -> None:
        with pytest.raises(CommandError) as exc_info:
            validate_path("/etc/passwd", tmp_path)
        assert exc_info.value.code == "path_traversal"

    def test_symlink_escape__raises(self, tmp_path: Path) -> None:
        evil_target = tmp_path.parent / "evil.txt"
        evil_target.write_text("secret")
        link = tmp_path / "link.txt"
        link.symlink_to(evil_target)

        with pytest.raises(CommandError) as exc_info:
            validate_path("link.txt", tmp_path)
        assert exc_info.value.code == "path_traversal"

        evil_target.unlink()

    def test_symlink_inside_repo__ok(self, tmp_path: Path) -> None:
        real = tmp_path / "real.py"
        real.write_text("code")
        link = tmp_path / "link.py"
        link.symlink_to(real)
        result = validate_path("link.py", tmp_path)
        assert result == real

    def test_sensitive_env__raises(self, tmp_path: Path) -> None:
        (tmp_path / ".env").write_text("SECRET=foo")
        with pytest.raises(CommandError) as exc_info:
            validate_path(".env", tmp_path)
        assert exc_info.value.code == "sensitive_path"

    def test_sensitive_env_variant__raises(self, tmp_path: Path) -> None:
        (tmp_path / ".env.local").write_text("SECRET=foo")
        with pytest.raises(CommandError) as exc_info:
            validate_path(".env.local", tmp_path)
        assert exc_info.value.code == "sensitive_path"

    def test_sensitive_pem__raises(self, tmp_path: Path) -> None:
        (tmp_path / "cert.pem").write_text("-----BEGIN")
        with pytest.raises(CommandError) as exc_info:
            validate_path("cert.pem", tmp_path)
        assert exc_info.value.code == "sensitive_path"

    def test_sensitive_key__raises(self, tmp_path: Path) -> None:
        (tmp_path / "private.key").write_text("key")
        with pytest.raises(CommandError) as exc_info:
            validate_path("private.key", tmp_path)
        assert exc_info.value.code == "sensitive_path"

    def test_sensitive_nested__raises(self, tmp_path: Path) -> None:
        d = tmp_path / "config"
        d.mkdir()
        (d / "secrets.json").write_text("{}")
        with pytest.raises(CommandError) as exc_info:
            validate_path("config/secrets.json", tmp_path)
        assert exc_info.value.code == "sensitive_path"

    def test_empty_path__raises(self, tmp_path: Path) -> None:
        with pytest.raises(CommandError) as exc_info:
            validate_path("", tmp_path)
        assert exc_info.value.code == "path_traversal"

    def test_normal_file__ok(self, tmp_path: Path) -> None:
        (tmp_path / "app.py").write_text("print('hi')")
        result = validate_path("app.py", tmp_path)
        assert result == tmp_path / "app.py"


class TestIsBinary:
    def test_text_file__false(self, tmp_path: Path) -> None:
        f = tmp_path / "code.py"
        f.write_text("def hello(): pass")
        assert is_binary(f) is False

    def test_null_bytes__true(self, tmp_path: Path) -> None:
        f = tmp_path / "binary.dat"
        f.write_bytes(b"header\x00\x01\x02data")
        assert is_binary(f) is True

    def test_empty_file__false(self, tmp_path: Path) -> None:
        f = tmp_path / "empty"
        f.write_text("")
        assert is_binary(f) is False

    def test_nonexistent__false(self, tmp_path: Path) -> None:
        assert is_binary(tmp_path / "nope") is False
