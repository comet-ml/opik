#!/usr/bin/env python3
"""
Sync LLM provider model definitions across backend Java enums and frontend TypeScript.

Add-only: new models are added automatically, stale models are reported but never
removed (to avoid breaking references across the codebase).

Sources (in priority order):
  - OpenRouter: https://openrouter.ai/api/v1/models (public, no key needed)
  - OpenAI: https://api.openai.com/v1/models (needs OPENAI_API_KEY)
  - Anthropic: https://api.anthropic.com/v1/models (needs ANTHROPIC_API_KEY)
  - Gemini: https://generativelanguage.googleapis.com/v1beta/models (needs GEMINI_API_KEY)
  - Fallback: model_prices_and_context_window.json (already in repo, no key needed)

Usage:
  python scripts/sync_provider_models.py             # Apply changes
  python scripts/sync_provider_models.py --dry-run    # Preview without writing

  # With provider API keys for better coverage:
  OPENAI_API_KEY=sk-... ANTHROPIC_API_KEY=sk-... GEMINI_API_KEY=... python scripts/sync_provider_models.py --dry-run
"""

import argparse
import json
import os
import re
import sys
from datetime import date
from pathlib import Path
from typing import NamedTuple

import requests

REPO_ROOT = Path(__file__).resolve().parent.parent

# --- File paths (relative to repo root) ---
JAVA_BASE = Path("apps/opik-backend/src/main/java/com/comet/opik/infrastructure/llm")
OPENROUTER_JAVA = JAVA_BASE / "openrouter" / "OpenRouterModelName.java"
OPENAI_JAVA = JAVA_BASE / "openai" / "OpenaiModelName.java"
ANTHROPIC_JAVA = JAVA_BASE / "antropic" / "AnthropicModelName.java"
GEMINI_JAVA = JAVA_BASE / "gemini" / "GeminiModelName.java"
VERTEXAI_JAVA = JAVA_BASE / "vertexai" / "VertexAIModelName.java"
PROVIDERS_TS = Path("apps/opik-frontend/src/types/providers.ts")
MODELS_DATA_TS = Path("apps/opik-frontend/src/hooks/useLLMProviderModelsData.ts")
MODEL_PRICES_JSON = Path("apps/opik-backend/src/main/resources/model_prices_and_context_window.json")

OPENROUTER_API_URL = "https://openrouter.ai/api/v1/models"

# Models from the JSON to exclude per provider
OPENAI_EXCLUDE_PATTERNS = [
    r"^ft:",
    r"-realtime",
    r"-audio",
    r"^gpt-audio",
    r"^gpt-realtime",
    r"^gpt-4-32k",
    r"^gpt-4-vision",
    r"^gpt-4-1106-vision",
    r"^gpt-4\.5-preview",
    r"^gpt-5-search",
    r"^gpt-4o-.*search",
    r"^gpt-4o-mini-search",
    r"^openai/",
    r"-\d{4}-\d{2}-\d{2}$",
    r"^gpt-3\.5-turbo-16k",
    r"^gpt-3\.5-turbo-0301$",
    r"^gpt-3\.5-turbo-0613$",
    r"-tts$",
    r"-transcribe$",
    r"-search",
]

# The OpenAI /v1/models API returns ALL model types (embeddings, tts, dall-e, whisper, etc).
# Only these prefixes are chat/completion models usable in our playground.
OPENAI_CHAT_PREFIXES = ("gpt-", "o1", "o3", "o4", "chatgpt-")

ANTHROPIC_EXCLUDE_PATTERNS = [
    r"-latest$",
    r"^claude-3-",
    r"^claude-3\.5-",
    r"^claude-2",
    r"^claude-instant",
    r"^claude-4-",  # claude-4-opus is old naming, current is claude-opus-4
]

GEMINI_EXCLUDE_PATTERNS = [
    r"^gemini-pro$",
    r"-thinking-exp",
    r"-audio",
    r"-native-audio",
    r"-live-",
    r"^gemini-live-",
    r"-image-generation",
    r"-computer-use-",
    r"^learnlm",
    r"^gemma",
    r"^gemini-gemma",
    r"^gemini-robotics",
    r"^text-embedding",
    r"^aqa$",
    r"^gemini-1\.0-pro-vision",
    r"^gemini-pro-vision$",
    r"^gemini-exp-",
    r"-preview-\d{2}-\d{2}$",
    r"-preview-\d{2}-\d{4}$",
    r"-exp-\d{2}-\d{2}$",
    r"-exp-\d{4}$",
    r"-preview-tts$",
    r"-\d{3}$",
    r"-customtools$",
    r"-latest$",
]


class ModelEntry(NamedTuple):
    enum_name: str
    value: str
    structured_output: bool
    label: str


# ─────────────────────────────────────────────────────────────────────────────
# Dropdown filtering — only show useful models in the frontend dropdown.
# Java enums keep all models for backend validation.
# ─────────────────────────────────────────────────────────────────────────────

OPENAI_DROPDOWN_EXCLUDE = [
    r"-\d{4}-\d{2}-\d{2}",    # dated snapshots (gpt-4o-2024-08-06)
    r"-preview$",               # old preview aliases
    r"^gpt-3\.5-",              # very old
    r"^gpt-4-0\d{3}",          # old GPT-4 snapshots (gpt-4-0314, gpt-4-0613)
    r"^gpt-4-1106",
    r"^gpt-4-0125",
    r"-instruct",
    r"-image",
    r"^chatgpt-image",
    r"^chatgpt-4o-latest$",    # deprecated by OpenAI (not in prices JSON)
    r"-transcribe",
    r"-codex",
    r"-pro$",                   # Responses API only (/v1/responses), we use /v1/chat/completions
    r"-deep-research$",
    r"-chat-latest$",
    r"^o1-preview",
    r"^o1-mini",
]

