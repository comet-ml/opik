import json
from functools import cache
from importlib.resources import files
from typing import Any

_DATA = files("opik_genui") / "data"


def _read_text(name: str) -> str:
    return (_DATA / name).read_text(encoding="utf-8")


@cache
def _engine() -> dict[str, Any]:
    return json.loads(_read_text("engine.json"))


ENGINE_VERSION: int = _engine()["engine_version"]
AREAS: tuple[str, ...] = tuple(_engine()["areas"])
LIBRARIES: tuple[str, ...] = tuple(_engine()["libraries"])
MAX_HTML_LENGTH: int = _engine()["max_html_length"]
TOKENS: dict[str, dict[str, str]] = _engine()["tokens"]


def prompt(area: str) -> str:
    if area not in AREAS:
        raise ValueError(f'unknown area "{area}"; expected one of {", ".join(AREAS)}')
    return _read_text(f"prompt-{area}.txt").removesuffix("\n")


__all__ = [
    "AREAS",
    "ENGINE_VERSION",
    "LIBRARIES",
    "MAX_HTML_LENGTH",
    "TOKENS",
    "prompt",
]
