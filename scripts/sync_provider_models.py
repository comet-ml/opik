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

A provider whose API call fails keeps its current models in every file; the others still sync.

Each model's `params` in llm-models-default.yaml come from LiteLLM's prices JSON and, for OpenRouter models, from
OpenRouter's model list. llm-models-curated.yaml (written by people) wins over them field by field; the summary lists
curated fields the generated params now agree or disagree with. Reading that file needs pyyaml.

Exit codes (with --dry-run, "written" means "would be written"):
  0  files written, every provider API call succeeded
  1  nothing to write, every provider API call succeeded
  3  files written, but at least one provider API call failed
  4  nothing to write, and at least one provider API call failed
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
MODELS_DATA_TS = Path("apps/opik-frontend/src/constants/providerModels.ts")
MODEL_PRICES_JSON = Path("apps/opik-backend/src/main/resources/model_prices_and_context_window.json")
# LiteLLM's prices JSON no longer lists OpenRouter models; scripts/update_openrouter_model_prices.py writes them here.
OPENROUTER_MODEL_PRICES_JSON = Path("apps/opik-backend/src/main/resources/model_prices_openrouter.json")
LLM_MODELS_YAML = Path("apps/opik-backend/src/main/resources/llm-models-default.yaml")

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

# LiteLLM marks these ChatGPT snapshots with `supports_reasoning`, a flag broader than what the frontend classifies as a reasoning model:
# OpenAI's model pages list no reasoning for them and the API answers reasoning_effort with 400 "Invalid 'reasoning_effort' for non-reasoning
# model". Must equal the `reasoning: false` rows of OPENAI_MODEL_CAPABILITIES in apps/opik-frontend/src/constants/llm.ts (its llm.test.ts
# fails otherwise); one shared list is OPIK-8637.
OPENAI_NON_REASONING_MODELS = {"gpt-5-chat-latest", "gpt-5.1-chat-latest", "gpt-5.2-chat-latest", "gpt-5.3-chat-latest"}

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


# Only the parameters the playground's OpenRouter panel has a control for: OpenRouter's full lists would more than double the
# registry every client downloads.
OPENROUTER_PANEL_PARAMETERS = {
    "temperature",
    "top_p",
    "top_k",
    "frequency_penalty",
    "presence_penalty",
    "repetition_penalty",
    "min_p",
    "top_a",
    "max_tokens",
    "max_completion_tokens",
    "reasoning",
    "reasoning_effort",
}


class OpenRouterCapabilities(NamedTuple):
    # None when OpenRouter lists no parameters at all (its router models), which says nothing about what they accept.
    supported_parameters: list[str] | None
    reasoning_efforts: list[str]
    output_modalities: list[str]


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
    r"^gpt-live",               # realtime voice model, 400 "not a chat model" on /v1/chat/completions
]

# Retired, 404 "model: ...". The deprecated filter hid them only while the prices JSON listed them with a
# deprecation_date; LiteLLM later drops a retired model's entry, and the next sync would show it again.
ANTHROPIC_DROPDOWN_EXCLUDE = [
    r"^claude-mythos-preview$",
    r"^claude-3-7-sonnet-",
    r"^claude-opus-4-1-",
    r"^claude-(opus|sonnet)-4-2025",
]

GEMINI_DROPDOWN_EXCLUDE = [
    r"^aqa$",
    r"^text-embedding",
    r"^gemini-pro-vision$",
    r"^gemini-1\.0-",
    r"-latest$",
    r"nano-banana",              # unanchored: also gemini-nano-banana-*, image models that 500 on chat requests
    r"-image",
    r"-tts",
    r"-exp$",                    # experimental, removed without a deprecation date
    r"^gemini-robotics",         # robotics models, 404 on Vertex AI
    # Retired: Gemini answers "no longer available", Vertex AI 404. The deprecated filter misses them: their
    # vertex_ai/ entries have no deprecation_date or are gone, and LiteLLM dropped gemini-3-pro-preview's gemini/ entry.
    r"^gemini-2\.0-",
    r"^gemini-3-pro-preview$",
    r"^gemini-3\.1-flash-lite-preview$",
    # Google removed Gemma 2 and Gemma 3 from the AI Studio Gemini API in
    # April 2026 (replaced by Gemma 4). The LiteLLM prices JSON still ships
    # `gemini/gemma-3-*-it` and `gemini/gemini-gemma-2-*-it` entries, but
    # generateContent on /v1beta no longer routes them. Hide from dropdown
    # until Gemma 4 (`gemma-4-31b-it`, `gemma-4-26b-a4b-it`) lands in the
    # sync sources.
    r"^gemma-(2|3)-",
    r"^gemini-gemma-",
    r"^gemini-omni",             # video generation, 400 "only supports Interactions API" on generateContent
    r"^lyria-",                  # music generation, answers with audio the playground cannot show
]

VERTEXAI_DROPDOWN_EXCLUDE = [
    r"-exp-",                    # experimental
    r"-preview-\d{2}-\d{2}$",   # dated previews
    r"/gemini-omni",             # video generation, Interactions API only
]

