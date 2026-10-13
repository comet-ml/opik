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

# The fields that change what the backend sends or where it routes a plain prompt. Others, such
# as label (display only) or supportedParameters (v0 sends no optional params), are only counted.
CALL_FIELDS = ("qualifiedName", "structuredOutput", "reasoning")

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


class UnexercisedChange(NamedTuple):
    provider: str
    model_id: str
    fields: tuple[str, ...]


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


def find_models_to_check(
    base: dict[str, list[dict]], head: dict[str, list[dict]]
) -> tuple[list[ModelToCheck], list[UnexercisedChange]]:
    to_check = []
    unexercised = []
    for provider, models in head.items():
        base_by_id = {m["id"]: m for m in base.get(provider, [])}
        for model in models:
            before = base_by_id.get(model["id"])
            if before is None:
                to_check.append(ModelToCheck(provider, model["id"], request_model_name(model), "new"))
            elif _call_fields(before) != _call_fields(model):
                to_check.append(ModelToCheck(provider, model["id"], request_model_name(model), "changed"))
            else:
                other = sorted(k for k in before.keys() | model.keys() if k != "id" and before.get(k) != model.get(k))
                if other:
                    unexercised.append(UnexercisedChange(provider, model["id"], tuple(other)))
    return to_check, unexercised


def _call_fields(model: dict) -> tuple:
    # A missing flag and false mean the same in the bundled file, which is the bottom layer.
    return (
        model.get("qualifiedName") or None,
        bool(model.get("structuredOutput")),
        bool(model.get("reasoning")),
    )


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


def render_report(
    results: list[CheckResult], unexercised: list[UnexercisedChange], mode: str, base_ref: str, call_cap: int, cut: int
) -> str:
    lines = [REPORT_MARKER, "## Live model check", "", f"**{_headline(results, mode)}**", ""]
    if mode == "smoke" and results:
        lines.append(
            "No model is new or changed in a field this check exercises, "
            "so this run called one model per provider to check the harness."
        )
        lines.append("")
    lines.append(
        f"Compared `{LLM_MODELS_YAML.name}` with `{base_ref}`. Each call sends one short prompt through the "
        f"backend (`max_completion_tokens: {MAX_COMPLETION_TOKENS}`). A failure is for a person to read: "
        "it can be a real gap in the model data, or a provider-side limit."
    )
    providers = _provider_order(results)
    if providers:
        lines.append("")
        for provider in providers:
            rows = [r for r in results if r.model.provider == provider]
            lines.append(f"- **{PROVIDER_LABELS.get(provider, provider)}:** {_counts(rows)}")
    if cut:
        lines += ["", f"The call cap of {call_cap} cut {cut} models; they are listed as not checked."]

    failures = [r for r in results if r.result == "fail"]
    if failures:
        lines += [
            "",
            "### Failures",
            "",
            "| Provider | Model | Why | Seconds | Note |",
            "| --- | --- | --- | --- | --- |",
        ]
        for r in failures:
            label = PROVIDER_LABELS.get(r.model.provider, r.model.provider)
            lines.append(f"| {label} | `{r.model.request_model}` | {r.model.reason} | {r.seconds:.1f} | {r.note} |")

    for provider in providers:
        rows = sorted((r for r in results if r.model.provider == provider), key=lambda r: RESULT_ORDER[r.result])
        shown = rows[:MAX_TABLE_ROWS]
        lines += _details(
            f"{PROVIDER_LABELS.get(provider, provider)}: {_models(len(rows))}",
            ["| Model | Why | Result | Seconds | Note |", "| --- | --- | --- | --- | --- |"]
            + [
                f"| `{r.model.request_model}` | {r.model.reason} | {r.result} | {_seconds(r.seconds)} | {r.note} |"
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
            fields = ", ".join(f"`{f}` ({n})" for f, n in sorted(field_counts.items()))
            table.append(f"| {PROVIDER_LABELS.get(provider, provider)} | {len(changes)} | {fields} |")
        lines += _details(
            f"{_models(len(unexercised))} changed only in fields this check doesn't exercise",
            [f"This check exercises {', '.join(f'`{f}`' for f in CALL_FIELDS)}.", ""] + table,
        )
    lines.append("")
    return "\n".join(lines)


RESULT_ORDER = {"fail": 0, "pass": 1, "would call": 2, "not checked": 3}
MAX_TABLE_ROWS = 100


def _headline(results: list[CheckResult], mode: str) -> str:
    calls = [r for r in results if r.result in ("pass", "fail")]
    planned = [r for r in results if r.result == "would call"]
    not_checked = [r for r in results if r.result == "not checked"]
    if calls:
        passed = sum(1 for r in calls if r.result == "pass")
        return f"{passed} of {len(calls)} calls passed"
    if planned:
        return f"Dry run: {len(planned)} calls planned"
    if mode == "smoke" or not not_checked:
        return "No new or changed models"
    n = len(not_checked)
    models = "1 new or changed model is" if n == 1 else f"{n} new or changed models are"
    if all(r.note == "no CI key" for r in not_checked):
        return f"Nothing called: {models} on providers without a CI key"
    return f"Nothing called: {models} not checked"


def _counts(rows: list[CheckResult]) -> str:
    parts = []
    for result, word in (("pass", "passed"), ("fail", "failed"), ("would call", "to call")):
        n = sum(1 for r in rows if r.result == result)
        if n:
            parts.append(f"{n} {word}")
    reasons = {}
    for r in rows:
        if r.result == "not checked":
            reasons[r.note] = reasons.get(r.note, 0) + 1
    parts += [f"{n} not checked ({note})" for note, n in reasons.items()]
    return ", ".join(parts)


def _details(summary: str, body: list[str]) -> list[str]:
    return ["", "<details>", f"<summary>{summary}</summary>", ""] + body + ["", "</details>"]


def _models(n: int) -> str:
    return f"{n} model" if n == 1 else f"{n} models"


def _seconds(seconds: float | None) -> str:
    return "" if seconds is None else f"{seconds:.1f}"


def _provider_order(items: list) -> list[str]:
    seen = []
    for item in items:
        provider = item.model.provider if isinstance(item, CheckResult) else item.provider
        if provider not in seen:
            seen.append(provider)
    rank = {provider: i for i, provider in enumerate(PROVIDER_LABELS)}
    return sorted(seen, key=lambda p: rank.get(p, len(rank)))


def plan(args: argparse.Namespace) -> tuple[list[ModelToCheck], list[UnexercisedChange], str]:
    base = load_registry_at_ref(args.base_ref)
    if args.head_ref:
        head = load_registry_at_ref(args.head_ref)
    else:
        head = load_registry_from_text((REPO_ROOT / LLM_MODELS_YAML).read_text())
    to_check, unexercised = find_models_to_check(base, head)
    if to_check:
        return to_check, unexercised, "changes"
    return smoke_models(head), unexercised, "smoke"


def run(args: argparse.Namespace) -> int:
    to_check, unexercised, mode = plan(args)
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

    report = render_report(results, unexercised, mode, args.base_ref, args.max_calls, cut)
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