GEMINI_DROPDOWN_EXCLUDE = [
    r"^aqa$",
    r"^text-embedding",
    r"^gemini-pro-vision$",
    r"^gemini-1\.0-",
    r"-latest$",
    r"^nano-banana",
    r"-image",
]

VERTEXAI_DROPDOWN_EXCLUDE = [
    r"-exp-",                    # experimental
    r"-preview-\d{2}-\d{2}$",   # dated previews
]


def _openai_sort_key(entry: ModelEntry) -> tuple:
    """Sort OpenAI: GPT 5.x → 4.x → 4o → 4 → o-series → chatgpt. Within: base → pro → mini → nano."""
    value = entry.value
    tier = 1 if '-pro' in value else 2 if '-mini' in value else 3 if '-nano' in value else 0
    m = re.match(r'^gpt-(\d+)\.(\d+)', value)
    if m:
        return (0, -int(m.group(1)), -int(m.group(2)), tier, value)
    m = re.match(r'^gpt-(\d+)o', value)
    if m:
        return (0, -int(m.group(1)), 0.5, tier, value)
    m = re.match(r'^gpt-(\d+)', value)
    if m:
        return (0, -int(m.group(1)), 99, tier, value)
    m = re.match(r'^o(\d+)', value)
    if m:
        return (1, -int(m.group(1)), 0, tier, value)
    if value.startswith('chatgpt'):
        return (2, 0, 0, 0, value)
    return (99, 0, 0, 0, value)


def _anthropic_sort_key(entry: ModelEntry) -> tuple:
    """Sort Anthropic: by generation desc, then Opus → Sonnet → Haiku."""
    value = entry.value
    family_order = {'opus': 0, 'sonnet': 1, 'haiku': 2}
    # New naming: claude-{family}-{major}[-{minor}] (minor is single digit, not a date)
    m = re.match(r'^claude-(opus|sonnet|haiku)-(\d+)(?:-(\d)(?!\d))?', value)
    if m:
        return (-int(m.group(2)), -(int(m.group(3)) if m.group(3) else 0),
                family_order.get(m.group(1), 9), value)
    # Old naming: claude-{major}-{minor}-{family}
    m = re.match(r'^claude-(\d+)-(\d+)-(opus|sonnet|haiku)', value)
    if m:
        return (-int(m.group(1)), -int(m.group(2)),
                family_order.get(m.group(3), 9), value)
    return (0, 0, 9, value)


def _gemini_sort_key(entry: ModelEntry) -> tuple:
    """Sort Gemini/VertexAI: by generation desc, then Pro → Flash → Flash-Lite."""
    value = entry.value.removeprefix("vertex_ai/")
    m = re.match(r'^gemini-(\d+)(?:\.(\d+))?', value)
    if not m:
        return (99, 0, 0, value)
    major, minor = int(m.group(1)), int(m.group(2)) if m.group(2) else 0
    if '-pro' in value:
        tier = 0
    elif '-flash-lite' in value or '-flash-8b' in value:
        tier = 2
    elif '-flash' in value:
        tier = 1
    else:
        tier = 3
    return (-major, -minor, tier, value)


def _deduplicate_by_base(entries: list[ModelEntry]) -> list[ModelEntry]:
    """For models with dated variants, keep only the non-dated version for the dropdown."""
    groups: dict[str, list[ModelEntry]] = {}
    for e in entries:
        base = re.sub(r'-\d{8}$', '', e.value)
        groups.setdefault(base, []).append(e)
    result = []
    for group in groups.values():
        non_dated = [e for e in group if not re.search(r'-\d{8}$', e.value)]
        result.append(non_dated[0] if non_dated else group[0])
    return result


def build_deprecated_set(prices: dict) -> set[str]:
    """Build a set of model values with deprecation_date in the past."""
    today = date.today()
    deprecated = set()
    for key, info in prices.items():
        if not isinstance(info, dict):
            continue
        dep = info.get("deprecation_date")
        if not dep:
            continue
        try:
            if date.fromisoformat(dep) <= today:
                deprecated.add(key)
                for prefix in ("gemini/", "anthropic/", "openai/", "vertex_ai/"):
                    if key.startswith(prefix):
                        deprecated.add(key.removeprefix(prefix))
        except ValueError:
            pass
    return deprecated


def filter_for_dropdown(
    entries: list[ModelEntry], provider: str, deprecated: set[str] | None = None,
) -> list[ModelEntry]:
    """Filter and sort model entries for the frontend dropdown."""
    exclude = {
        "openai": OPENAI_DROPDOWN_EXCLUDE,
        "gemini": GEMINI_DROPDOWN_EXCLUDE,
        "vertexai": VERTEXAI_DROPDOWN_EXCLUDE,
    }.get(provider, [])

    sort_fn = {
        "openai": _openai_sort_key,
        "anthropic": _anthropic_sort_key,
        "gemini": _gemini_sort_key,
        "vertexai": _gemini_sort_key,
    }.get(provider)

    filtered = entries
    if exclude:
        filtered = [e for e in filtered if not matches_any(e.value, exclude)]
    if deprecated:
        filtered = [e for e in filtered if e.value not in deprecated]
    if provider == "anthropic":
        filtered = _deduplicate_by_base(filtered)
    if sort_fn:
        filtered = sorted(filtered, key=sort_fn)
    return filtered