OPENROUTER_DROPDOWN_EXCLUDE = [
    r":batch$",                  # batch API only, 404 "cannot be used with the chat/completions endpoint"
    r"^openai/gpt-3\.5-turbo-instruct",    # completions API only
    r"^openai/gpt-5-image",                # 400 on the temperature the playground always sends
    r"^relace/relace-apply",               # code-merge model, 400 on a chat request
    r"^thinkingmachines/inkling.*:free$",  # 403 "only available on agentic harnesses"
    r"^openai/gpt-5\.2-chat$",             # its only endpoint is Azure and BYOK only: 404 "No endpoints found"
    r"^minimax/minimax-m1$",               # answers only in its reasoning; the content stays empty
    r"^amazon/nova-premier-v1$",           # retired on Bedrock: 400 "This model version has reached the end of its life"
    r"^inclusionai/ling-3\.0-flash-fin$",  # no provider serves it: 404 "No endpoints found"
    r"^nousresearch/hermes-3-llama-3\.1-70b$",  # streams the same junk sentence until the output limit
    r"llama-guard",                        # safety classifier: answers "safe" or "unsafe", not a chat model
    r"content-safety",                     # safety classifier: answers "safe" or "unsafe", not a chat model
    r"^openrouter/bodybuilder$",           # turns a request into OpenRouter API JSON, not a chat model
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
    """Sort Gemini first by generation, then Gemma by generation/size.

    Within Gemini: Pro → Flash → Flash-Lite.
    Within Gemma: base `gemma-N-*b-it` (small → large) before `gemma-Nn-*` variants.
    """
    value = entry.value.removeprefix("vertex_ai/")

    # Gemma family (including legacy `gemini-gemma-*`) — sorts after Gemini.
    gemma = re.match(r'^(?:gemini-)?gemma-(\d+)(n)?(?:-(e?\d+)b-)?', value)
    if gemma:
        major = int(gemma.group(1))
        is_3n = gemma.group(2) == "n"
        size_token = gemma.group(3) or ""
        size_digits = re.search(r"\d+", size_token)
        size = int(size_digits.group()) if size_digits else 99
        sub = 0 if not is_3n else 1
        return (1, -major, sub, size, value)

    # Gemini family — existing behaviour.
    m = re.match(r'^gemini-(\d+)(?:\.(\d+))?', value)
    if not m:
        return (1, 99, 0, 99, value)
    major, minor = int(m.group(1)), int(m.group(2)) if m.group(2) else 0
    if '-pro' in value:
        tier = 0
    elif '-flash-lite' in value or '-flash-8b' in value:
        tier = 2
    elif '-flash' in value:
        tier = 1
    else:
        tier = 3
    return (0, -major, -minor, tier, value)


def _deduplicate_by_base(entries: list[ModelEntry]) -> list[ModelEntry]:
    """For models with dated variants, keep only the non-dated version for the dropdown."""
    groups: dict[str, list[ModelEntry]] = {}
    for e in entries:
        base = re.sub(r'-\d{8}$', '', e.value)
        groups.setdefault(base, []).append(e)
    result = []
    for group in groups.values():
        non_dated = [e for e in group if not re.search(r'-\d{8}$', e.value)]
        if non_dated:
            result.append(non_dated[0])
        else:
            result.append(max(group, key=lambda e: re.search(r'-(\d{8})$', e.value).group(1)))
    return result


# A date belongs to the provider LiteLLM files the row under. Matched on the bare id, Vertex AI's
# "gemini-2.5-flash" row hid the Gemini API model and DeepSeek's own "deepseek/deepseek-chat" hid OpenRouter's.
def _deprecated_value(key: str, litellm_provider: str) -> tuple[str, str] | None:
    if litellm_provider in ("openai", "text-completion-openai"):
        return "openai", key.removeprefix("openai/")
    if litellm_provider == "anthropic":
        return "anthropic", key.removeprefix("anthropic/")
    if litellm_provider == "gemini":
        return "gemini", key.removeprefix("gemini/")
    if litellm_provider == "openrouter":
        return "openrouter", key.removeprefix("openrouter/")
    if key.startswith("vertex_ai/"):
        return "vertexai", key
    return None


def build_deprecated_by_provider(prices: dict) -> dict[str, set[str]]:
    today = date.today()
    deprecated: dict[str, set[str]] = {}
    for key, info in prices.items():
        if not isinstance(info, dict) or not info.get("deprecation_date"):
            continue
        try:
            if date.fromisoformat(info["deprecation_date"]) > today:
                continue
        except ValueError:
            continue
        scoped = _deprecated_value(key, str(info.get("litellm_provider", "")))
        if scoped:
            deprecated.setdefault(scoped[0], set()).add(scoped[1])
    return deprecated


def filter_for_dropdown(
    entries: list[ModelEntry],
    provider: str,
    deprecated: set[str] | None = None,
    served_ids: set[str] | None = None,
) -> list[ModelEntry]:
    """Filter and sort model entries for the frontend dropdown."""
    exclude = {
        "openai": OPENAI_DROPDOWN_EXCLUDE,
        "anthropic": ANTHROPIC_DROPDOWN_EXCLUDE,
        "gemini": GEMINI_DROPDOWN_EXCLUDE,
        "vertexai": VERTEXAI_DROPDOWN_EXCLUDE,
        "openrouter": OPENROUTER_DROPDOWN_EXCLUDE,
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
    # Vertex AI serves the same Gemini models; the Gemini patterns are ^-anchored, so they match only without the prefix.
    if provider == "vertexai":
        filtered = [e for e in filtered if not matches_any(e.value.removeprefix("vertex_ai/"), GEMINI_DROPDOWN_EXCLUDE)]
    if deprecated:
        filtered = [e for e in filtered if e.value not in deprecated]
    if served_ids is not None:
        filtered = [e for e in filtered if e.value in served_ids]
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
    s = s.lstrip("~")
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
    # Google dropped the "Gemini" prefix from Gemma branding; the prices JSON
    # still carries the old `gemini-gemma-*` keys. Collapse to match current
    # naming so the dropdown reads "Gemma 2 …" not "Gemini Gemma 2 …".
    s = re.sub(r"^gemini-gemma-", "gemma-", s)
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

# A malformed list counts as no list (every control stays) instead of a string turning into single letters,
# or a partial list, that hides controls the model takes.
def _string_list(value) -> list[str]:
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return value
    return []


def fetch_openrouter_models() -> dict[str, OpenRouterCapabilities]:
    """Fetch chat-capable models from OpenRouter API, keyed by id."""
    resp = requests.get(OPENROUTER_API_URL, timeout=30)
    resp.raise_for_status()
    models = resp.json()["data"]
    chat_models = {}
    for m in models:
        modality = (m.get("architecture") or {}).get("modality", "")
        if "text" in modality:
            supported = _string_list(m.get("supported_parameters"))
            chat_models[m["id"]] = OpenRouterCapabilities(
                supported_parameters=sorted(set(supported) & OPENROUTER_PANEL_PARAMETERS) if supported else None,
                reasoning_efforts=_string_list((m.get("reasoning") or {}).get("supported_efforts")),
                output_modalities=_string_list((m.get("architecture") or {}).get("output_modalities")),
            )
    # A 200 with no chat models is an outage, not an answer: taken at its word, the sync would strip every
    # model's capability lists. Raising makes main treat it as a failed fetch, so OpenRouter keeps its current
    # models and the other providers still sync.
    if not chat_models:
        raise ValueError("the model list has no chat models")
    return dict(sorted(chat_models.items()))


# OpenRouter answers 404 for an id it no longer lists, so the picker shows only the ids it serves today.
# Audio output (music, voice) fails on a text-only request, and the playground can't play it back.
def _openrouter_picker_ids(capabilities: dict[str, OpenRouterCapabilities]) -> set[str]:
    return {model_id for model_id, model in capabilities.items() if "audio" not in model.output_modalities}


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
    return sorted(set(results))


def load_model_prices() -> dict:
    prices = {}
    for path in (MODEL_PRICES_JSON, OPENROUTER_MODEL_PRICES_JSON):
        with open(REPO_ROOT / path, encoding="utf-8") as f:
            prices.update(json.load(f))
    return prices


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
    """Turn the last entry's trailing comma into a semicolon."""
    if not lines:
        return "    ;\n"
    lines[-1] = lines[-1].rstrip().rstrip(",") + ";"
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
    kept_providers: frozenset[str] | set[str] = frozenset(),
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

    enum_body = content[opik_free_end:enum_close]
    marker_starts = sorted(
        (enum_body.index(f"  {marker}"), provider)
        for provider, marker in _TS_ENUM_SECTION_MARKERS.items()
        if f"  {marker}" in enum_body
    )
    section_ends = [start for start, _ in marker_starts[1:]] + [len(enum_body)]
    existing_sections = {
        provider: enum_body[start:end].rstrip("\n")
        for (start, provider), end in zip(marker_starts, section_ends)
    }

    sections = []
    for provider in _TS_PROVIDER_ORDER:
        if provider in kept_providers and provider in existing_sections:
            sections.append(existing_sections[provider])
            continue
        marker = _TS_ENUM_SECTION_MARKERS[provider]
        entries = models_by_provider.get(provider, [])
        lines = [f"  {marker}"]
        for entry in entries:
            lines.append(f'  {entry.enum_name} = "{entry.value}",')
        sections.append("\n".join(lines))

    new_body = "\n\n".join(sections)
    return content[:opik_free_end] + new_body + content[enum_close:]


def regenerate_models_data_ts(
    content: str,
    models_by_provider: dict[str, list[ModelEntry]],
) -> str:
    """Regenerate PROVIDER_MODELS entries in src/constants/providerModels.ts."""
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
            value_line = f"      value: PROVIDER_MODEL_TYPE.{entry.enum_name},"
            if len(value_line) > 80:
                lines.append("      value:")
                lines.append(f"        PROVIDER_MODEL_TYPE.{entry.enum_name},")
            else:
                lines.append(value_line)
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
    yaml_structured_output_ids: set[str] | None = None,
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
    yaml_structured_output_ids = yaml_structured_output_ids or set()

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
    collisions: list[tuple[str, str]] = []

    # Process existing values first to claim their enum names, then new values
    existing_first = sorted(v for v in all_values if v in value_to_existing_name)
    new_values = sorted(v for v in all_values if v not in value_to_existing_name)

    for value in existing_first + new_values:
        enum_name = value_to_existing_name.get(value) or model_to_enum_name(value, provider)

        if enum_name in used_enum_names:
            skipped_values.add(value)
            collisions.append((value, enum_name))
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
            # A 1-arg enum stores no flag, so a kept model that left the source would otherwise lose the one the YAML has.
            structured_output=current_so_by_value.get(
                value, source_dict.get(value, value in yaml_structured_output_ids)
            ),
            label=label,
        ))

    added = sorted(source_set - current_values - skipped_values)

    if java_format == "2arg":
        new_java = regenerate_java_2arg(java_content, entries)
    else:
        new_java = regenerate_java_1arg(java_content, [(e, v) for e, v in entries])

    return new_java, model_entries, added, stale, collisions


