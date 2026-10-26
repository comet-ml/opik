#!/usr/bin/env python3
"""
Check each new or changed model in the model catalog against its provider, through a running Opik backend.

Compares the catalog files (llm-models-default.yaml, which the sync writes, and llm-models-curated.yaml, which
people write) with a base git ref. For each model that is new, or whose call fields or params changed, it reads the
params the backend serves (GET /v1/private/llm/models) and calls the model through /v1/private/chat/completions:

  plain       one short prompt and a low token limit, nothing else
  highest     every number the catalog offers at the top of its range, the token limit included
  lowest      the same at the bottom of each range
  refused     the settings the catalog says the model refuses, which the backend should leave out; a pass
              cannot tell that apart from a provider that takes them anyway
  effort X    once per effort level the catalog offers

OpenAI models are called on Chat Completions and again on the Responses API, since a route can refuse what the
model takes; a model whose params name an only_route is called on that route alone. When the plain call fails, the
rest of that model's calls on that route are skipped. A provider error that names a setting is a rejected claim, and
the call goes again without it, so one wrong value does not hide the others. Report-only: a failure is a result for
a person to read, not an error. The exit code is non-zero only when the check itself could not run.

A changed model that no picker offers (the backend serves it without a label) is listed as not checked rather than
called: nobody can pick it, and most such models are retired, not chat models, or a dated copy of a listed one, so a
failure there is one nobody acts on. A model past its provider's retirement date (read the way the sync reads it) is
listed as retired, picker or not. --models still calls both.

When nothing changed, it checks one cheap model per provider instead (the first in SMOKE_MODELS that is in the
registry and not retired), so a PR that touches only this script or its workflow still runs end to end.

Usage:
  python scripts/check_new_models.py --base-ref origin/main --dry-run --catalog-file models.json
  OPENAI_API_KEY=... ANTHROPIC_API_KEY=... GEMINI_API_KEY=... \\
    python scripts/check_new_models.py --base-ref origin/main --backend-url http://localhost:8080 --output report.md
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import NamedTuple
from urllib.parse import urlparse

import requests
import yaml
from sync_provider_models import build_deprecated_by_provider, load_model_prices

REPO_ROOT = Path(__file__).resolve().parent.parent
LLM_MODELS_YAML = Path("apps/opik-backend/src/main/resources/llm-models-default.yaml")
CURATED_YAML = Path("apps/opik-backend/src/main/resources/llm-models-curated.yaml")

REPORT_MARKER = "<!-- live-model-check -->"
PROMPT = "Reply with the single word: ok"
MAX_COMPLETION_TOKENS = 256
EFFORT_MAX_COMPLETION_TOKENS = 1024
REQUEST_TIMEOUT_SECONDS = 120
RETRY_DELAYS_SECONDS = [5, 20]
RETRYABLE_STATUS = {429, 500, 502, 503, 504, 529}
MAX_RESENDS = 3
ERROR_NOTE_MAX_CHARS = 200

PROVIDER_KEY_ENV = {
    "openai": "OPENAI_API_KEY",
    "anthropic": "ANTHROPIC_API_KEY",
    "gemini": "GEMINI_API_KEY",
}

PROVIDER_LABELS = {
    "openai": "OpenAI",
    "anthropic": "Anthropic",
    "gemini": "Gemini",
    "vertex-ai": "Vertex AI",
    "openrouter": "OpenRouter",
}

REASON_PRIORITY = {"new": 0, "changed": 1, "params": 2}
OVER_CAP_NOTE = "over the call cap"
NOT_IN_PICKER_NOTE = "not in a picker"
RETIRED_NOTE = "retired"

SMOKE_MODELS = {
    # gpt-4o-mini first: a model without reasoning takes the sampling settings, so the smoke run still sends them.
    "openai": ("gpt-4o-mini", "gpt-5-nano"),
    "anthropic": ("claude-haiku-4-5-20251001",),
    "gemini": ("gemini-2.5-flash-lite",),
}

# The fields that change what the backend sends or where it routes a plain prompt. Others, such
# as label (display only) or supportedParameters (no call sends them), are only counted.
CALL_FIELDS = ("qualifiedName", "structuredOutput", "reasoning")

ROUTES = {"openai": (None, "responses_api")}
CHAT_COMPLETIONS_ROUTE = "chat_completions_api"
ROUTE_KEY_CONFIGURATION = {"responses_api": {"openai_pipeline_mode": "responses_api"}}
ROUTE_LABELS = {None: "Chat Completions", "responses_api": "Responses"}

NUMBER_FIELDS = ("temperature", "top_p", "frequency_penalty", "presence_penalty")
FLAG_VALUES = {"stop": ["\n\n\n\n"], "seed": 7}
# Not 1: OpenAI reasoning models take temperature 1, their default, so it would pass even if the backend sent it.
REFUSED_VALUES = {"temperature": 0.5, "top_p": 0.5, "frequency_penalty": 0.5, "presence_penalty": 0.5, **FLAG_VALUES}

EFFORT_FIELDS = {
    "openai": lambda level: {"reasoning_effort": level},
    "anthropic": lambda level: {"custom_parameters": {"output_config": {"effort": level}}},
    "gemini": lambda level: {"custom_parameters": {"thinking": {"level": level}}},
    "vertex-ai": lambda level: {"custom_parameters": {"thinking": {"level": level}}},
}

# The playground sends these levels as no thinking setting at all, so their call would be the plain one.
EFFORT_LEVELS_SENT_AS_NOTHING = {"gemini": {"auto", "none"}, "vertex-ai": {"auto", "none"}}

# How providers spell each setting in an error, after camelCase and punctuation become underscores:
# OpenAI says max_tokens and reasoning.effort, Anthropic output_config.effort, Gemini "Thinking level".
FIELD_NAMES_IN_ERRORS = {
    "temperature": ("temperature",),
    "top_p": ("top_p", "topp"),
    "frequency_penalty": ("frequency_penalty",),
    "presence_penalty": ("presence_penalty",),
    "max_completion_tokens": ("max_completion_tokens", "max_tokens", "max_output_tokens"),
    "stop": ("stop", "stop_sequences"),
    "seed": ("seed",),
    "effort": ("reasoning_effort", "effort", "thinking_level"),
}

SECRET_PATTERNS = [
    re.compile(r"sk-[A-Za-z0-9_\-*.]{6,}"),
    re.compile(r"AIza[0-9A-Za-z_\-]{10,}"),
    re.compile(r"\b(proj|org)[_-][A-Za-z0-9]{4,}"),
    re.compile(r"(?i)(api[_-]?key|authorization|x-goog-api-key|bearer)\s*[:=]?\s*\S+"),
]


class ModelToCheck(NamedTuple):
    provider: str
    model_id: str
    request_model: str
    reason: str


class UnexercisedChange(NamedTuple):
    provider: str
    model_id: str
    fields: tuple[str, ...]


class Probe(NamedTuple):
    model: ModelToCheck
    route: str | None
    name: str
    fields: dict
    claims: tuple[str, ...]


class Rejection(NamedTuple):
    field: str
    value: object
    message: str


class CheckResult(NamedTuple):
    probe: Probe
    result: str
    seconds: float | None
    note: str
    rejections: tuple[Rejection, ...] = ()
    calls: int = 0


def load_registry_from_text(text: str) -> dict[str, list[dict]]:
    data = yaml.safe_load(text) or {}
    registry = {provider: list(models or []) for provider, models in data.items()}
    _check_rows(registry)
    return registry


def load_curated_from_text(text: str) -> dict:
    data = yaml.safe_load(text) or {}
    models = {provider: list(rows or []) for provider, rows in (data.get("models") or {}).items()}
    _check_rows(models)
    return {"defaults": data.get("defaults") or {}, "profiles": data.get("profiles") or {}, "models": models}


def _check_rows(registry: dict[str, list[dict]]) -> None:
    for provider, models in registry.items():
        for model in models:
            for field in ("id", "qualifiedName"):
                value = model.get(field)
                if (field == "id" or value is not None) and not (isinstance(value, str) and value.strip()):
                    raise RuntimeError(f"{provider} entry {model!r} has no usable {field}")


def read_at_ref(ref: str, path: Path, missing_ok: bool = False) -> str | None:
    completed = subprocess.run(
        ["git", "show", f"{ref}:{path.as_posix()}"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode == 0:
        return completed.stdout
    if missing_ok and _path_missing_at_ref(ref, path):
        return None
    raise RuntimeError(f"Cannot read {path} at {ref}: {completed.stderr.strip()}")


def _path_missing_at_ref(ref: str, path: Path) -> bool:
    commit = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"],
        cwd=REPO_ROOT,
        capture_output=True,
        check=False,
    )
    listed = subprocess.run(
        ["git", "ls-tree", "--name-only", ref, path.as_posix()],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return commit.returncode == 0 and listed.returncode == 0 and not listed.stdout.strip()


def load_catalog(ref: str | None) -> tuple[dict[str, list[dict]], dict]:
    if ref is None:
        registry_text = (REPO_ROOT / LLM_MODELS_YAML).read_text()
        curated_path = REPO_ROOT / CURATED_YAML
        curated_text = curated_path.read_text() if curated_path.exists() else None
    else:
        registry_text = read_at_ref(ref, LLM_MODELS_YAML)
        curated_text = read_at_ref(ref, CURATED_YAML, missing_ok=True)
    return load_registry_from_text(registry_text), load_curated_from_text(curated_text or "")


def request_model_name(model: dict) -> str:
    return model.get("qualifiedName") or model["id"]


def find_models_to_check(
    base: tuple[dict[str, list[dict]], dict], head: tuple[dict[str, list[dict]], dict]
) -> tuple[list[ModelToCheck], list[UnexercisedChange]]:
    base_registry, base_curated = base
    head_registry, head_curated = head
    to_check = []
    unexercised = []
    for provider in _ordered_union(head_registry, head_curated["models"]):
        base_rows = _rows_by_id(base_registry, base_curated, provider)
        for model_id, (row, curated_row) in _rows_by_id(head_registry, head_curated, provider).items():
            model = _merged_row(row, curated_row)
            before = base_rows.get(model_id)
            # Gaining onlyRoute only takes routes away and changes no request the backend sends, so it alone is no
            # reason to call a model again. Losing or changing it opens a route the check hasn't called.
            gained_only_route = (
                before is not None and _only_route(*before) is None and _only_route(row, curated_row) is not None
            )
            if before is None:
                reason = "new"
            elif _call_fields(_merged_row(*before)) != _call_fields(model):
                reason = "changed"
            elif _params_inputs(base_curated, provider, *before, gained_only_route) != _params_inputs(
                head_curated, provider, row, curated_row, gained_only_route
            ):
                reason = "params"
            else:
                reason = None
                old = _merged_row(*before)
                other = sorted(k for k in old.keys() | model.keys() if k != "id" and old.get(k) != model.get(k))
                if other:
                    unexercised.append(UnexercisedChange(provider, model_id, tuple(other)))
            if reason:
                to_check.append(ModelToCheck(provider, model_id, request_model_name(model), reason))
    return to_check, unexercised


def _ordered_union(*registries: dict) -> list[str]:
    providers = []
    for registry in registries:
        providers += [p for p in registry if p not in providers]
    return providers


def _rows_by_id(registry: dict[str, list[dict]], curated: dict, provider: str) -> dict[str, tuple]:
    rows = {m["id"]: (m, None) for m in registry.get(provider, [])}
    for curated_row in curated["models"].get(provider, []):
        rows[curated_row["id"]] = (rows.get(curated_row["id"], (None, None))[0], curated_row)
    return rows


def _merged_row(row: dict | None, curated_row: dict | None) -> dict:
    return {**(row or {}), **{k: v for k, v in (curated_row or {}).items() if k != "params"}}


def _params_inputs(
    curated: dict, provider: str, row: dict | None, curated_row: dict | None, without_only_route: bool = False
) -> tuple:
    # Every input the backend folds into a model's params, compared as a whole rather than merged here: a second
    # copy of the merge would be one more place for the rules to drift.
    defaults = curated["defaults"].get(provider) or {}
    reasoning = _flag(_merged_row(row, curated_row).get("reasoning"))
    names = [defaults.get("profile"), defaults.get("reasoningProfile") if reasoning else None]
    names += [(row or {}).get("profile"), (curated_row or {}).get("profile")]
    profiles = tuple((name, json.dumps(curated["profiles"].get(name), sort_keys=True)) for name in names if name)
    params = [(row or {}).get("params"), (curated_row or {}).get("params")]
    if without_only_route:
        params = [{k: v for k, v in p.items() if k != "onlyRoute"} if isinstance(p, dict) else p for p in params]
    return (*(json.dumps(p, sort_keys=True) for p in params), profiles)


def _only_route(row: dict | None, curated_row: dict | None) -> str | None:
    for source in (curated_row, row):
        params = (source or {}).get("params")
        if isinstance(params, dict) and params.get("onlyRoute") is not None:
            return params["onlyRoute"]
    return None


def _call_fields(model: dict) -> tuple:
    # A missing flag and false mean the same in the bundled file, which is the bottom layer.
    return (
        model.get("qualifiedName") or None,
        _flag(model.get("structuredOutput")),
        _flag(model.get("reasoning")),
    )


def _flag(value) -> bool:
    # Mirrors how the backend's YAML reader coerces a Boolean: "true" and non-zero numbers are true.
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() == "true"
    if isinstance(value, (int, float)):
        return value != 0
    return False


def smoke_models(head: dict[str, list[dict]], retired: dict[str, set[str]]) -> list[ModelToCheck]:
    to_check = []
    for provider, candidates in SMOKE_MODELS.items():
        for model_id in candidates:
            row = next((m for m in head.get(provider, []) if m["id"] == model_id), None)
            model = row and ModelToCheck(provider, model_id, request_model_name(row), "smoke")
            if model and not is_retired(model, retired):
                to_check.append(model)
                break
    return to_check


def retired_models() -> dict[str, set[str]]:
    # Read the way the sync reads them, so the check and the picker agree on which models are gone.
    return build_deprecated_by_provider(load_model_prices())


def is_retired(model: ModelToCheck, retired: dict[str, set[str]]) -> bool:
    if model.provider == "vertex-ai":
        return model.request_model in retired.get("vertexai", set())
    return model.model_id in retired.get(model.provider, set())


def plan_probes(model: ModelToCheck, params: dict | None) -> list[Probe]:
    if not params:
        return [Probe(model, None, "plain", {"max_completion_tokens": MAX_COMPLETION_TOKENS}, ())]
    probes = []
    routes = ROUTES.get(model.provider, (None,))
    only_route = params.get("only_route")
    if only_route is not None and only_route not in [route or CHAT_COMPLETIONS_ROUTE for route in routes]:
        raise ValueError(
            f"{model.provider}/{model.model_id} has only_route {only_route!r}, which names none of its routes"
        )
    for route in routes:
        if only_route is not None and (route or CHAT_COMPLETIONS_ROUTE) != only_route:
            continue
        route_params = params if route is None else (params.get("routes") or {}).get(route, params)
        probes += _route_probes(model, route, route_params)
    return probes


def _route_probes(model: ModelToCheck, route: str | None, params: dict) -> list[Probe]:
    tokens = params.get("max_output_tokens")
    probes = [Probe(model, route, "plain", {"max_completion_tokens": _clamp(MAX_COMPLETION_TOKENS, tokens)}, ())]

    highest, lowest = {}, {}
    for field in NUMBER_FIELDS:
        if _offers_range(params.get(field)):
            highest[field] = params[field]["max"]
            lowest[field] = params[field]["min"]
    if params.get("sampling_exclusive") and "temperature" in highest and "top_p" in highest:
        # The panel sends one of the two, never both, so each goes alone, at the end of its range a
        # provider is likelier to refuse: temperature at its top, top_p at its bottom.
        del highest["top_p"]
        del lowest["temperature"]
    for field, value in FLAG_VALUES.items():
        if _accepts(params.get(field)):
            highest[field] = value
    if _offers_range(tokens):
        highest["max_completion_tokens"] = tokens["max"]
        lowest["max_completion_tokens"] = max(tokens["min"], 1)
    for name, fields in (("highest", highest), ("lowest", lowest)):
        if fields:
            probes.append(Probe(model, route, name, _with_token_limit(fields, tokens), tuple(fields)))

    refused = {field: value for field, value in REFUSED_VALUES.items() if _refuses(params.get(field))}
    if refused:
        probes.append(Probe(model, route, "refused", _with_token_limit(refused, tokens), tuple(refused)))

    effort = params.get("effort") or {}
    if effort.get("accepted") is True and model.provider in EFFORT_FIELDS:
        limit = _clamp(EFFORT_MAX_COMPLETION_TOKENS, tokens)
        for level in effort.get("levels") or []:
            if level in EFFORT_LEVELS_SENT_AS_NOTHING.get(model.provider, ()):
                continue
            fields = {"effort": level, "max_completion_tokens": limit}
            probes.append(Probe(model, route, f"effort {level}", fields, ("effort",)))
    return probes


def _offers_range(spec) -> bool:
    return (
        _accepts(spec)
        and isinstance(spec.get("min"), (int, float))
        and isinstance(spec.get("max"), (int, float))
        and spec["min"] <= spec["max"]
    )


def _accepts(spec) -> bool:
    return isinstance(spec, dict) and spec.get("accepted") is True


def _refuses(spec) -> bool:
    return isinstance(spec, dict) and spec.get("accepted") is False


def _clamp(value: int, tokens) -> int:
    if not _offers_range(tokens):
        return value
    return int(max(tokens["min"], 1, min(value, tokens["max"])))


def _with_token_limit(fields: dict, tokens) -> dict:
    if "max_completion_tokens" in fields:
        return dict(fields)
    return {**fields, "max_completion_tokens": _clamp(MAX_COMPLETION_TOKENS, tokens)}


def request_body(provider: str, request_model: str, fields: dict) -> dict:
    body = {"model": request_model, "messages": [{"role": "user", "content": PROMPT}], "stream": False}
    for field, value in fields.items():
        if field == "effort":
            body.update(EFFORT_FIELDS[provider](value))
        else:
            body[field] = value
    return body


def named_fields(message: str, fields) -> list[str]:
    words = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", message).lower()
    words = "_" + re.sub(r"[^a-z0-9]+", "_", words) + "_"
    return [f for f in fields if any(f"_{name}_" in words for name in FIELD_NAMES_IN_ERRORS.get(f, (f,)))]


def sanitize(text: str, secrets: list[str]) -> str:
    for secret in secrets:
        text = text.replace(secret, "[redacted]")
    for pattern in SECRET_PATTERNS:
        text = pattern.sub("[redacted]", text)
    text = " ".join(text.split()).replace("|", "/").replace("<", "&lt;").replace(">", "&gt;")
    if len(text) > ERROR_NOTE_MAX_CHARS:
        text = text[: ERROR_NOTE_MAX_CHARS - 1] + "…"
    return text


def error_text(response: requests.Response) -> str:
    try:
        body = response.json()
    except ValueError:
        return f"HTTP {response.status_code}"
    if isinstance(body, dict):
        message = body.get("message") or body.get("errors") or body.get("error")
        if isinstance(message, list):
            message = "; ".join(str(m) for m in message)
        if isinstance(message, dict):
            message = message.get("message")
        if message:
            return f"HTTP {response.status_code}: {message}"
    return f"HTTP {response.status_code}"


class Backend:
    def __init__(self, base_url: str, secrets: list[str]):
        self.base_url = base_url.rstrip("/")
        self.secrets = secrets
        self.session = requests.Session()
        self.created_keys: dict[str, str] = {}

    def check_alive(self) -> None:
        response = self.session.get(f"{self.base_url}/is-alive/ping", timeout=30)
        if response.status_code != 200:
            raise RuntimeError(f"Backend at {self.base_url} is not up (HTTP {response.status_code})")

    def models(self) -> dict[str, dict[str, dict]]:
        response = self.session.get(f"{self.base_url}/v1/private/llm/models", timeout=30)
        if response.status_code != 200:
            raise RuntimeError(f"Could not read the model catalog: {sanitize(error_text(response), self.secrets)}")
        return catalog_by_id(response.json())

    def add_provider_key(self, provider: str, api_key: str) -> None:
        response = self.session.post(
            f"{self.base_url}/v1/private/llm-provider-key",
            json={"provider": provider, "api_key": api_key, "name": f"live-model-check-{provider}"},
            timeout=30,
        )
        if response.status_code not in (200, 201, 409):
            raise RuntimeError(f"Could not add the {provider} key: {sanitize(error_text(response), self.secrets)}")
        location = response.headers.get("Location")
        if response.status_code == 201 and location:
            self.created_keys[provider] = urlparse(location).path.rstrip("/").rsplit("/", 1)[-1]

    def set_route(self, provider: str, route: str | None) -> bool:
        # Only on a key this run created: a key that was already there may be someone's, set up their way.
        key_id = self.created_keys.get(provider)
        if key_id is None:
            return route is None
        response = self.session.patch(
            f"{self.base_url}/v1/private/llm-provider-key/{key_id}",
            json={"configuration": ROUTE_KEY_CONFIGURATION.get(route, {})},
            timeout=30,
        )
        if response.status_code not in (200, 204):
            raise RuntimeError(
                f"Could not switch the {provider} key to {ROUTE_LABELS[route]}: "
                f"{sanitize(error_text(response), self.secrets)}"
            )
        return True

    def run(self, probe: Probe, max_calls: int) -> CheckResult:
        fields = dict(probe.fields)
        rejections = []
        started = time.monotonic()
        calls = 0
        while True:
            status, note = self._send(request_body(probe.model.provider, probe.model.request_model, fields))
            calls += 1
            if status == 200:
                result = "fail" if rejections else "pass"
                if rejections:
                    note = "passes without " + ", ".join(r.field for r in rejections)
                break
            if status is None or status in RETRYABLE_STATUS:
                result = "unclear"
                break
            named = [f for f in named_fields(note, fields) if f in probe.claims]
            result = "fail"
            rejections += [Rejection(f, fields.pop(f), note) for f in named]
            if not named or not any(f in probe.claims for f in fields) or calls > MAX_RESENDS:
                break
            if calls >= max_calls:
                note = f"{OVER_CAP_NOTE} before checking " + ", ".join(f for f in fields if f in probe.claims)
                break
        return CheckResult(probe, result, round(time.monotonic() - started, 1), note, tuple(rejections), calls)

    def _send(self, payload: dict) -> tuple[int | None, str]:
        response = None
        note = ""
        attempts = len(RETRY_DELAYS_SECONDS) + 1
        for attempt in range(attempts):
            try:
                response = self.session.post(
                    f"{self.base_url}/v1/private/chat/completions",
                    json=payload,
                    timeout=REQUEST_TIMEOUT_SECONDS,
                )
            except requests.RequestException as e:
                response = None
                note = f"request failed: {type(e).__name__}"
            else:
                if response.status_code not in RETRYABLE_STATUS:
                    break
                note = error_text(response)
            if attempt < attempts - 1:
                time.sleep(RETRY_DELAYS_SECONDS[attempt])
        if response is None:
            return None, sanitize(note, self.secrets)
        if response.status_code != 200:
            return response.status_code, sanitize(error_text(response), self.secrets)
        return 200, _answer_note(response)


def catalog_by_id(api_response: dict) -> dict[str, dict[str, dict]]:
    return {provider: {m["id"]: m for m in models or []} for provider, models in (api_response or {}).items()}


def _answer_note(response: requests.Response) -> str:
    try:
        choices = response.json().get("choices") or []
    except ValueError:
        return "no JSON body"
    if not choices:
        return "no choices returned"
    content = (choices[0].get("message") or {}).get("content")
    finish_reason = choices[0].get("finish_reason")
    if not content:
        return f"empty answer (finish reason: {finish_reason})"
    return ""


RESULT_ORDER = {"fail": 0, "unclear": 1, "skipped": 2, "pass": 3, "would call": 4, "not checked": 5}
MAX_TABLE_ROWS = 150


def render_report(
    results: list[CheckResult],
    unexercised: list[UnexercisedChange],
    mode: str,
    base_ref: str,
    call_cap: int,
    cut: int,
) -> str:
    lines = [REPORT_MARKER, "## Live model check", "", f"**{_headline(results, unexercised, mode)}**", ""]
    if mode == "smoke" and results:
        lines.append(
            "No model is new or changed in a field this check exercises, "
            "so this run checked one model per provider to test the harness."
        )
        lines.append("")
    compared = (
        "Checked the models named on the command line."
        if mode == "asked"
        else f"Compared `{LLM_MODELS_YAML.name}` and `{CURATED_YAML.name}` with `{base_ref}`."
    )
    lines.append(
        f"{compared} Each model is called "
        "through the backend with the params its API serves: once plain, then with the numbers at the top and the "
        "bottom of their ranges (only one of temperature and top_p for a model that takes one at a time), with the "
        "settings it refuses, and once per effort level that sets a request field (not the Gemini and Vertex AI "
        "`auto` or `none` levels). An OpenAI model the catalog serves on one route only (`only_route`) is called on "
        "that route alone. Resending without a rejected setting counts toward the call cap. A `refused` call "
        "passes when the backend leaves those settings out or when the provider takes them anyway, so it fails only "
        "when they reach a provider that rejects them. A failure is for a person to read: it can be a real gap in "
        "the model data, or a provider-side limit."
    )
    providers = _provider_order(results)
    if providers:
        lines.append("")
        for provider in providers:
            rows = [r for r in results if r.probe.model.provider == provider]
            lines.append(f"- **{PROVIDER_LABELS.get(provider, provider)}:** {_counts(rows)}")
    if cut:
        lines += ["", f"The call cap of {call_cap} cut {_models(cut)}; they are listed as not checked."]

    rejections = [(r, rejection) for r in results for rejection in r.rejections]
    if rejections:
        lines += [
            "",
            "### Settings the provider rejected",
            "",
            (
                "Each row is a claim the catalog makes that the provider refused: the model data needs a row that "
                "narrows or refuses it. On a `refused` call, the backend sent a setting the catalog says it leaves out."
            ),
            "",
            "| Provider | Model | Route | Call | Setting | Sent | Provider said |",
            "| --- | --- | --- | --- | --- | --- | --- |",
        ]
        for r, rejection in rejections:
            lines.append(
                f"| {_provider_label(r)} | {_code(r.probe.model.request_model)} | {_route(r.probe)} | "
                f"{r.probe.name} | `{rejection.field}` | {_code(json.dumps(rejection.value))} | {rejection.message} |"
            )

    others = [r for r in results if r.result in ("fail", "unclear") and not r.rejections]
    if others:
        lines += [
            "",
            "### Other failures",
            "",
            "A failure that names no setting the call tested, or a provider error that retries did not clear.",
            "",
            "| Provider | Model | Route | Call | Why | Result | Note |",
            "| --- | --- | --- | --- | --- | --- | --- |",
        ]
        for r in others:
            lines.append(
                f"| {_provider_label(r)} | {_code(r.probe.model.request_model)} | {_route(r.probe)} | "
                f"{r.probe.name} | {r.probe.model.reason} | {r.result} | {r.note} |"
            )

    for provider in providers:
        rows = sorted((r for r in results if r.probe.model.provider == provider), key=lambda r: RESULT_ORDER[r.result])
        shown = rows[:MAX_TABLE_ROWS]
        models = len({r.probe.model.model_id for r in rows})
        lines += _details(
            f"{PROVIDER_LABELS.get(provider, provider)}: {_models(models)}, {_calls(sum(r.calls for r in rows))}",
            ["| Model | Route | Call | Sent | Result | Seconds | Note |", "| --- | --- | --- | --- | --- | --- | --- |"]
            + [
                f"| {_code(r.probe.model.request_model)} | {_route(r.probe)} | {r.probe.name} | "
                f"{_sent(r.probe)} | {r.result} | {_seconds(r.seconds)} | {r.note} |"
                for r in shown
            ]
            + ([] if len(rows) == len(shown) else ["", f"{len(rows) - len(shown)} more not shown."]),
        )

    if unexercised:
        table = ["| Provider | Models | Fields that changed |", "| --- | --- | --- |"]
        for provider in _provider_order(unexercised):
            changes = [u for u in unexercised if u.provider == provider]
            field_counts = {}
            for change in changes:
                for field in change.fields:
                    field_counts[field] = field_counts.get(field, 0) + 1
            fields = ", ".join(f"{_code(f)} ({n})" for f, n in sorted(field_counts.items()))
            table.append(f"| {PROVIDER_LABELS.get(provider, provider)} | {len(changes)} | {fields} |")
        lines += _details(
            f"{_models(len(unexercised))} changed only in fields this check doesn't exercise",
            [f"This check exercises {', '.join(f'`{f}`' for f in CALL_FIELDS)} and `params`.", ""] + table,
        )
    lines.append("")
    return "\n".join(lines)


def _headline(results: list[CheckResult], unexercised: list[UnexercisedChange], mode: str) -> str:
    calls = sum(r.calls for r in results)
    planned = [r for r in results if r.result == "would call"]
    not_checked = [r for r in results if r.result == "not checked"]
    if calls:
        failed = {r.probe.model for r in results if r.result == "fail"}
        rejected = sum(len(r.rejections) for r in results)
        passed = sum(1 for r in results if r.result == "pass")
        made = sum(1 for r in results if r.result in ("pass", "fail", "unclear"))
        headline = f"{passed} of {made} checks passed ({_calls(calls)})"
        if rejected:
            headline += f", {rejected} rejected {'setting' if rejected == 1 else 'settings'}"
        if failed:
            headline += f"; failures on {_models(len(failed))}"
        return headline
    if planned:
        return f"Dry run: {_calls(len(planned))} planned for {_models(len({r.probe.model for r in planned}))}"
    if mode == "smoke" or not not_checked:
        if unexercised:
            return "Nothing called: no model is new or changed in a field this check exercises"
        return "No new or changed models"
    n = len({r.probe.model for r in not_checked})
    models = "1 new or changed model is" if n == 1 else f"{n} new or changed models are"
    if all(r.note == "no CI key" for r in not_checked):
        return f"Nothing called: {models} on providers without a CI key"
    return f"Nothing called: {models} not checked"


def _counts(rows: list[CheckResult]) -> str:
    parts = []
    for result, word in (
        ("pass", "passed"),
        ("fail", "failed"),
        ("unclear", "unclear"),
        ("skipped", "skipped"),
        ("would call", "to call"),
    ):
        n = sum(1 for r in rows if r.result == result)
        if n:
            parts.append(f"{n} {word}")
    reasons = {}
    for r in rows:
        if r.result == "not checked":
            reasons.setdefault(r.note, set()).add(r.probe.model)
    parts += [f"{_models(len(models))} not checked ({note})" for note, models in reasons.items()]
    return ", ".join(parts)


def _details(summary: str, body: list[str]) -> list[str]:
    return ["", "<details>", f"<summary>{summary}</summary>", ""] + body + ["", "</details>"]


def _code(text: str) -> str:
    return "`" + " ".join(str(text).split()).replace("`", "'").replace("|", "\\|") + "`"


def _sent(probe: Probe) -> str:
    return ", ".join(_code(f"{field}={json.dumps(value)}") for field, value in probe.fields.items())


def _route(probe: Probe) -> str:
    return ROUTE_LABELS[probe.route] if probe.model.provider in ROUTES else ""


def _provider_label(result: CheckResult) -> str:
    return PROVIDER_LABELS.get(result.probe.model.provider, result.probe.model.provider)


def _models(n: int) -> str:
    return f"{n} model" if n == 1 else f"{n} models"


def _calls(n: int) -> str:
    return f"{n} call" if n == 1 else f"{n} calls"


def _seconds(seconds: float | None) -> str:
    return "" if seconds is None else f"{seconds:.1f}"


def _provider_order(items: list) -> list[str]:
    seen = []
    for item in items:
        provider = item.probe.model.provider if isinstance(item, CheckResult) else item.provider
        if provider not in seen:
            seen.append(provider)
    rank = {provider: i for i, provider in enumerate(PROVIDER_LABELS)}
    return sorted(seen, key=lambda p: rank.get(p, len(rank)))


def plan(
    args: argparse.Namespace, retired: dict[str, set[str]]
) -> tuple[list[ModelToCheck], list[UnexercisedChange], str]:
    head = load_catalog(args.head_ref)
    if args.models:
        rows = {(p, m["id"]): m for p, models in head[0].items() for m in models}
        return (
            [
                ModelToCheck(
                    provider, model_id, request_model_name(rows.get((provider, model_id), {"id": model_id})), "asked"
                )
                for provider, model_id in args.models
            ],
            [],
            "asked",
        )
    to_check, unexercised = find_models_to_check(load_catalog(args.base_ref), head)
    if to_check:
        return to_check, unexercised, "changes"
    return smoke_models(head[0], retired), unexercised, "smoke"


def run(args: argparse.Namespace) -> int:
    retired = retired_models()
    to_check, unexercised, mode = plan(args, retired)
    keys = {p: os.environ.get(env, "") for p, env in PROVIDER_KEY_ENV.items()}
    secrets = [k for k in keys.values() if k]

    backend = None if args.dry_run and args.catalog_file else Backend(args.backend_url, secrets)
    if backend is not None:
        backend.check_alive()
    if args.catalog_file:
        catalog = catalog_by_id(json.loads(Path(args.catalog_file).read_text()))
    else:
        catalog = backend.models()

    results = []
    queues = {}
    for model in sorted(to_check, key=lambda m: REASON_PRIORITY.get(m.reason, len(REASON_PRIORITY))):
        served = catalog.get(model.provider, {}).get(model.model_id)
        probes = plan_probes(model, (served or {}).get("params"))
        if served is None:
            results += [CheckResult(p, "not checked", None, "the backend doesn't list this model") for p in probes]
        elif mode != "asked" and is_retired(model, retired):
            results += [CheckResult(p, "not checked", None, RETIRED_NOTE) for p in probes]
        elif mode == "changes" and not served.get("label"):
            results += [CheckResult(p, "not checked", None, NOT_IN_PICKER_NOTE) for p in probes]
        elif not keys.get(model.provider):
            results += [CheckResult(p, "not checked", None, "no CI key") for p in probes]
        else:
            queues.setdefault(model.provider, []).append(probes)
    planned, over_cap = share_call_cap(queues, args.max_calls)
    results += [CheckResult(p, "not checked", None, OVER_CAP_NOTE) for p in over_cap]

    if args.dry_run:
        results += [CheckResult(p, "would call", None, "") for p in planned]
    else:
        for provider in sorted({p.model.provider for p in planned}):
            backend.add_provider_key(provider, keys[provider])
        results += run_probes(backend, planned, args.max_calls)
    cut = len({r.probe.model for r in results if r.note.startswith(OVER_CAP_NOTE)})

    report = render_report(results, unexercised, mode, args.base_ref, args.max_calls, cut)
    if args.output:
        Path(args.output).write_text(report)
    else:
        print(report)
    return 0


def share_call_cap(queues: dict[str, list[list[Probe]]], max_calls: int) -> tuple[list[Probe], list[Probe]]:
    # One model per provider per round: an OpenAI model plans two routes and every effort level, so filling the
    # cap in file order spends it all on OpenAI and leaves the other providers unchecked.
    planned = []
    over_cap = []
    rounds = max((len(q) for q in queues.values()), default=0)
    for i in range(rounds):
        for queue in queues.values():
            if i >= len(queue):
                continue
            probes = queue[i]
            if len(planned) + len(probes) > max_calls:
                over_cap += probes
            else:
                planned += probes
    return planned, over_cap


def run_probes(backend: Backend, probes: list[Probe], max_calls: int) -> list[CheckResult]:
    results = []
    calls = 0
    by_route = {}
    for probe in probes:
        by_route.setdefault((probe.model.provider, probe.route), []).append(probe)
    for (provider, route), route_probes in sorted(by_route.items(), key=lambda item: (item[0][0], item[0][1] or "")):
        if not backend.set_route(provider, route):
            results += [CheckResult(p, "not checked", None, "the key was already there") for p in route_probes]
            continue
        try:
            failed_plain = set()
            for probe in route_probes:
                if probe.model in failed_plain:
                    results.append(CheckResult(probe, "skipped", None, "the plain call failed"))
                    continue
                if calls >= max_calls:
                    results.append(CheckResult(probe, "not checked", None, OVER_CAP_NOTE))
                    continue
                result = backend.run(probe, max_calls - calls)
                calls += result.calls
                if probe.name == "plain" and result.result != "pass":
                    failed_plain.add(probe.model)
                print(
                    f"  {result.result:7}  {provider}/{probe.model.request_model}  {ROUTE_LABELS[route]}  "
                    f"{probe.name}  {result.seconds}s  {result.note}",
                    flush=True,
                )
                results.append(result)
        except BaseException:
            try:
                backend.set_route(provider, None)
            except (RuntimeError, requests.RequestException) as reset_error:
                print(f"::warning::Could not switch the {provider} key back: {reset_error}", file=sys.stderr)
            raise
        backend.set_route(provider, None)
    return results


def provider_and_id(value: str) -> tuple[str, str]:
    provider, _, model_id = value.partition("/")
    if not provider or not model_id:
        raise argparse.ArgumentTypeError(f"expected PROVIDER/ID, got {value!r}")
    return provider, model_id


def non_negative_int(value: str) -> int:
    number = int(value)
    if number < 0:
        raise argparse.ArgumentTypeError(f"must be 0 or more, got {number}")
    return number


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-ref", required=True, help="Git ref to compare the catalog against")
    parser.add_argument("--head-ref", help="Git ref for the new catalog (default: the working tree)")
    parser.add_argument("--backend-url", default="http://localhost:8080")
    parser.add_argument("--output", help="Write the Markdown report here (default: stdout)")
    parser.add_argument("--max-calls", type=non_negative_int, default=200)
    parser.add_argument("--dry-run", action="store_true", help="List what would be called, make no calls")
    parser.add_argument(
        "--catalog-file", help="Read the params from this saved GET /v1/private/llm/models response instead"
    )
    parser.add_argument(
        "--models",
        nargs="+",
        type=provider_and_id,
        metavar="PROVIDER/ID",
        help="Check these models instead of the new or changed ones",
    )
    args = parser.parse_args()
    try:
        return run(args)
    except (RuntimeError, requests.RequestException, yaml.YAMLError, ValueError) as e:
        print(f"::error::Live model check could not run: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
