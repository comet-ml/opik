#!/usr/bin/env python3
"""
Call each new or changed model in the model registry YAML through a running Opik backend.

Compares apps/opik-backend/src/main/resources/llm-models-default.yaml with a base git ref,
sends one short prompt per new or changed model to /v1/private/chat/completions, and writes
a Markdown report. Report-only: a model that fails is a result, not an error. The exit code
is non-zero only when the check itself could not run.

When nothing changed, it calls one cheap model per provider instead (from SMOKE_MODELS, when
that model is in the registry), so a PR that touches only this script or its workflow still
runs end to end.

Usage:
  python scripts/check_new_models.py --base-ref origin/main --dry-run
  OPENAI_API_KEY=... ANTHROPIC_API_KEY=... GEMINI_API_KEY=... \\
    python scripts/check_new_models.py --base-ref origin/main --backend-url http://localhost:8080 --output report.md
"""

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import NamedTuple

import requests
import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
LLM_MODELS_YAML = Path("apps/opik-backend/src/main/resources/llm-models-default.yaml")

REPORT_MARKER = "<!-- live-model-check -->"
PROMPT = "Reply with the single word: ok"
MAX_COMPLETION_TOKENS = 256
REQUEST_TIMEOUT_SECONDS = 120
RETRY_DELAYS_SECONDS = [5, 20]
RETRYABLE_STATUS = {429, 500, 502, 503, 504, 529}
ERROR_NOTE_MAX_CHARS = 140

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

SMOKE_MODELS = {
    "openai": "gpt-4.1-nano",
    "anthropic": "claude-haiku-4-5-20251001",
    "gemini": "gemini-2.5-flash-lite",
}

IDENTITY_FIELDS = ("id", "label")

SECRET_PATTERNS = [
    re.compile(r"sk-[A-Za-z0-9_\-*.]{6,}"),
    re.compile(r"AIza[0-9A-Za-z_\-]{10,}"),
    re.compile(r"(?i)(api[_-]?key|authorization|x-goog-api-key|bearer)\s*[:=]?\s*\S+"),
]


class ModelToCheck(NamedTuple):
    provider: str
    model_id: str
    request_model: str
    reason: str


class CheckResult(NamedTuple):
    model: ModelToCheck
    result: str
    seconds: float | None
    note: str


def load_registry_from_text(text: str) -> dict[str, list[dict]]:
    data = yaml.safe_load(text) or {}
    registry = {provider: list(models or []) for provider, models in data.items()}
    for provider, models in registry.items():
        for model in models:
            for field in ("id", "qualifiedName"):
                value = model.get(field)
                if (field == "id" or value is not None) and not (isinstance(value, str) and value.strip()):
                    raise RuntimeError(f"{provider} entry {model!r} has no usable {field}")
    return registry