def sync_vertexai(
    source_models: list[tuple[str, bool]],
    java_content: str,
    label_overrides: dict[str, str] | None = None,
) -> tuple[str, list[ModelEntry], list[str], list[str], list[tuple[str, str]]]:
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
    collisions: list[tuple[str, str]] = []
    # Reserved up front, not claimed while iterating: hand-crafted names drop suffixes
    # (GEMINI_2_0_FLASH is "gemini-2.0-flash-001"), and a new "gemini-2.0-flash" sorts
    # before it, so it would otherwise take the name and push the existing model out.
    used_enum_names = set(qualified_to_existing_name.values())

    for qualified in sorted(all_qualified):
        value = qualified.removeprefix("vertex_ai/")
        enum_name = qualified_to_existing_name.get(qualified)
        if enum_name is None:
            enum_name = model_to_enum_name(qualified, "vertexai")
            if enum_name in used_enum_names:
                collisions.append((qualified, enum_name))
                continue
            used_enum_names.add(enum_name)

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

    added = sorted(source_set - current_qualified - {q for q, _ in collisions})

    new_java = regenerate_java_3arg(java_content, entries)
    return new_java, model_entries, added, stale, collisions


# ─────────────────────────────────────────────────────────────────────────────
# Generated params
# ─────────────────────────────────────────────────────────────────────────────

CURATED_YAML = Path("apps/opik-backend/src/main/resources/llm-models-curated.yaml")

# The field order of ModelParams.java, so a model's line reads the same way in every sync.
PARAM_FIELDS = (
    "temperature", "topP", "topK", "frequencyPenalty", "presencePenalty",
    "maxOutputTokens", "stop", "effort", "samplingExclusive", "thinksByDefault", "onlyRoute", "routes",
)
EFFORT_ORDER = ["none", "minimal", "low", "medium", "high", "xhigh", "max"]
SAMPLING_FIELDS = ("temperature", "topP", "topK")

OPENROUTER_PARAM_NAMES = {
    "temperature": ("temperature",),
    "topP": ("top_p",),
    "topK": ("top_k",),
    "frequencyPenalty": ("frequency_penalty",),
    "presencePenalty": ("presence_penalty",),
    "maxOutputTokens": ("max_tokens", "max_completion_tokens"),
    "stop": ("stop",),
    "effort": ("reasoning", "reasoning_effort"),
}

_FLOW_PLAIN_WORD = re.compile(r"[A-Za-z][A-Za-z0-9_.-]*")
# YAML 1.1 reads these bare words as booleans or null.
_FLOW_RESERVED_WORDS = {"y", "n", "yes", "no", "on", "off", "true", "false", "null", "~"}


def _ordered_efforts(levels: list[str]) -> list[str]:
    known = [level for level in EFFORT_ORDER if level in levels]
    return known + [level for level in levels if level not in EFFORT_ORDER]


def _litellm_info(prices: dict, provider_key: str, entry_value: str) -> dict | None:
    candidates = {
        "openai": [entry_value],
        "anthropic": [entry_value, f"anthropic/{entry_value}"],
        "gemini": [f"gemini/{entry_value}"],
        "vertexai": [entry_value],
    }.get(provider_key, [])
    for key in candidates:
        info = prices.get(key)
        if isinstance(info, dict):
            return info
    # A bare id can be a Gemini API row or a Vertex AI one (litellm_provider vertex_ai-language-models), so its
    # provider is checked: a model never takes the other provider's facts.
    owns_bare_row = {
        "gemini": lambda provider: provider == "gemini",
        "vertexai": lambda provider: provider.startswith("vertex_ai"),
    }.get(provider_key)
    if owns_bare_row:
        info = prices.get(entry_value.removeprefix("vertex_ai/"))
        if isinstance(info, dict) and owns_bare_row(str(info.get("litellm_provider", ""))):
            return info
    return None


def litellm_params(provider_key: str, model_id: str, info: dict, default_max_output: int | None = None) -> dict:
    params: dict = {}
    max_output = info.get("max_output_tokens")
    if isinstance(max_output, int) and not isinstance(max_output, bool) and max_output > 0:
        params["maxOutputTokens"] = {"max": max_output}
        # The provider profile's default would otherwise sit above this model's cap.
        if default_max_output is not None and max_output < default_max_output:
            params["maxOutputTokens"]["default"] = max_output

    # Only a refusal is taken from LiteLLM: a wrong "yes" sends a param the provider answers with a 400, so taking
    # one needs a curated row, and a model without one keeps its profile's safe default.
    if info.get("supports_sampling_params") is False:
        for field in SAMPLING_FIELDS if provider_key == "anthropic" else ("temperature", "topP"):
            params[field] = {"accepted": False}

    if provider_key == "openai" and info.get("supports_reasoning") and model_id not in OPENAI_NON_REASONING_MODELS:
        levels = (
            (["none"] if info.get("supports_none_reasoning_effort") else [])
            + (["minimal"] if info.get("supports_minimal_reasoning_effort") else [])
            + ["low", "medium", "high"]
            + (["xhigh"] if info.get("supports_xhigh_reasoning_effort") else [])
        )
        # OpenAI's API runs at medium when a request names no effort; a curated row can pick another default.
        params["effort"] = {"accepted": True, "levels": levels, "default": "medium"}
        # Chat Completions sends max as high, so max is a level of the Responses route only.
        if info.get("supports_max_reasoning_effort"):
            params["routes"] = {"responses_api": {"effort": {"levels": levels + ["max"]}}}

    # OpenAI answers a model that takes only /v1/responses (the codex, pro, deep-research and computer-use models)
    # with a 404 on /v1/chat/completions.
    endpoints = info.get("supported_endpoints")
    if (
        provider_key == "openai"
        and isinstance(endpoints, list)
        and "/v1/responses" in endpoints
        and "/v1/chat/completions" not in endpoints
    ):
        params["onlyRoute"] = "responses_api"

    # Claude's effort is output_config.effort; LiteLLM's supports_reasoning there means extended thinking, which
    # older Claude models have without any effort levels.
    if provider_key == "anthropic" and info.get("supports_output_config"):
        params["effort"] = {
            "accepted": True,
            "levels": ["low", "medium", "high"]
            + (["xhigh"] if info.get("supports_xhigh_reasoning_effort") else [])
            + (["max"] if info.get("supports_max_reasoning_effort") else []),
        }
    return params