# ─────────────────────────────────────────────────────────────────────────────
# Conversion helpers
# ─────────────────────────────────────────────────────────────────────────────

def model_to_enum_name(model_str: str, provider: str) -> str:
    """Convert a model string to a Java/TS enum name."""
    s = model_str
    if provider == "vertexai":
        s = s.removeprefix("vertex_ai/")
    if provider == "openai" and re.match(r"^o\d", s):
        s = "GPT_" + s
    s = re.sub(r"[-.:\/]", "_", s)
    return s.upper()


def generate_openai_label(model_str: str) -> str:
    if model_str.startswith("chatgpt-"):
        rest = model_str[len("chatgpt-"):]
        return "ChatGPT " + _label_parts(rest)
    if model_str.startswith("gpt-"):
        rest = model_str[len("gpt-"):]
        return "GPT " + _label_parts(rest)
    if re.match(r"^o\d", model_str):
        return "GPT " + _label_parts(model_str)
    return _label_parts(model_str)


def generate_anthropic_label(model_str: str) -> str:
    s = re.sub(r"-\d{8}$", "", model_str)
    parts = s.split("-")
    result = []
    i = 0
    while i < len(parts):
        if (
            parts[i].isdigit()
            and i + 1 < len(parts)
            and parts[i + 1].isdigit()
            and len(parts[i]) <= 2
            and len(parts[i + 1]) <= 2
        ):
            result.append(f"{parts[i]}.{parts[i + 1]}")
            i += 2
        else:
            result.append(parts[i].title() if parts[i].isalpha() else parts[i])
            i += 1
    return " ".join(result)


def generate_gemini_label(model_str: str) -> str:
    s = model_str.removeprefix("vertex_ai/")
    parts = s.split("-")
    result = []
    for p in parts:
        if p.isalpha():
            result.append(p.title())
        else:
            result.append(p)
    return " ".join(result)


def _label_parts(s: str) -> str:
    """Convert 'something-else-3.5' to 'Something Else 3.5', keeping version dots."""
    parts = s.split("-")
    result = []
    for p in parts:
        if p.isalpha():
            result.append(p.title())
        elif re.match(r"^\d+[a-z]$", p):
            result.append(p)
        else:
            result.append(p)
    return " ".join(result)


def matches_any(s: str, patterns: list[str]) -> bool:
    return any(re.search(p, s) for p in patterns)


# ─────────────────────────────────────────────────────────────────────────────
# Source fetching
# ─────────────────────────────────────────────────────────────────────────────

def fetch_openrouter_models() -> list[str]:
    """Fetch chat-capable model IDs from OpenRouter API."""
    resp = requests.get(OPENROUTER_API_URL, timeout=30)
    resp.raise_for_status()
    models = resp.json()["data"]
    chat_ids = []
    for m in models:
        modality = (m.get("architecture") or {}).get("modality", "")
        if "text" in modality:
            chat_ids.append(m["id"])
    return sorted(set(chat_ids))


def fetch_openai_models(api_key: str) -> list[str]:
    """Fetch model IDs from OpenAI API. Returns filtered list of chat model IDs."""
    resp = requests.get(
        "https://api.openai.com/v1/models",
        headers={"Authorization": f"Bearer {api_key}"},
        timeout=30,
    )
    resp.raise_for_status()
    ids = [m["id"] for m in resp.json()["data"]]
    return sorted(
        id_ for id_ in set(ids)
        if id_.startswith(OPENAI_CHAT_PREFIXES) and not matches_any(id_, OPENAI_EXCLUDE_PATTERNS)
    )


def fetch_anthropic_models(api_key: str) -> list[tuple[str, str]]:
    """Fetch models from Anthropic API. Returns list of (model_id, display_name)."""
    results = []
    after_id = None
    while True:
        params = {"limit": 1000}
        if after_id:
            params["after_id"] = after_id
        resp = requests.get(
            "https://api.anthropic.com/v1/models",
            headers={
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
            },
            params=params,
            timeout=30,
        )
        resp.raise_for_status()
        data = resp.json()
        for m in data["data"]:
            model_id = m["id"]
            if not matches_any(model_id, ANTHROPIC_EXCLUDE_PATTERNS):
                results.append((model_id, m.get("display_name", "")))
        if not data.get("has_more"):
            break
        after_id = data.get("last_id")
    return sorted(results, key=lambda x: x[0])


def fetch_gemini_models(api_key: str) -> list[tuple[str, str]]:
    """Fetch models from Gemini API. Returns list of (base_model_id, display_name)."""
    results = []
    page_token = None
    while True:
        params = {"key": api_key}
        if page_token:
            params["pageToken"] = page_token
        resp = requests.get(
            "https://generativelanguage.googleapis.com/v1beta/models",
            params=params,
            timeout=30,
        )
        resp.raise_for_status()
        data = resp.json()
        for m in data.get("models", []):
            methods = m.get("supportedGenerationMethods", [])
            if "generateContent" not in methods:
                continue
            base_id = m.get("baseModelId") or m["name"].removeprefix("models/")
            if not matches_any(base_id, GEMINI_EXCLUDE_PATTERNS):
                results.append((base_id, m.get("displayName", "")))
        page_token = data.get("nextPageToken")
        if not page_token:
            break
    return sorted(set(results), key=lambda x: x[0])


