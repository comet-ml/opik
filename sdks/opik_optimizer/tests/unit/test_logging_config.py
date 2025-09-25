import logging
import sys
import types
from pathlib import Path
from collections.abc import Generator

import pytest

from opik_optimizer.logging_config import setup_logging


try:  # pragma: no cover - executed only when rich is installed normally
    import rich.logging  # noqa: F401
except ModuleNotFoundError:  # pragma: no cover
    rich_pkg = types.ModuleType("rich")
    rich_logging_pkg = types.ModuleType("rich.logging")

    class RichHandler(logging.Handler):
        def __init__(self, *args: object, **kwargs: object) -> None:
            super().__init__()

        def emit(
            self, record: logging.LogRecord
        ) -> None:  # pragma: no cover - no output in tests
            pass

    rich_logging_pkg.RichHandler = RichHandler  # type: ignore[attr-defined]
    sys.modules["rich"] = rich_pkg
    sys.modules["rich.logging"] = rich_logging_pkg
    rich_pkg.logging = rich_logging_pkg  # type: ignore[attr-defined]


root = Path(__file__).resolve().parents[2]
src_root = root / "src"

if "opik_optimizer" not in sys.modules:
    pkg = types.ModuleType("opik_optimizer")
    pkg.__path__ = [str(src_root / "opik_optimizer")]
    sys.modules["opik_optimizer"] = pkg


@pytest.fixture(autouse=True)
def reset_logging_state(monkeypatch: pytest.MonkeyPatch) -> Generator[None, None, None]:
    monkeypatch.delenv("OPIK_LOG_LEVEL", raising=False)
    # Ensure each test starts from a clean slate
    setup_logging(level=logging.WARNING, force=True)
    yield
    setup_logging(level=logging.WARNING, force=True)


def test_setup_logging_accepts_string_level() -> None:
    setup_logging(level="INFO", force=True)
    package_logger = logging.getLogger("opik_optimizer")
    assert package_logger.level == logging.INFO

    setup_logging(level="DEBUG")
    assert package_logger.level == logging.DEBUG


def test_setup_logging_honours_env_override(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("OPIK_LOG_LEVEL", "ERROR")
    setup_logging(level="INFO", force=True)
    package_logger = logging.getLogger("opik_optimizer")
    assert package_logger.level == logging.ERROR