def openrouter_model_params(model: dict) -> dict:
    supported = model.get("supported_parameters")
    if not isinstance(supported, list) or not supported:
        return {}
    supported = {name for name in supported if isinstance(name, str)}
    params: dict = {}
    for field, names in OPENROUTER_PARAM_NAMES.items():
        params[field] = {"accepted": any(name in supported for name in names)}

    max_output = (model.get("top_provider") or {}).get("max_completion_tokens")
    if params["maxOutputTokens"]["accepted"] and isinstance(max_output, int) and max_output > 0:
        params["maxOutputTokens"]["max"] = max_output

    reasoning = model.get("reasoning") or {}
    efforts = [e for e in reasoning.get("supported_efforts") or [] if isinstance(e, str)]
    if params["effort"]["accepted"] and efforts:
        params["effort"]["levels"] = _ordered_efforts(efforts)
        default = reasoning.get("default_effort")
        if isinstance(default, str) and default in params["effort"]["levels"]:
            params["effort"]["default"] = default
    elif params["effort"]["accepted"]:
        # Reasoning with no levels to pick from is an on/off switch, which an effort can't express.
        del params["effort"]
    return params


def fetch_openrouter_params() -> dict[str, dict]:
    resp = requests.get(OPENROUTER_API_URL, timeout=30)
    resp.raise_for_status()
    params = {m["id"]: openrouter_model_params(m) for m in resp.json()["data"] if isinstance(m, dict) and "id" in m}
    # A 200 with an empty list is an outage, not an answer: taken at its word, every model would lose its params.
    if not params:
        raise ValueError("the model list is empty")
    return params


def _flow_scalar(value) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if _FLOW_PLAIN_WORD.fullmatch(value) and value.lower() not in _FLOW_RESERVED_WORDS:
        return value
    return json.dumps(value)


def _flow(value) -> str:
    if isinstance(value, dict):
        ordered = [k for k in PARAM_FIELDS if k in value] + [k for k in value if k not in PARAM_FIELDS]
        return "{" + ", ".join(f"{k}: {_flow(value[k])}" for k in ordered) + "}"
    if isinstance(value, list):
        return "[" + ", ".join(_flow(v) for v in value) + "]"
    return _flow_scalar(value)


def params_line(params: dict) -> str:
    return f"    params: {_flow(params)}"


def _yaml_params_lines(yaml_content: str) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    for section, lines in _yaml_sections(yaml_content).items():
        current_id: str | None = None
        for line in lines:
            id_match = re.match(r'^\s+- id:\s+"([^"]+)"', line)
            if id_match:
                current_id = id_match.group(1)
            elif current_id and line.startswith("    params: "):
                result.setdefault(section, {})[current_id] = line
    return result


def load_curated_catalog() -> dict:
    path = REPO_ROOT / CURATED_YAML
    if not path.exists():
        return {}
    # Imported here so the rest of the sync runs on requests alone, as it always has.
    import yaml

    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


def _profile_max_output_defaults(catalog: dict) -> dict[str, int]:
    profiles = catalog.get("profiles") or {}
    defaults = {}
    for yaml_key, provider_defaults in (catalog.get("defaults") or {}).items():
        profile = profiles.get((provider_defaults or {}).get("profile")) or {}
        value = (profile.get("maxOutputTokens") or {}).get("default")
        if isinstance(value, int) and not isinstance(value, bool):
            defaults[yaml_key] = value
    return defaults


def build_params_lines(
    models_by_provider: dict[str, list[ModelEntry]],
    prices: dict,
    openrouter_params: dict[str, dict] | None,
    existing_yaml_content: str,
    curated_catalog: dict | None = None,
) -> tuple[dict[str, dict[str, str]], dict[str, dict[str, dict]]]:
    """
    Each model's params line for llm-models-default.yaml, and the params behind each line.

    A model a source says nothing about keeps the line it has, the same add-only rule the model lists follow: a
    failed OpenRouter fetch (openrouter_params is None), an OpenRouter model missing from the fetched list, or a
    model LiteLLM has no entry for. A model the source lists with no params is an answer, not a gap, so it loses
    its line.
    """
    previous = _yaml_params_lines(existing_yaml_content)
    max_output_defaults = _profile_max_output_defaults(curated_catalog or {})
    lines: dict[str, dict[str, str]] = {}
    generated: dict[str, dict[str, dict]] = {}
    for provider_key, entries in models_by_provider.items():
        yaml_key = _PROVIDER_TO_YAML_KEY[provider_key]
        for entry in entries:
            model_id = entry.value.removeprefix("vertex_ai/") if provider_key == "vertexai" else entry.value
            if provider_key == "openrouter":
                answered = openrouter_params is not None and model_id in openrouter_params
                params = openrouter_params[model_id] if answered else {}
            else:
                info = _litellm_info(prices, provider_key, entry.value)
                answered = bool(info)
                params = litellm_params(provider_key, model_id, info, max_output_defaults.get(yaml_key)) if info else {}
            if params:
                lines.setdefault(yaml_key, {})[model_id] = params_line(params)
                generated.setdefault(yaml_key, {})[model_id] = params
            elif not answered and model_id in previous.get(yaml_key, {}):
                lines.setdefault(yaml_key, {})[model_id] = previous[yaml_key][model_id]
    return lines, generated


def params_changes(
    existing_yaml_content: str, params_lines: dict[str, dict[str, str]], kept_yaml_keys: set[str],
) -> dict[str, tuple[int, int, int]]:
    """Per YAML section: (models that gain params, models whose params change, models that lose them)."""
    previous = _yaml_params_lines(existing_yaml_content)
    changes = {}
    managed = set(_PROVIDER_TO_YAML_KEY.values())
    for yaml_key in sorted((params_lines.keys() | previous.keys()) & managed):
        if yaml_key in kept_yaml_keys:
            continue
        before = previous.get(yaml_key, {})
        after = params_lines.get(yaml_key, {})
        gained = sum(1 for model_id in after if model_id not in before)
        changed = sum(1 for model_id, line in after.items() if model_id in before and before[model_id] != line)
        lost = sum(1 for model_id in before if model_id not in after)
        if gained or changed or lost:
            changes[yaml_key] = (gained, changed, lost)
    return changes


def _leaves(value, prefix: str = "") -> dict[str, object]:
    if isinstance(value, dict):
        out = {}
        for key, inner in value.items():
            out.update(_leaves(inner, f"{prefix}.{key}" if prefix else key))
        return out
    return {prefix: value}


# Written by litellm_params from other fields, not stated by LiteLLM, so they say nothing about a curated value.
_DERIVED_LITELLM_FIELDS = {"effort.default", "maxOutputTokens.default"}


def _yaml_model_ids(yaml_content: str) -> dict[str, set[str]]:
    ids: dict[str, set[str]] = {}
    for section, lines in _yaml_sections(yaml_content).items():
        for line in lines:
            id_match = re.match(r'^\s+- id:\s+"([^"]+)"', line)
            if id_match:
                ids.setdefault(section, set()).add(id_match.group(1))
    return ids