def load_model_prices() -> dict:
    path = REPO_ROOT / MODEL_PRICES_JSON
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def extract_models_from_prices(
    prices: dict, litellm_provider: str, exclude_patterns: list[str], key_prefix: str = ""
) -> list[tuple[str, bool]]:
    """Extract (model_key, supports_structured_output) from the prices JSON."""
    results = []
    for key, info in prices.items():
        if not isinstance(info, dict):
            continue
        if info.get("litellm_provider") != litellm_provider:
            continue
        if info.get("mode") != "chat":
            continue

        model_key = key.removeprefix(key_prefix) if key_prefix else key

        if matches_any(model_key, exclude_patterns):
            continue

        structured = info.get("supports_response_schema", False)
        results.append((model_key, bool(structured)))
    return sorted(results, key=lambda x: x[0])


# ─────────────────────────────────────────────────────────────────────────────
# Java file parsing
# ─────────────────────────────────────────────────────────────────────────────

def read_file(rel_path: Path) -> str:
    return (REPO_ROOT / rel_path).read_text(encoding="utf-8")


def write_file(rel_path: Path, content: str) -> None:
    (REPO_ROOT / rel_path).write_text(content, encoding="utf-8")


def parse_java_enum_2arg(content: str) -> dict[str, tuple[str, bool]]:
    """Parse enums like ENUM_NAME("value", true/false)."""
    pattern = re.compile(r"^\s+(\w+)\(\s*\"([^\"]+)\"\s*,\s*(true|false)\s*\)", re.MULTILINE)
    return {m.group(1): (m.group(2), m.group(3) == "true") for m in pattern.finditer(content)}


def parse_java_enum_1arg(content: str) -> dict[str, str]:
    """Parse enums like ENUM_NAME("value")."""
    pattern = re.compile(r"^\s+(\w+)\(\s*\"([^\"]+)\"\s*\)", re.MULTILINE)
    return {m.group(1): m.group(2) for m in pattern.finditer(content)}


def parse_java_enum_3arg(content: str) -> dict[str, tuple[str, str, bool]]:
    """Parse enums like ENUM_NAME("qualified", "value", true/false)."""
    pattern = re.compile(
        r"^\s+(\w+)\(\s*\"([^\"]+)\"\s*,\s*\"([^\"]+)\"\s*,\s*(true|false)\s*\)",
        re.MULTILINE,
    )
    return {
        m.group(1): (m.group(2), m.group(3), m.group(4) == "true")
        for m in pattern.finditer(content)
    }


def parse_openrouter_structured_set(content: str) -> set[str]:
    """Parse the STRUCTURED_OUTPUT_SUPPORTED_MODELS Set from OpenRouterModelName.java."""
    m = re.search(
        r"STRUCTURED_OUTPUT_SUPPORTED_MODELS\s*=\s*Set\.of\((.*?)\)",
        content,
        re.DOTALL,
    )
    if not m:
        return set()
    return set(re.findall(r"(\w+)", m.group(1)))


# ─────────────────────────────────────────────────────────────────────────────
# Java file regeneration
# ─────────────────────────────────────────────────────────────────────────────

def _find_enum_body_range(content: str) -> tuple[int, int]:
    """Find the start/end offsets of enum entries.

    Returns (body_start, body_end) where:
    - body_start is the first char after the enum opening '{' newline
    - body_end is the start of the first 'private' line after entries

    The caller replaces content[body_start:body_end] with new entries ending in ';\\n\\n'.
    """
    enum_open = re.search(r"implements StructuredOutputSupported \{", content)
    if not enum_open:
        raise ValueError("Could not find enum opening")
    body_start = content.index("\n", enum_open.end()) + 1

    # The enum entries end before the first 'private' declaration
    private_match = re.search(r"^\s+private\s", content[body_start:], re.MULTILINE)
    if not private_match:
        raise ValueError("Could not find 'private' field after enum entries")

    body_end = body_start + private_match.start()
    return body_start, body_end


def _format_java_entry_2arg(enum_name: str, value: str, flag: bool) -> str:
    """Format a 2-arg Java enum entry with line splitting if needed."""
    line = f"    {enum_name}(\"{value}\", {'true' if flag else 'false'}),"
    if len(line) <= 120:
        return line
    return f"    {enum_name}(\n            \"{value}\", {'true' if flag else 'false'}),"


def _format_java_entry_1arg(enum_name: str, value: str) -> str:
    line = f"    {enum_name}(\"{value}\"),"
    if len(line) <= 120:
        return line
    return f"    {enum_name}(\n            \"{value}\"),"


def _format_java_entry_3arg(enum_name: str, qualified: str, value: str, flag: bool) -> str:
    line = f"    {enum_name}(\"{qualified}\", \"{value}\", {'true' if flag else 'false'}),"
    if len(line) <= 120:
        return line
    return f"    {enum_name}(\"{qualified}\",\n            \"{value}\", {'true' if flag else 'false'}),"


def _finalize_entries(lines: list[str]) -> str:
    """Turn the last entry's trailing comma into a semicolon, add blank line."""
    if not lines:
        return "    ;\n\n"
    lines[-1] = lines[-1].rstrip().rstrip(",") + ";\n"
    return "\n".join(lines) + "\n"