def load_registry_at_ref(ref: str) -> dict[str, list[dict]]:
    completed = subprocess.run(
        ["git", "show", f"{ref}:{LLM_MODELS_YAML.as_posix()}"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"Cannot read {LLM_MODELS_YAML} at {ref}: {completed.stderr.strip()}")
    return load_registry_from_text(completed.stdout)


def request_model_name(model: dict) -> str:
    return model.get("qualifiedName") or model["id"]


def find_models_to_check(base: dict[str, list[dict]], head: dict[str, list[dict]]) -> list[ModelToCheck]:
    to_check = []
    for provider, models in head.items():
        base_by_id = {m["id"]: m for m in base.get(provider, [])}
        for model in models:
            before = base_by_id.get(model["id"])
            if before is None:
                reason = "new"
            elif _behaviour_fields(before) != _behaviour_fields(model):
                reason = "changed"
            else:
                continue
            to_check.append(ModelToCheck(provider, model["id"], request_model_name(model), reason))
    return to_check


def _behaviour_fields(model: dict) -> dict:
    return {k: v for k, v in model.items() if k not in IDENTITY_FIELDS}


def smoke_models(head: dict[str, list[dict]]) -> list[ModelToCheck]:
    to_check = []
    for provider, model_id in SMOKE_MODELS.items():
        model = next((m for m in head.get(provider, []) if m["id"] == model_id), None)
        if model is not None:
            to_check.append(ModelToCheck(provider, model_id, request_model_name(model), "smoke"))
    return to_check


def sanitize(text: str, secrets: list[str]) -> str:
    for secret in secrets:
        text = text.replace(secret, "[redacted]")
    for pattern in SECRET_PATTERNS:
        text = pattern.sub("[redacted]", text)
    text = " ".join(text.split()).replace("|", "/")
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

    def check_alive(self) -> None:
        response = self.session.get(f"{self.base_url}/is-alive/ping", timeout=30)
        if response.status_code != 200:
            raise RuntimeError(f"Backend at {self.base_url} is not up (HTTP {response.status_code})")

    def add_provider_key(self, provider: str, api_key: str) -> None:
        response = self.session.post(
            f"{self.base_url}/v1/private/llm-provider-key",
            json={"provider": provider, "api_key": api_key, "name": f"live-model-check-{provider}"},
            timeout=30,
        )
        if response.status_code not in (200, 201, 409):
            raise RuntimeError(f"Could not add the {provider} key: {sanitize(error_text(response), self.secrets)}")

    def call(self, model: ModelToCheck) -> CheckResult:
        payload = {
            "model": model.request_model,
            "messages": [{"role": "user", "content": PROMPT}],
            "max_completion_tokens": MAX_COMPLETION_TOKENS,
            "stream": False,
        }
        started = time.monotonic()
        response = None
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
        seconds = round(time.monotonic() - started, 1)

        if response is None:
            return CheckResult(model, "fail", seconds, sanitize(note, self.secrets))
        if response.status_code != 200:
            return CheckResult(model, "fail", seconds, sanitize(error_text(response), self.secrets))
        return CheckResult(model, "pass", seconds, _answer_note(response))


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


def render_report(results: list[CheckResult], mode: str, base_ref: str, call_cap: int, cut: int) -> str:
    lines = [REPORT_MARKER, "## Live model check", ""]
    calls = [r for r in results if r.result in ("pass", "fail", "would call")]
    passed = sum(1 for r in calls if r.result == "pass")
    if mode == "smoke":
        lines.append(
            f"No model in `{LLM_MODELS_YAML.name}` is new or changed against `{base_ref}`, "
            "so this run called one model per provider to check the harness."
        )
    else:
        lines.append(f"New or changed models in `{LLM_MODELS_YAML.name}` against `{base_ref}`.")
    lines.append("")
    lines.append(
        f"**{passed} of {len(calls)} calls passed.** Each call sends one short prompt through the backend "
        f"(`max_completion_tokens: {MAX_COMPLETION_TOKENS}`). A failure is for a person to read: "
        "it can be a real gap in the model data, or a provider-side limit."
    )
    if cut:
        lines.append("")
        lines.append(f"The call cap of {call_cap} cut {cut} models; they are listed as not checked.")

    for provider in _provider_order(results):
        rows = [r for r in results if r.model.provider == provider]
        lines += ["", f"### {PROVIDER_LABELS.get(provider, provider)}", ""]
        lines += ["| Model | Why | Result | Seconds | Note |", "| --- | --- | --- | --- | --- |"]
        for r in rows:
            seconds = "" if r.seconds is None else f"{r.seconds:.1f}"
            lines.append(f"| `{r.model.request_model}` | {r.model.reason} | {r.result} | {seconds} | {r.note} |")
    lines.append("")
    return "\n".join(lines)


def _provider_order(results: list[CheckResult]) -> list[str]:
    seen = []
    for r in results:
        if r.model.provider not in seen:
            seen.append(r.model.provider)
    return seen


def plan(args: argparse.Namespace) -> tuple[list[ModelToCheck], str]:
    base = load_registry_at_ref(args.base_ref)
    if args.head_ref:
        head = load_registry_at_ref(args.head_ref)
    else:
        head = load_registry_from_text((REPO_ROOT / LLM_MODELS_YAML).read_text())
    to_check = find_models_to_check(base, head)
    if to_check:
        return to_check, "changes"
    return smoke_models(head), "smoke"


def run(args: argparse.Namespace) -> int:
    to_check, mode = plan(args)
    keys = {p: os.environ.get(env, "") for p, env in PROVIDER_KEY_ENV.items()}
    secrets = [k for k in keys.values() if k]

    results = []
    callable_models = []
    for model in to_check:
        if not keys.get(model.provider):
            results.append(CheckResult(model, "not checked", None, "no CI key"))
        elif len(callable_models) >= args.max_calls:
            results.append(CheckResult(model, "not checked", None, "over the call cap"))
        else:
            callable_models.append(model)
    cut = sum(1 for r in results if r.note == "over the call cap")

    if args.dry_run:
        for model in callable_models:
            results.append(CheckResult(model, "would call", None, ""))
    else:
        backend = Backend(args.backend_url, secrets)
        backend.check_alive()
        for provider in sorted({m.provider for m in callable_models}):
            backend.add_provider_key(provider, keys[provider])
        for model in callable_models:
            result = backend.call(model)
            print(f"  {result.result:4}  {model.provider}/{model.request_model}  {result.seconds}s  {result.note}")
            results.append(result)

    provider_rank = {provider: i for i, provider in enumerate(PROVIDER_LABELS)}
    results.sort(key=lambda r: provider_rank.get(r.model.provider, len(provider_rank)))
    report = render_report(results, mode, args.base_ref, args.max_calls, cut)
    if args.output:
        Path(args.output).write_text(report)
    else:
        print(report)
    return 0


def non_negative_int(value: str) -> int:
    number = int(value)
    if number < 0:
        raise argparse.ArgumentTypeError(f"must be 0 or more, got {number}")
    return number


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-ref", required=True, help="Git ref to compare the YAML against")
    parser.add_argument("--head-ref", help="Git ref for the new YAML (default: the working tree)")
    parser.add_argument("--backend-url", default="http://localhost:8080")
    parser.add_argument("--output", help="Write the Markdown report here (default: stdout)")
    parser.add_argument("--max-calls", type=non_negative_int, default=60)
    parser.add_argument("--dry-run", action="store_true", help="List what would be called, make no calls")
    args = parser.parse_args()
    try:
        return run(args)
    except (RuntimeError, requests.RequestException, yaml.YAMLError) as e:
        print(f"::error::Live model check could not run: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