def curated_drift(
    catalog: dict, generated: dict[str, dict[str, dict]], listed_ids: dict[str, set[str]] | None = None,
) -> tuple[list[str], list[str], list[str]]:
    """
    Curated facts the generated params now state the same way (the curated value can go), curated facts they
    contradict (someone should check which is right), and rows written ahead of the sync (awaitingSync) for a model
    it now lists. Only a model's own curated row and profile count: the provider defaults are fallbacks, so generated
    facts are meant to differ from them.
    """
    profiles = catalog.get("profiles") or {}
    listed_ids = listed_ids or {}
    agree, disagree, arrived = [], [], []
    for yaml_key, rows in (catalog.get("models") or {}).items():
        for row in rows or []:
            model_id = row.get("id")
            if row.get("awaitingSync") is True and model_id in listed_ids.get(yaml_key, set()):
                arrived.append(f"{yaml_key}/{model_id}")
            facts = generated.get(yaml_key, {}).get(model_id)
            if not facts:
                continue
            curated = {**_leaves(profiles.get(row.get("profile")) or {}), **_leaves(row.get("params") or {})}
            same, different = [], []
            for field, generated_value in sorted(_leaves(facts).items()):
                if field not in curated or (field in _DERIVED_LITELLM_FIELDS and yaml_key != "openrouter"):
                    continue
                if curated[field] == generated_value:
                    same.append(field)
                else:
                    different.append(f"{field} (curated {_flow(curated[field])}, generated {_flow(generated_value)})")
            if same:
                agree.append(f"{yaml_key}/{model_id}: {', '.join(same)}")
            if different:
                disagree.append(f"{yaml_key}/{model_id}: {'; '.join(different)}")
    return agree, disagree, arrived


# LiteLLM says whether a Gemini model thinks but not which levels it takes, and a level it doesn't take is a 400, so
# the sync can't write Gemini levels: a picker model that thinks needs them written by hand.
def thinking_models_without_effort(
    dropdown_by_provider: dict[str, list[ModelEntry]], prices: dict, catalog: dict,
) -> list[str]:
    profiles = catalog.get("profiles") or {}
    missing = []
    for provider_key in ("gemini", "vertexai"):
        yaml_key = _PROVIDER_TO_YAML_KEY[provider_key]
        provider_profile = ((catalog.get("defaults") or {}).get(yaml_key) or {}).get("profile")
        default_effort = (profiles.get(provider_profile) or {}).get("effort") or {}
        rows = {row.get("id"): row for row in (catalog.get("models") or {}).get(yaml_key) or []}
        for entry in dropdown_by_provider.get(provider_key, []):
            info = _litellm_info(prices, provider_key, entry.value)
            if not (info and info.get("supports_reasoning")):
                continue
            model_id = entry.value.removeprefix("vertex_ai/")
            row = rows.get(model_id) or {}
            own_effort = {
                **((profiles.get(row.get("profile")) or {}).get("effort") or {}),
                **((row.get("params") or {}).get("effort") or {}),
            }
            # The provider profile's {accepted: false} is only the fallback for a model nobody wrote levels for; a
            # refusal or an empty levels list ("takes none") in the model's own row or profile is a decision.
            if (
                {**default_effort, **own_effort}.get("levels")
                or "levels" in own_effort
                or own_effort.get("accepted") is False
            ):
                continue
            missing.append(f"{yaml_key}/{model_id}")
    return missing


# ─────────────────────────────────────────────────────────────────────────────
# YAML regeneration
# ─────────────────────────────────────────────────────────────────────────────

# Maps internal provider keys → YAML section names
_PROVIDER_TO_YAML_KEY = {
    "openai": "openai",
    "anthropic": "anthropic",
    "gemini": "gemini",
    "vertexai": "vertex-ai",
    "openrouter": "openrouter",
}


def _parse_yaml_reasoning_flags(yaml_content: str) -> dict[str, dict[str, bool]]:
    """
    Parse existing YAML to extract per-provider {model_id: reasoning} flags.
    Uses simple line-by-line parsing to avoid requiring pyyaml.
    Returns {yaml_section_key: {model_id: True}} for models with reasoning: true.
    """
    result: dict[str, dict[str, bool]] = {}
    current_provider: str | None = None
    current_id: str | None = None

    for line in yaml_content.splitlines():
        # Top-level provider key (no leading spaces, ends with colon)
        provider_match = re.match(r'^(\S[^:]+):\s*$', line)
        if provider_match:
            current_provider = provider_match.group(1)
            current_id = None
            continue

        if current_provider is None:
            continue

        # Model id line: "  - id: "value""
        id_match = re.match(r'^\s+- id:\s+"([^"]+)"', line)
        if id_match:
            current_id = id_match.group(1)
            continue

        # reasoning flag line: "    reasoning: true"
        if current_id and re.match(r'^\s+reasoning:\s+true', line):
            result.setdefault(current_provider, {})[current_id] = True

    return result


_YAML_PLAIN_WORD = re.compile(r"[a-z][a-z0-9_]*")
# In YAML 1.1 these bare words are booleans or null, so they are quoted too.
_YAML_RESERVED_WORDS = {"y", "n", "yes", "no", "on", "off", "true", "false", "null"}


# reasoning_efforts come from OpenRouter as they are, so anything but a plain word is written quoted.
# A JSON string is also a valid YAML double-quoted scalar.
def _yaml_flow_item(value: str) -> str:
    if _YAML_PLAIN_WORD.fullmatch(value) and value not in _YAML_RESERVED_WORDS:
        return value
    return json.dumps(value)


def _yaml_flow_sequence(values: list[str]) -> str:
    return f"[{', '.join(_yaml_flow_item(value) for value in values)}]"


def _openrouter_capability_lines(capabilities: OpenRouterCapabilities | None) -> list[str]:
    if capabilities is None:
        return []
    lines = []
    if capabilities.supported_parameters is not None:
        lines.append(f"    supportedParameters: {_yaml_flow_sequence(capabilities.supported_parameters)}")
    if capabilities.reasoning_efforts:
        lines.append(f"    reasoningEfforts: {_yaml_flow_sequence(capabilities.reasoning_efforts)}")
    return lines


def _yaml_sections(yaml_content: str) -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {}
    current_section: list[str] | None = None
    for line in yaml_content.splitlines():
        provider_match = re.match(r'^(\S[^:]+):\s*$', line)
        if provider_match:
            current_section = sections.setdefault(provider_match.group(1), [])
        if current_section is not None:
            current_section.append(line)
    return sections


def _yaml_structured_output_ids(yaml_content: str, section: str) -> set[str]:
    ids: set[str] = set()
    current_id: str | None = None
    for line in _yaml_sections(yaml_content).get(section, []):
        id_match = re.match(r'^\s+- id:\s+"([^"]+)"', line)
        if id_match:
            current_id = id_match.group(1)
        elif current_id and re.match(r'^\s+structuredOutput:\s+true', line):
            ids.add(current_id)
    return ids