def regenerate_openrouter_java(
    content: str,
    entries: list[tuple[str, str]],
    structured_set: set[str],
) -> str:
    body_start, body_end = _find_enum_body_range(content)

    lines = []
    for enum_name, value in entries:
        lines.append(_format_java_entry_1arg(enum_name, value))
    entry_block = _finalize_entries(lines)

    # Rebuild structured output set
    so_names = sorted(n for n in structured_set if any(n == e[0] for e in entries))
    so_block = ",\n            ".join(so_names)

    new_content = content[:body_start] + entry_block + content[body_end:]

    # Replace the Set.of(...) block
    new_content = re.sub(
        r"(STRUCTURED_OUTPUT_SUPPORTED_MODELS\s*=\s*Set\.of\().*?(\))",
        lambda m: m.group(1) + "\n            " + so_block + m.group(2) if so_block else m.group(1) + m.group(2),
        new_content,
        flags=re.DOTALL,
    )
    return new_content


def regenerate_java_2arg(content: str, entries: list[tuple[str, str, bool]]) -> str:
    body_start, body_end = _find_enum_body_range(content)
    lines = [_format_java_entry_2arg(e, v, f) for e, v, f in entries]
    return content[:body_start] + _finalize_entries(lines) + content[body_end:]


def regenerate_java_1arg(content: str, entries: list[tuple[str, str]]) -> str:
    body_start, body_end = _find_enum_body_range(content)
    lines = [_format_java_entry_1arg(e, v) for e, v in entries]
    return content[:body_start] + _finalize_entries(lines) + content[body_end:]


def regenerate_java_3arg(content: str, entries: list[tuple[str, str, str, bool]]) -> str:
    body_start, body_end = _find_enum_body_range(content)
    lines = [_format_java_entry_3arg(e, q, v, f) for e, q, v, f in entries]
    return content[:body_start] + _finalize_entries(lines) + content[body_end:]


# ─────────────────────────────────────────────────────────────────────────────
# TypeScript file regeneration
# ─────────────────────────────────────────────────────────────────────────────

_TS_ENUM_SECTION_MARKERS = {
    "openai": "// <------ openai",
    "anthropic": "//  <----- anthropic",
    "openrouter": "//  <---- OpenRouter",
    "gemini": "//   <----- gemini",
    "vertexai": "//   <------ vertex ai",
}

_TS_PROVIDER_ORDER = ["openai", "anthropic", "openrouter", "gemini", "vertexai"]


def regenerate_providers_ts(
    content: str,
    models_by_provider: dict[str, list[ModelEntry]],
) -> str:
    """Regenerate the PROVIDER_MODEL_TYPE enum sections in providers.ts."""
    # Find the enum block
    enum_start_m = re.search(r"export enum PROVIDER_MODEL_TYPE \{", content)
    if not enum_start_m:
        raise ValueError("Could not find PROVIDER_MODEL_TYPE enum")

    # Find the opik free line (always first, we preserve it)
    opik_free_end = content.index("\n\n", enum_start_m.end()) + 2

    # Find the closing brace of the enum
    enum_close = content.index("\n}", opik_free_end)

    sections = []
    for provider in _TS_PROVIDER_ORDER:
        marker = _TS_ENUM_SECTION_MARKERS[provider]
        entries = models_by_provider.get(provider, [])
        lines = [f"  {marker}"]
        for entry in entries:
            lines.append(f'  {entry.enum_name} = "{entry.value}",')
        sections.append("\n".join(lines))

    new_body = "\n\n".join(sections) + "\n"
    return content[:opik_free_end] + new_body + content[enum_close:]


def regenerate_models_data_ts(
    content: str,
    models_by_provider: dict[str, list[ModelEntry]],
) -> str:
    """Regenerate PROVIDER_MODELS entries in useLLMProviderModelsData.ts."""
    provider_type_map = {
        "openai": "OPEN_AI",
        "anthropic": "ANTHROPIC",
        "openrouter": "OPEN_ROUTER",
        "gemini": "GEMINI",
        "vertexai": "VERTEX_AI",
    }

    for provider, entries in models_by_provider.items():
        ts_provider = provider_type_map[provider]
        section_marker = f"[PROVIDER_TYPE.{ts_provider}]: ["

        start_idx = content.index(section_marker)
        bracket_start = content.index("[", start_idx + len("[PROVIDER_TYPE."))
        # Find matching closing bracket
        depth = 0
        pos = bracket_start
        while pos < len(content):
            if content[pos] == "[":
                depth += 1
            elif content[pos] == "]":
                depth -= 1
                if depth == 0:
                    break
            pos += 1
        bracket_end = pos

        lines = ["["]
        for entry in entries:
            lines.append("    {")
            lines.append(f"      value: PROVIDER_MODEL_TYPE.{entry.enum_name},")
            lines.append(f'      label: "{entry.label}",')
            lines.append("    },")
        lines.append("  ]")

        content = content[:bracket_start] + "\n".join(lines) + content[bracket_end + 1 :]

    return content


# ─────────────────────────────────────────────────────────────────────────────
# Per-provider sync logic
# ─────────────────────────────────────────────────────────────────────────────

