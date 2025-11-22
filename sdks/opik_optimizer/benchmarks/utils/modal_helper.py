"""Helpers for Modal setup and env-key guidance."""

from __future__ import annotations

import os
from collections.abc import Iterable
import configparser
from pathlib import Path

REQUIRED_KEYS = ["OPIK_API_KEY"]
OPTIONAL_KEYS = [
    "OPENAI_API_KEY",
    "ANTHROPIC_API_KEY",
    "GOOGLE_API_KEY",
    "GEMINI_API_KEY",
    "OPENROUTER_API_KEY",
]
OPIK_HOST_KEYS = ["OPIK_HOST", "OPIK_BASE_URL"]


def _collect(keys: Iterable[str]) -> tuple[list[str], list[str]]:
    present: list[str] = []
    missing: list[str] = []
    for k in keys:
        (present if os.getenv(k) else missing).append(k)
    return present, missing


def summarize_env() -> dict[str, list[str]]:
    """Return present/missing keys for required/optional and host overrides."""
    present_req, missing_req = _collect(REQUIRED_KEYS)
    present_opt, missing_opt = _collect(OPTIONAL_KEYS)
    present_host, missing_host = _collect(OPIK_HOST_KEYS)
    return {
        "present_required": present_req,
        "missing_required": missing_req,
        "present_optional": present_opt,
        "missing_optional": missing_opt,
        "present_host": present_host,
        "missing_host": missing_host,
    }


def build_secret_command(secret_name: str, env_summary: dict[str, list[str]]) -> str:
    keys = env_summary["present_required"] + env_summary["present_optional"]
    if not keys:
        return (
            f"# Export your keys, then run: modal secret create {secret_name} "
            'OPIK_API_KEY="$OPIK_API_KEY" OPENAI_API_KEY="$OPENAI_API_KEY" ...'
        )
    parts = [f'{k}="${{{k}}}"' for k in keys]
    return f"modal secret create {secret_name} " + " ".join(parts)


def build_placeholder_secret_command(secret_name: str) -> str:
    """Return a template command including optional keys with placeholders."""
    keys = REQUIRED_KEYS + OPTIONAL_KEYS
    parts = [f'{k}="YOUR_{k}_HERE"' for k in keys]
    return f"modal secret create {secret_name} " + " ".join(parts)


def read_opik_config(config_path: str | None = None) -> dict[str, str]:
    """Read opik config (if present) for api_key/url overrides without exposing values."""
    path = Path(config_path or Path.home() / ".opik.config")
    if not path.exists():
        return {}
    parser = configparser.ConfigParser()
    try:
        parser.read(path)
        api_key = parser.get("opik", "api_key", fallback=None)
        url_override = parser.get("opik", "url_override", fallback=None)
        workspace = parser.get("opik", "workspace", fallback=None)
        data: dict[str, str] = {}
        if api_key:
            data["api_key"] = api_key
        if url_override:
            data["url_override"] = url_override
        if workspace:
            data["workspace"] = workspace
        return data
    except Exception:
        return {}