def regenerate_llm_models_yaml(
    existing_content: str,
    models_by_provider: dict[str, list[ModelEntry]],
    dropdown_by_provider: dict[str, list[ModelEntry]] | None = None,
    openai_reasoning: dict[str, bool] | None = None,
    openrouter_capabilities: dict[str, OpenRouterCapabilities] | None = None,
    kept_providers: frozenset[str] | set[str] = frozenset(),
    params_lines: dict[str, dict[str, str]] | None = None,
) -> str:
    """
    Regenerate llm-models-default.yaml from the synced model entries.

    - Replaces sections for providers managed by the sync script.
    - Emits the *full* model list per provider so the YAML stays a superset
      and remains a complete routing fallback for the backend when the
      remote CDN is unavailable. Dropdown-curated entries (when
      `dropdown_by_provider` is supplied) lead their provider section in
      curated order and carry their human-readable `label`; the remaining
      entries follow alphabetically with no label.
    - Preserves reasoning flags carried over from the existing file.
    - In the openai section only, also emits `reasoning: true` for models
      that `openai_reasoning` marks. OPENAI_NON_REASONING_MODELS never get
      the flag, even when the existing file carries it.
      Other sections stay carry-over only: LiteLLM's flag means "can emit
      reasoning tokens", which is broader than what the frontend treats as
      a reasoning model, and nothing reads the flag for other providers.
    - Sections for providers not managed here (bedrock, ollama, opik-free,
      custom-llm), and for the managed providers in `kept_providers`, are
      preserved as-is if present.
    """
    # Carry over existing reasoning flags by provider/model-id
    reasoning_flags = _parse_yaml_reasoning_flags(existing_content)
    existing_sections = _yaml_sections(existing_content)
    dropdown_by_provider = dropdown_by_provider or {}
    openai_reasoning = openai_reasoning or {}
    openrouter_capabilities = openrouter_capabilities or {}
    params_lines = params_lines or {}

    lines: list[str] = []

    for provider_key, yaml_key in _PROVIDER_TO_YAML_KEY.items():
        if provider_key in kept_providers and yaml_key in existing_sections:
            lines.extend(existing_sections[yaml_key])
            continue
        entries = models_by_provider.get(provider_key, [])
        provider_reasoning = reasoning_flags.get(yaml_key, {})

        lines.append(f"{yaml_key}:")
        if not entries:
            lines.append("  []")
            continue

        # Build the final ordering: dropdown-curated entries first (in curated
        # order, with labels), then the remaining models alphabetically (no
        # label, same entry identity as in `entries`).
        dropdown_entries = dropdown_by_provider.get(provider_key, [])
        dropdown_values = {e.value for e in dropdown_entries}
        non_dropdown_entries = sorted(
            (e for e in entries if e.value not in dropdown_values),
            key=lambda e: e.value,
        )
        ordered_entries = list(dropdown_entries) + non_dropdown_entries

        for entry in ordered_entries:
            if provider_key == "vertexai":
                # value is qualified name (vertex_ai/gemini-...), id is base name
                model_id = entry.value.removeprefix("vertex_ai/")
                lines.append(f'  - id: "{model_id}"')
                lines.append(f'    qualifiedName: "{entry.value}"')
            else:
                model_id = entry.value
                lines.append(f'  - id: "{model_id}"')

            # Emit a label for every dropdown-visible entry, even when it
            # equals the id. The FE filter uses label-presence as the
            # "is this dropdown-visible?" signal, so dropping labels for
            # OpenRouter (where label == id by convention) made every
            # OpenRouter model invisible in the picker (OPIK-6360). The
            # extra ~30KB on the cold-cached YAML is a non-issue compared
            # to the correctness fragility of the omit-when-equal rule.
            is_dropdown_entry = entry.value in dropdown_values
            if is_dropdown_entry and entry.label:
                escaped_label = entry.label.replace('"', '\\"')
                lines.append(f'    label: "{escaped_label}"')

            if entry.structured_output:
                lines.append("    structuredOutput: true")
            is_openai = provider_key == "openai"
            excluded_from_reasoning = is_openai and model_id in OPENAI_NON_REASONING_MODELS
            seeded_reasoning = is_openai and openai_reasoning.get(model_id, False)
            if not excluded_from_reasoning and (provider_reasoning.get(model_id) or seeded_reasoning):
                lines.append("    reasoning: true")
            if provider_key == "openrouter":
                lines.extend(_openrouter_capability_lines(openrouter_capabilities.get(model_id)))
            if model_id in params_lines.get(yaml_key, {}):
                lines.append(params_lines[yaml_key][model_id])

    # Preserve any provider sections not managed by the sync script
    managed_yaml_keys = set(_PROVIDER_TO_YAML_KEY.values())
    for key, section in existing_sections.items():
        if key not in managed_yaml_keys:
            lines.extend(section)

    return "\n".join(lines) + "\n"


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


def _build_reasoning_lookup(prices: dict) -> dict[str, bool]:
    return {
        key: bool(info.get("supports_reasoning"))
        for key, info in prices.items()
        if isinstance(info, dict)
        and info.get("litellm_provider") == "openai"
        # LiteLLM lists the Responses-API-only models (codex, -pro, deep-research) with mode "responses", not "chat".
        and info.get("mode") in ("chat", "responses")
    }


def _get_vertexai_models_from_prices(prices: dict) -> list[tuple[str, bool]]:
    """Extract VertexAI models from the prices JSON.

    LiteLLM spreads Vertex Gemini entries across several `litellm_provider` tags with no clear
    rule, so all of them have to be read. Missing one silently hides whole model families: the
    flash-lite variants are tagged `vertex_ai-language-models`, so leaving that tag out kept them
    out of the dropdown entirely. The `vertex_ai/gemini-` guard below is what keeps the wider tags
    from pulling in non-Gemini models.
    """
    vertexai_models = extract_models_from_prices(
        prices, "vertex_ai-chat-models", [], key_prefix=""
    )
    vertexai_models_alt = extract_models_from_prices(
        prices, "vertex_ai", GEMINI_EXCLUDE_PATTERNS, key_prefix=""
    )
    vertexai_models_language = extract_models_from_prices(
        prices, "vertex_ai-language-models", GEMINI_EXCLUDE_PATTERNS, key_prefix=""
    )
    vertexai_all = {}
    for k, so in vertexai_models + vertexai_models_alt + vertexai_models_language:
        if k.startswith("vertex_ai/gemini-"):
            vertexai_all[k] = vertexai_all.get(k, False) or so
    return sorted(vertexai_all.items(), key=lambda x: x[0])


def _seeded_reasoning_ids(
    existing_yaml_content: str, openai_entries: list[ModelEntry], openai_reasoning: dict[str, bool]
) -> list[str]:
    already_flagged = _parse_yaml_reasoning_flags(existing_yaml_content).get("openai", {})
    return sorted(
        entry.value
        for entry in openai_entries
        if openai_reasoning.get(entry.value, False)
        and entry.value not in OPENAI_NON_REASONING_MODELS
        and entry.value not in already_flagged
    )


def _cleared_reasoning_ids(existing_yaml_content: str, regenerated_yaml_content: str) -> list[str]:
    before = _parse_yaml_reasoning_flags(existing_yaml_content).get("openai", {})
    after = _parse_yaml_reasoning_flags(regenerated_yaml_content).get("openai", {})
    return sorted(before.keys() - after.keys())


def _parse_yaml_labelled_ids(yaml_content: str, section: str) -> tuple[set[str], set[str]]:
    ids: set[str] = set()
    labelled: set[str] = set()
    current_provider: str | None = None
    current_id: str | None = None
    for line in yaml_content.splitlines():
        provider_match = re.match(r'^(\S[^:]+):\s*$', line)
        if provider_match:
            current_provider = provider_match.group(1)
            current_id = None
            continue
        if current_provider != section:
            continue
        id_match = re.match(r'^\s+- id:\s+"([^"]+)"', line)
        if id_match:
            current_id = id_match.group(1)
            ids.add(current_id)
            continue
        if current_id and re.match(r'^\s+label:', line):
            labelled.add(current_id)
    return ids, labelled


def _openrouter_picker_changes(
    existing_yaml_content: str, regenerated_yaml_content: str,
) -> tuple[list[str], list[str]]:
    before_ids, before_labelled = _parse_yaml_labelled_ids(existing_yaml_content, "openrouter")
    _, after_labelled = _parse_yaml_labelled_ids(regenerated_yaml_content, "openrouter")
    shown = sorted((after_labelled - before_labelled) & before_ids)
    hidden = sorted(before_labelled - after_labelled)
    return shown, hidden