def sync_openrouter(
    api_models: list[str], prices: dict, java_content: str,
) -> tuple[str, list[ModelEntry], list[str], list[str]]:
    """Sync OpenRouter models. Returns (new_java_content, model_entries, added, stale)."""
    current = parse_java_enum_1arg(java_content)
    current_values = {v for v in current.values()}
    current_so = parse_openrouter_structured_set(java_content)

    # Build price lookup for structured output
    price_so = set()
    for key, info in prices.items():
        if isinstance(info, dict) and info.get("supports_response_schema"):
            price_so.add(key)

    # Preserve hand-crafted enum names from existing Java entries
    value_to_existing_name = {v: name for name, v in current.items()}

    api_set = set(api_models)
    # Add-only: keep all existing + add new from API. Report stale for manual review.
    all_values = sorted(current_values | api_set)
    stale = sorted(current_values - api_set)

    entries_for_java = []
    model_entries = []
    new_so = set()

    for value in all_values:
        enum_name = value_to_existing_name.get(value) or model_to_enum_name(value, "openrouter")

        # Keep existing structured output flags, check prices for new
        if enum_name in current_so:
            new_so.add(enum_name)
        elif value in price_so or f"openrouter/{value}" in price_so:
            new_so.add(enum_name)

        entries_for_java.append((enum_name, value))
        model_entries.append(ModelEntry(
            enum_name=enum_name,
            value=value,
            structured_output=enum_name in new_so,
            label=value,
        ))

    added = sorted(api_set - current_values)

    new_java = regenerate_openrouter_java(java_content, entries_for_java, new_so)
    return new_java, model_entries, added, stale


def sync_simple_provider(
    provider: str,
    source_models: list[tuple[str, bool]],
    java_content: str,
    label_fn,
    java_format: str = "2arg",
    label_overrides: dict[str, str] | None = None,
) -> tuple[str, list[ModelEntry], list[str], list[str]]:
    """Generic add-only sync for OpenAI/Anthropic/Gemini providers.

    Never removes models — only adds new ones and reports stale for manual review.
    """
    if java_format == "2arg":
        current = parse_java_enum_2arg(java_content)
        current_values = {v for v, _ in current.values()}
        current_so_by_value = {v: so for v, so in current.values()}
    elif java_format == "1arg":
        current = parse_java_enum_1arg(java_content)
        current_values = set(current.values())
        current_so_by_value = {}
    else:
        raise ValueError(f"Unknown format: {java_format}")

    source_dict = {k: so for k, so in source_models}
    label_overrides = label_overrides or {}

    # Preserve hand-crafted enum names from existing Java entries
    if java_format == "2arg":
        value_to_existing_name = {v: name for name, (v, _) in current.items()}
    else:
        value_to_existing_name = {v: name for name, v in current.items()}

    # Add-only: keep all existing + add new from source. Report stale for manual review.
    source_set = set(source_dict.keys())
    all_values = current_values | source_set
    stale = sorted(current_values - source_set)

    entries = []
    model_entries = []
    used_enum_names: set[str] = set()
    skipped_values: set[str] = set()

    # Process existing values first to claim their enum names, then new values
    existing_first = sorted(v for v in all_values if v in value_to_existing_name)
    new_values = sorted(v for v in all_values if v not in value_to_existing_name)

    for value in existing_first + new_values:
        enum_name = value_to_existing_name.get(value) or model_to_enum_name(value, provider)

        # Skip new values whose auto-derived name collides with an existing entry
        # (e.g. claude-haiku-4-5 vs claude-haiku-4-5-20251001 both → CLAUDE_HAIKU_4_5)
        if enum_name in used_enum_names:
            skipped_values.add(value)
            continue
        used_enum_names.add(enum_name)

        if java_format == "2arg":
            if value in current_so_by_value:
                so = current_so_by_value[value]
            else:
                so = source_dict.get(value, False)
            entries.append((enum_name, value, so))
        else:
            entries.append((enum_name, value))

        label = label_overrides.get(value) or label_fn(value)
        model_entries.append(ModelEntry(
            enum_name=enum_name,
            value=value,
            structured_output=current_so_by_value.get(value, source_dict.get(value, False)),
            label=label,
        ))

    added = sorted(source_set - current_values - skipped_values)

    if java_format == "2arg":
        new_java = regenerate_java_2arg(java_content, entries)
    else:
        new_java = regenerate_java_1arg(java_content, [(e, v) for e, v in entries])

    return new_java, model_entries, added, stale


def sync_vertexai(
    source_models: list[tuple[str, bool]],
    java_content: str,
    label_overrides: dict[str, str] | None = None,
) -> tuple[str, list[ModelEntry], list[str], list[str]]:
    """Add-only sync for VertexAI. Never removes, reports stale for manual review."""
    current = parse_java_enum_3arg(java_content)
    current_qualified = {q for q, _, _ in current.values()}
    current_so_by_qualified = {q: so for q, _, so in current.values()}

    source_dict = {k: so for k, so in source_models}
    label_overrides = label_overrides or {}

    # Preserve hand-crafted enum names from existing Java entries
    qualified_to_existing_name = {q: name for name, (q, _, _) in current.items()}

    # Add-only: keep all existing + add new from source. Report stale for manual review.
    source_set = set(source_dict.keys())
    all_qualified = current_qualified | source_set
    stale = sorted(current_qualified - source_set)

    entries = []
    model_entries = []

    for qualified in sorted(all_qualified):
        value = qualified.removeprefix("vertex_ai/")
        enum_name = qualified_to_existing_name.get(qualified) or model_to_enum_name(qualified, "vertexai")

        if qualified in current_so_by_qualified:
            so = current_so_by_qualified[qualified]
        else:
            so = source_dict.get(qualified, False)

        entries.append((enum_name, qualified, value, so))
        label = label_overrides.get(value) or generate_gemini_label(value)
        ts_enum_name = enum_name if enum_name.startswith("VERTEX_AI_") else "VERTEX_AI_" + enum_name
        model_entries.append(ModelEntry(
            enum_name=ts_enum_name,
            value=qualified,
            structured_output=so,
            label=label,
        ))

    added = sorted(source_set - current_qualified)

    new_java = regenerate_java_3arg(java_content, entries)
    return new_java, model_entries, added, stale


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def _build_structured_output_lookup(prices: dict) -> dict[str, bool]:
    """Build model_key → supports_structured_output from prices JSON."""
    lookup = {}
    for key, info in prices.items():
        if isinstance(info, dict):
            lookup[key] = bool(info.get("supports_response_schema", False))
    return lookup


