#!/usr/bin/env python3
"""
Write OpenRouter's model prices and limits, in the format of LiteLLM's price file, to Opik's own price file.

LiteLLM removed its `litellm_provider: openrouter` rows (BerriAI/litellm#45623), which it had generated from the same
public OpenRouter model list this script reads. Opik still needs them: the backend prices OpenRouter spans with them,
and the frontend reads OpenRouter models' output-token limits and input modalities from them.

Usage:
  python scripts/update_openrouter_model_prices.py

Exits non-zero without writing anything when the model list can't be fetched or holds no priced model, so a failed
run keeps the current file.
"""

import json
import math
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

OPENROUTER_API_URL = "https://openrouter.ai/api/v1/models"
OUTPUT_FILES = [
    Path("apps/opik-backend/src/main/resources/model_prices_openrouter.json"),
    Path("apps/opik-frontend/src/data/model_prices_openrouter.json"),
]

# OpenRouter's price name -> LiteLLM's field name.
PRICE_FIELDS = {
    "prompt": "input_cost_per_token",
    "completion": "output_cost_per_token",
    "input_cache_read": "cache_read_input_token_cost",
    "input_cache_write": "cache_creation_input_token_cost",
    "input_cache_write_1h": "cache_creation_input_token_cost_above_1hr",
    "audio": "input_cost_per_audio_token",
    "internal_reasoning": "output_cost_per_reasoning_token",
}

# OpenRouter's price name -> LiteLLM's field name prefix for a prompt-size tier.
TIER_PRICE_FIELDS = {
    "prompt": "input_cost_per_token",
    "completion": "output_cost_per_token",
    "input_cache_read": "cache_read_input_token_cost",
    "input_cache_write": "cache_creation_input_token_cost",
}

INPUT_MODALITY_FIELDS = {
    "image": "supports_vision",
    "audio": "supports_audio_input",
    "video": "supports_video_input",
    "file": "supports_pdf_input",
}

SUPPORTED_PARAMETER_FIELDS = {
    "tools": "supports_function_calling",
    "tool_choice": "supports_tool_choice",
    "structured_outputs": "supports_response_schema",
    "reasoning": "supports_reasoning",
}


def fetch_models() -> list[dict]:
    request = urllib.request.Request(OPENROUTER_API_URL, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["data"]


def _price(value) -> float | None:
    """The price as a number, or None when it isn't one: routers whose price depends on the model they pick list -1."""
    try:
        price = float(value)
    except (TypeError, ValueError):
        return None
    return price if math.isfinite(price) and price >= 0 else None


def _positive_int(value) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) and value > 0 else None


def _tier_prices(overrides) -> dict:
    row = {}
    for override in overrides if isinstance(overrides, list) else []:
        min_prompt_tokens = _positive_int(override.get("min_prompt_tokens")) if isinstance(override, dict) else None
        # Off-peak overrides (utc_start, utc_days) depend on the time of the request, which a span's cost can't.
        if min_prompt_tokens is None or min_prompt_tokens % 1000 or any(key.startswith("utc_") for key in override):
            continue
        for name, field in TIER_PRICE_FIELDS.items():
            price = _price(override.get(name))
            if price is not None:
                row[f"{field}_above_{min_prompt_tokens // 1000}k_tokens"] = price
    return row


def to_price_row(model: dict) -> dict:
    pricing = model.get("pricing") or {}
    architecture = model.get("architecture") or {}
    top_provider = model.get("top_provider") or {}
    input_modalities = set(architecture.get("input_modalities") or [])
    supported_parameters = set(model.get("supported_parameters") or [])

    row: dict = {"litellm_provider": "openrouter", "mode": "chat"}
    for name, field in PRICE_FIELDS.items():
        price = _price(pricing.get(name))
        if price is not None:
            row[field] = price
    row.update(_tier_prices(pricing.get("overrides")))

    max_input_tokens = _positive_int(top_provider.get("context_length")) or _positive_int(model.get("context_length"))
    if max_input_tokens:
        row["max_input_tokens"] = max_input_tokens
    max_output_tokens = _positive_int(top_provider.get("max_completion_tokens"))
    if max_output_tokens:
        row["max_output_tokens"] = max_output_tokens
        row["max_tokens"] = max_output_tokens

    for modality, field in INPUT_MODALITY_FIELDS.items():
        row[field] = modality in input_modalities
    for parameter, field in SUPPORTED_PARAMETER_FIELDS.items():
        row[field] = parameter in supported_parameters
    row["supports_prompt_caching"] = "cache_read_input_token_cost" in row
    if model.get("expiration_date"):
        row["deprecation_date"] = model["expiration_date"]
    row["source"] = OPENROUTER_API_URL
    return dict(sorted(row.items()))


def build_price_rows(models: list[dict]) -> dict[str, dict]:
    rows = {
        f"openrouter/{model['id']}": to_price_row(model)
        for model in models
        if isinstance(model, dict) and isinstance(model.get("id"), str) and model["id"]
    }
    return dict(sorted(rows.items()))


def main() -> int:
    try:
        rows = build_price_rows(fetch_models())
    except Exception as e:
        print(f"::error::OpenRouter model list fetch failed, kept the current prices: {e}", file=sys.stderr)
        return 1
    # A 200 with no priced model is an outage, not an answer: writing it would drop every OpenRouter price.
    if not any("input_cost_per_token" in row for row in rows.values()):
        print("::error::OpenRouter model list has no priced model, kept the current prices", file=sys.stderr)
        return 1

    # Infinity and NaN aren't JSON, and the backend would fail to load the file.
    content = json.dumps(rows, indent=4, allow_nan=False) + "\n"
    for path in OUTPUT_FILES:
        (REPO_ROOT / path).write_text(content, encoding="utf-8")
    print(f"Wrote {len(rows)} OpenRouter models to {', '.join(str(path) for path in OUTPUT_FILES)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