def _parse_yaml_openrouter_capabilities(yaml_content: str) -> dict[str, dict[str, frozenset[str]]]:
    result: dict[str, dict[str, frozenset[str]]] = {}
    current_provider: str | None = None
    current_id: str | None = None
    for line in yaml_content.splitlines():
        provider_match = re.match(r'^(\S[^:]+):\s*$', line)
        if provider_match:
            current_provider = provider_match.group(1)
            current_id = None
            continue
        if current_provider != "openrouter":
            continue
        id_match = re.match(r'^\s+- id:\s+"([^"]+)"', line)
        if id_match:
            current_id = id_match.group(1)
            result[current_id] = {}
            continue
        capability_match = re.match(r'^\s+(supportedParameters|reasoningEfforts):\s*\[(.*)\]', line)
        if current_id and capability_match:
            result[current_id][capability_match.group(1)] = frozenset(
                item.strip() for item in capability_match.group(2).split(",") if item.strip()
            )
    return result


def _changed_openrouter_capability_ids(existing_yaml_content: str, regenerated_yaml_content: str) -> list[str]:
    before = _parse_yaml_openrouter_capabilities(existing_yaml_content)
    after = _parse_yaml_openrouter_capabilities(regenerated_yaml_content)
    return sorted(model_id for model_id in before.keys() & after.keys() if before[model_id] != after[model_id])


def _should_write_files(
    total_added: int,
    seeded_reasoning_ids: list[str],
    cleared_reasoning_ids: list[str],
    picker_changed_ids: list[str],
    capability_changed_ids: list[str],
    force_regen: bool,
    changed_params: dict[str, tuple[int, int, int]] | None = None,
) -> bool:
    if force_regen:
        return True
    return (
        total_added > 0
        or bool(seeded_reasoning_ids)
        or bool(cleared_reasoning_ids)
        or bool(picker_changed_ids)
        or bool(capability_changed_ids)
        or bool(changed_params)
    )