def _get_vertexai_models_from_prices(prices: dict) -> list[tuple[str, bool]]:
    """Extract VertexAI models from the prices JSON."""
    vertexai_models = extract_models_from_prices(
        prices, "vertex_ai-chat-models", [], key_prefix=""
    )
    vertexai_models_alt = extract_models_from_prices(
        prices, "vertex_ai", GEMINI_EXCLUDE_PATTERNS, key_prefix=""
    )
    vertexai_all = {}
    for k, so in vertexai_models + vertexai_models_alt:
        if k.startswith("vertex_ai/gemini-"):
            vertexai_all[k] = vertexai_all.get(k, False) or so
    return sorted(vertexai_all.items(), key=lambda x: x[0])


def main():
    parser = argparse.ArgumentParser(description="Sync LLM provider model definitions")
    parser.add_argument("--dry-run", action="store_true", help="Preview changes without writing")
    args = parser.parse_args()

    print("## Provider Model Sync\n")

    openai_key = os.environ.get("OPENAI_API_KEY")
    anthropic_key = os.environ.get("ANTHROPIC_API_KEY")
    gemini_key = os.environ.get("GEMINI_API_KEY")

    prices = load_model_prices()
    so_lookup = _build_structured_output_lookup(prices)
    deprecated = build_deprecated_set(prices)

    # 1. Fetch sources — prefer provider APIs when keys are available

    # OpenRouter (always from API, no key needed)
    print("Fetching OpenRouter models...", file=sys.stderr)
    try:
        openrouter_api_models = fetch_openrouter_models()
        print(f"  Found {len(openrouter_api_models)} chat models from API", file=sys.stderr)
    except Exception as e:
        print(f"  WARNING: OpenRouter API fetch failed: {e}", file=sys.stderr)
        openrouter_api_models = []

    # OpenAI
    if openai_key:
        print("Fetching OpenAI models from API...", file=sys.stderr)
        try:
            openai_ids = fetch_openai_models(openai_key)
            openai_models = [(id_, so_lookup.get(id_, False)) for id_ in openai_ids]
            print(f"  Found {len(openai_models)} models from API", file=sys.stderr)
        except Exception as e:
            print(f"  WARNING: OpenAI API fetch failed, falling back to prices JSON: {e}", file=sys.stderr)
            openai_models = extract_models_from_prices(prices, "openai", OPENAI_EXCLUDE_PATTERNS)
    else:
        print("  OpenAI: using prices JSON (no OPENAI_API_KEY)", file=sys.stderr)
        openai_models = extract_models_from_prices(prices, "openai", OPENAI_EXCLUDE_PATTERNS)

    # Anthropic
    anthropic_labels: dict[str, str] = {}
    if anthropic_key:
        print("Fetching Anthropic models from API...", file=sys.stderr)
        try:
            anthropic_api = fetch_anthropic_models(anthropic_key)
            anthropic_models = [(id_, so_lookup.get(id_, so_lookup.get(f"anthropic/{id_}", False))) for id_, _ in anthropic_api]
            anthropic_labels = {id_: name for id_, name in anthropic_api if name}
            print(f"  Found {len(anthropic_models)} models from API", file=sys.stderr)
        except Exception as e:
            print(f"  WARNING: Anthropic API fetch failed, falling back to prices JSON: {e}", file=sys.stderr)
            anthropic_models = extract_models_from_prices(prices, "anthropic", ANTHROPIC_EXCLUDE_PATTERNS)
    else:
        print("  Anthropic: using prices JSON (no ANTHROPIC_API_KEY)", file=sys.stderr)
        anthropic_models = extract_models_from_prices(prices, "anthropic", ANTHROPIC_EXCLUDE_PATTERNS)

    # Gemini
    gemini_labels: dict[str, str] = {}
    if gemini_key:
        print("Fetching Gemini models from API...", file=sys.stderr)
        try:
            gemini_api = fetch_gemini_models(gemini_key)
            gemini_models = [(id_, so_lookup.get(f"gemini/{id_}", so_lookup.get(id_, False))) for id_, _ in gemini_api]
            gemini_labels = {id_: name for id_, name in gemini_api if name}
            print(f"  Found {len(gemini_models)} models from API", file=sys.stderr)
        except Exception as e:
            print(f"  WARNING: Gemini API fetch failed, falling back to prices JSON: {e}", file=sys.stderr)
            gemini_models = extract_models_from_prices(
                prices, "gemini", GEMINI_EXCLUDE_PATTERNS, key_prefix="gemini/"
            )
    else:
        print("  Gemini: using prices JSON (no GEMINI_API_KEY)", file=sys.stderr)
        gemini_models = extract_models_from_prices(
            prices, "gemini", GEMINI_EXCLUDE_PATTERNS, key_prefix="gemini/"
        )

    # VertexAI (always from prices JSON, but can use Gemini API labels)
    vertexai_models = _get_vertexai_models_from_prices(prices)

    # 2. Read current files
    or_java = read_file(OPENROUTER_JAVA)
    oa_java = read_file(OPENAI_JAVA)
    an_java = read_file(ANTHROPIC_JAVA)
    ge_java = read_file(GEMINI_JAVA)
    va_java = read_file(VERTEXAI_JAVA)
    providers_ts = read_file(PROVIDERS_TS)
    models_data_ts = read_file(MODELS_DATA_TS)

    # 3. Sync each provider
    all_changes = {}

    new_or_java, or_entries, or_added, or_stale = sync_openrouter(
        openrouter_api_models, prices, or_java,
    )
    all_changes["openrouter"] = {"entries": or_entries, "added": or_added, "stale": or_stale}

    new_oa_java, oa_entries, oa_added, oa_stale = sync_simple_provider(
        "openai", openai_models, oa_java, generate_openai_label, "2arg",
    )
    all_changes["openai"] = {"entries": oa_entries, "added": oa_added, "stale": oa_stale}

    new_an_java, an_entries, an_added, an_stale = sync_simple_provider(
        "anthropic", anthropic_models, an_java, generate_anthropic_label, "1arg",
        label_overrides=anthropic_labels,
    )
    all_changes["anthropic"] = {"entries": an_entries, "added": an_added, "stale": an_stale}

    new_ge_java, ge_entries, ge_added, ge_stale = sync_simple_provider(
        "gemini", gemini_models, ge_java, generate_gemini_label, "2arg",
        label_overrides=gemini_labels,
    )
    all_changes["gemini"] = {"entries": ge_entries, "added": ge_added, "stale": ge_stale}

    new_va_java, va_entries, va_added, va_stale = sync_vertexai(
        vertexai_models, va_java,
        label_overrides=gemini_labels,
    )
    all_changes["vertexai"] = {"entries": va_entries, "added": va_added, "stale": va_stale}

    # 4. Regenerate TypeScript files
    # TS enum (providers.ts) gets ALL models — same as Java enums
    models_by_provider = {k: v["entries"] for k, v in all_changes.items()}
    new_providers_ts = regenerate_providers_ts(providers_ts, models_by_provider)

    # Dropdown (useLLMProviderModelsData.ts) gets curated subset — filtered and sorted
    dropdown_by_provider = {
        provider: filter_for_dropdown(entries, provider, deprecated)
        for provider, entries in models_by_provider.items()
    }
    new_models_data_ts = regenerate_models_data_ts(models_data_ts, dropdown_by_provider)

    # 5. Print summary
    total_added = 0
    total_stale = 0
    total_deprecated = 0
    for provider, changes in all_changes.items():
        added = changes["added"]
        stale = changes.get("stale", [])
        total_added += len(added)
        total_stale += len(stale)
        entries = changes["entries"]
        dropdown = dropdown_by_provider[provider]
        dep_in_enum = sorted(e.value for e in entries if e.value in deprecated)
        total_deprecated += len(dep_in_enum)
        print(f"### {provider.title()}")
        if added:
            print(f"- Added {len(added)} model(s):")
            for m in added:
                print(f"  + {m}")
        if stale:
            print(f"- Stale {len(stale)} model(s) (not in source, manual review needed):")
            for m in stale:
                print(f"  ? {m}")
        if dep_in_enum:
            print(f"- Deprecated {len(dep_in_enum)} model(s) (past deprecation_date, excluded from dropdown):")
            for m in dep_in_enum:
                print(f"  \u2717 {m}")
        if not added and not stale and not dep_in_enum:
            print(f"- No changes (total: {len(entries)}, dropdown: {len(dropdown)})")
        else:
            print(f"- Total models: {len(entries)} (dropdown: {len(dropdown)})")
        print()

    if total_added == 0:
        if total_stale > 0:
            print(f"No new models found. {total_stale} stale model(s) flagged for manual review.")
        else:
            print("No changes found.")
        sys.exit(1)

    if args.dry_run:
        print(f"\n**Dry run**: {total_added} added. No files written.")
        if total_stale > 0:
            print(f"{total_stale} stale model(s) flagged for manual review.")
        if total_deprecated > 0:
            print(f"{total_deprecated} deprecated model(s) excluded from dropdown.")
        sys.exit(0)

    # 6. Write files
    write_file(OPENROUTER_JAVA, new_or_java)
    write_file(OPENAI_JAVA, new_oa_java)
    write_file(ANTHROPIC_JAVA, new_an_java)
    write_file(GEMINI_JAVA, new_ge_java)
    write_file(VERTEXAI_JAVA, new_va_java)
    write_file(PROVIDERS_TS, new_providers_ts)
    write_file(MODELS_DATA_TS, new_models_data_ts)

    print(f"\nWrote changes ({total_added} added) across 7 files.", file=sys.stderr)
    sys.exit(0)


if __name__ == "__main__":
    main()