def main():
    parser = argparse.ArgumentParser(description="Sync LLM provider model definitions")
    parser.add_argument("--dry-run", action="store_true", help="Preview changes without writing")
    parser.add_argument("--force-regen", action="store_true", help="Regenerate and write files even when no new models were found")
    args = parser.parse_args()

    print("## Provider Model Sync\n")

    openai_key = os.environ.get("OPENAI_API_KEY")
    anthropic_key = os.environ.get("ANTHROPIC_API_KEY")
    gemini_key = os.environ.get("GEMINI_API_KEY")
    failed_providers: set[str] = set()

    prices = load_model_prices()
    so_lookup = _build_structured_output_lookup(prices)
    deprecated = build_deprecated_by_provider(prices)

    # 1. Fetch sources — prefer provider APIs when keys are available

    # OpenRouter (always from API, no key needed)
    print("Fetching OpenRouter models...", file=sys.stderr)
    try:
        openrouter_capabilities = fetch_openrouter_models()
        print(f"  Found {len(openrouter_capabilities)} chat models from API", file=sys.stderr)
    except Exception as e:
        print(f"::error::OpenRouter API fetch failed, kept its current models: {e}", file=sys.stderr)
        failed_providers.add("openrouter")
        openrouter_capabilities = {}

    openrouter_params: dict[str, dict] | None = None
    if "openrouter" not in failed_providers:
        try:
            openrouter_params = fetch_openrouter_params()
        except Exception as e:
            print(f"::error::OpenRouter params fetch failed, kept its current models and params: {e}", file=sys.stderr)
            failed_providers.add("openrouter")

    # OpenAI
    if openai_key:
        print("Fetching OpenAI models from API...", file=sys.stderr)
        try:
            openai_ids = fetch_openai_models(openai_key)
            openai_models = [(id_, so_lookup.get(id_, False)) for id_ in openai_ids]
            print(f"  Found {len(openai_models)} models from API", file=sys.stderr)
        except Exception as e:
            print(f"::error::OpenAI API fetch failed, kept its current models: {e}", file=sys.stderr)
            failed_providers.add("openai")
            openai_models = []
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
            print(f"::error::Anthropic API fetch failed, kept its current models: {e}", file=sys.stderr)
            failed_providers.add("anthropic")
            anthropic_models = []
    else:
        print("::warning::Anthropic: using prices JSON (no ANTHROPIC_API_KEY); model list will lack API display names", file=sys.stderr)
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
            print(f"::error::Gemini API fetch failed, kept its current Gemini and Vertex AI models (Vertex AI labels come from it): {e}", file=sys.stderr)
            failed_providers.add("gemini")
            gemini_models = []
    else:
        print("  Gemini: using prices JSON (no GEMINI_API_KEY)", file=sys.stderr)
        gemini_models = extract_models_from_prices(
            prices, "gemini", GEMINI_EXCLUDE_PATTERNS, key_prefix="gemini/"
        )

    # VertexAI (always from prices JSON, but can use Gemini API labels)
    vertexai_models = _get_vertexai_models_from_prices(prices)

    # A failed provider keeps its current entries in every file; Vertex AI goes with Gemini, whose labels it would otherwise lose.
    kept_providers = failed_providers | ({"vertexai"} if "gemini" in failed_providers else set())

    # 2. Read current files
    or_java = read_file(OPENROUTER_JAVA)
    oa_java = read_file(OPENAI_JAVA)
    an_java = read_file(ANTHROPIC_JAVA)
    ge_java = read_file(GEMINI_JAVA)
    va_java = read_file(VERTEXAI_JAVA)
    providers_ts = read_file(PROVIDERS_TS)
    models_data_ts = read_file(MODELS_DATA_TS)
    llm_models_yaml_content = read_file(LLM_MODELS_YAML)

    # 3. Sync each provider
    all_changes = {}

    new_or_java, or_entries, or_added, or_stale = sync_openrouter(
        list(openrouter_capabilities), prices, or_java,
    )
    all_changes["openrouter"] = {"entries": or_entries, "added": or_added, "stale": or_stale}

    new_oa_java, oa_entries, oa_added, oa_stale, oa_collisions = sync_simple_provider(
        "openai", openai_models, oa_java, generate_openai_label, "2arg",
    )
    all_changes["openai"] = {"entries": oa_entries, "added": oa_added, "stale": oa_stale, "collisions": oa_collisions}

    new_an_java, an_entries, an_added, an_stale, an_collisions = sync_simple_provider(
        "anthropic", anthropic_models, an_java, generate_anthropic_label, "1arg",
        label_overrides=anthropic_labels,
        yaml_structured_output_ids=_yaml_structured_output_ids(llm_models_yaml_content, "anthropic"),
    )
    all_changes["anthropic"] = {"entries": an_entries, "added": an_added, "stale": an_stale, "collisions": an_collisions}

    new_ge_java, ge_entries, ge_added, ge_stale, ge_collisions = sync_simple_provider(
        "gemini", gemini_models, ge_java, generate_gemini_label, "2arg",
        label_overrides=gemini_labels,
    )
    all_changes["gemini"] = {"entries": ge_entries, "added": ge_added, "stale": ge_stale, "collisions": ge_collisions}

    new_va_java, va_entries, va_added, va_stale, va_collisions = sync_vertexai(
        vertexai_models, va_java,
        label_overrides=gemini_labels,
    )
    all_changes["vertexai"] = {"entries": va_entries, "added": va_added, "stale": va_stale, "collisions": va_collisions}

    # 4. Regenerate TypeScript files
    # TS enum (providers.ts) gets ALL models — same as Java enums
    models_by_provider = {k: v["entries"] for k, v in all_changes.items()}
    new_providers_ts = regenerate_providers_ts(providers_ts, models_by_provider, kept_providers)

    openrouter_picker_ids = _openrouter_picker_ids(openrouter_capabilities)

    # Dropdown (src/constants/providerModels.ts) gets curated subset — filtered and sorted
    dropdown_by_provider = {
        provider: filter_for_dropdown(
            entries, provider, deprecated.get(provider),
            served_ids=openrouter_picker_ids if provider == "openrouter" else None,
        )
        for provider, entries in models_by_provider.items()
    }
    new_models_data_ts = regenerate_models_data_ts(
        models_data_ts,
        {provider: entries for provider, entries in dropdown_by_provider.items() if provider not in kept_providers},
    )

    # Regenerate llm-models-default.yaml with the full model list per provider
    # so the YAML stays a superset and the backend's classpath fallback covers
    # every routable model. Dropdown-curated entries lead their provider
    # section in curated order and carry human-readable labels; the rest
    # follow alphabetically without a label.
    openai_reasoning = _build_reasoning_lookup(prices)
    curated_catalog = load_curated_catalog()
    params_lines, generated_params = build_params_lines(
        models_by_provider, prices, openrouter_params, llm_models_yaml_content, curated_catalog,
    )
    new_llm_models_yaml = regenerate_llm_models_yaml(
        llm_models_yaml_content, models_by_provider, dropdown_by_provider,
        openai_reasoning=openai_reasoning,
        openrouter_capabilities=openrouter_capabilities,
        kept_providers=kept_providers,
        params_lines=params_lines,
    )
    changed_params = params_changes(
        llm_models_yaml_content, params_lines, {_PROVIDER_TO_YAML_KEY[p] for p in kept_providers},
    )
    seeded_reasoning_ids = [] if "openai" in kept_providers else _seeded_reasoning_ids(
        llm_models_yaml_content, models_by_provider["openai"], openai_reasoning,
    )
    cleared_reasoning_ids = _cleared_reasoning_ids(llm_models_yaml_content, new_llm_models_yaml)
    openrouter_shown, openrouter_hidden = _openrouter_picker_changes(llm_models_yaml_content, new_llm_models_yaml)
    picker_changed_ids = openrouter_shown + openrouter_hidden
    capability_changed_ids = _changed_openrouter_capability_ids(llm_models_yaml_content, new_llm_models_yaml)

    # 5. Print summary
    total_added = 0
    total_stale = 0
    total_deprecated = 0
    for provider, changes in all_changes.items():
        if provider in kept_providers:
            print(f"### {provider.title()}")
            print("- Not synced: its API call failed, so its current models were kept unchanged.")
            print()
            continue
        added = changes["added"]
        stale = changes.get("stale", [])
        total_added += len(added)
        total_stale += len(stale)
        entries = changes["entries"]
        dropdown = dropdown_by_provider[provider]
        dep_in_enum = sorted(e.value for e in entries if e.value in deprecated.get(provider, set()))
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
        collisions = changes.get("collisions", [])
        if collisions:
            print(f"- Skipped {len(collisions)} model(s) (enum name collision with existing entry):")
            for value, enum_name in collisions:
                print(f"  ! {value} → {enum_name}")
        if not added and not stale and not dep_in_enum:
            print(f"- No changes (total: {len(entries)}, dropdown: {len(dropdown)})")
        else:
            print(f"- Total models: {len(entries)} (dropdown: {len(dropdown)})")
        print()

    if seeded_reasoning_ids or cleared_reasoning_ids or picker_changed_ids or capability_changed_ids:
        print("### Registry")
        for model_id in seeded_reasoning_ids:
            print(f"  + {model_id} (reasoning)")
        for model_id in cleared_reasoning_ids:
            print(f"  - {model_id} (reasoning)")
        for model_id in openrouter_shown:
            print(f"  + openrouter {model_id} (picker)")
        for model_id in openrouter_hidden:
            print(f"  - openrouter {model_id} (picker)")
        for model_id in capability_changed_ids:
            print(f"  ~ openrouter {model_id} (parameters)")
        print()

    if changed_params:
        print("### Params")
        for yaml_key, (gained, changed, lost) in changed_params.items():
            print(f"- {yaml_key}: {gained} model(s) gain params, {changed} change, {lost} lose them")
        print()

    drift_agree, drift_disagree, drift_arrived = curated_drift(
        curated_catalog, generated_params, _yaml_model_ids(new_llm_models_yaml),
    )
    if drift_agree or drift_disagree or drift_arrived:
        print("### Curated drift")
    if drift_arrived:
        print(f"- {len(drift_arrived)} curated row(s) wait for a model the sync now lists; their awaitingSync can go:")
        for note in drift_arrived:
            print(f"  + {note}")
    if drift_disagree:
        print(f"- {len(drift_disagree)} curated row(s) disagree with the generated params; check which is right:")
        for note in drift_disagree:
            print(f"  ! {note}")
    if drift_agree:
        print(f"- {len(drift_agree)} curated row(s) repeat what the generated params now say; those fields can go:")
        for note in drift_agree:
            print(f"  = {note}")
    if drift_agree or drift_disagree or drift_arrived:
        print()

    thinking_without_levels = thinking_models_without_effort(dropdown_by_provider, prices, curated_catalog)
    if thinking_without_levels:
        print("### Thinking levels")
        print(
            f"- {len(thinking_without_levels)} picker model(s) think but have no curated effort levels; add them to "
            "llm-models-curated.yaml, and to THINKING_LEVELS_BY_MODEL in modelUtils.ts until the Gemini panel reads "
            "the catalog:"
        )
        for model_id in thinking_without_levels:
            print(f"  ? {model_id}")
        print()

    if not _should_write_files(
        total_added,
        seeded_reasoning_ids,
        cleared_reasoning_ids,
        picker_changed_ids,
        capability_changed_ids,
        args.force_regen,
        changed_params,
    ):
        if total_stale > 0:
            print(f"No new models found. {total_stale} stale model(s) flagged for manual review.")
        else:
            print("No changes found.")
        sys.exit(4 if failed_providers else 1)

    if total_added == 0 and args.force_regen:
        print("No new models found, but --force-regen set: regenerating files anyway.")

    if args.dry_run:
        print(f"\n**Dry run**: {total_added} added. No files written.")
        if total_stale > 0:
            print(f"{total_stale} stale model(s) flagged for manual review.")
        if total_deprecated > 0:
            print(f"{total_deprecated} deprecated model(s) excluded from dropdown.")
        sys.exit(3 if failed_providers else 0)

    # 6. Write files
    new_java_by_provider = {
        "openrouter": (OPENROUTER_JAVA, new_or_java),
        "openai": (OPENAI_JAVA, new_oa_java),
        "anthropic": (ANTHROPIC_JAVA, new_an_java),
        "gemini": (GEMINI_JAVA, new_ge_java),
        "vertexai": (VERTEXAI_JAVA, new_va_java),
    }
    files_to_write = [
        (path, content) for provider, (path, content) in new_java_by_provider.items() if provider not in kept_providers
    ] + [
        (PROVIDERS_TS, new_providers_ts),
        (MODELS_DATA_TS, new_models_data_ts),
        (LLM_MODELS_YAML, new_llm_models_yaml),
    ]
    for path, content in files_to_write:
        write_file(path, content)

    print(f"\nWrote changes ({total_added} added) across {len(files_to_write)} files.", file=sys.stderr)
    sys.exit(3 if failed_providers else 0)


if __name__ == "__main__":
    main()
