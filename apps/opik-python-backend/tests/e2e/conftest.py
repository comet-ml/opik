"""E2E test infrastructure for opik-python-backend.

These tests talk to a real, running Opik backend (for dataset access and trace
storage) and run a real optimization, so they live in a separate directory from
the unit suite (`tests/`) and are gated behind the `e2e` marker.

Following the same principle as the rest of Opik (and the whole point of the
Optimization Studio gateway work): **no provider API key is passed to the
optimizer.** The Anthropic key comes from a CI secret, is stored in the backend
workspace, and the studio job processor routes LLM calls through the backend's
`/v1/private` gateway, which resolves the key server-side.
"""

import functools
import os
import re
import uuid
from collections.abc import Iterator
from typing import Any, Callable

import httpx
import pytest

import opik

from llm_constants import ANTHROPIC_CLAUDE_HAIKU, OPENAI_GPT_MINI
from opik_backend.jobs.optimizer import process_optimizer_job

_PROVIDER = "anthropic"


_PROVIDER_SECRET_ENV = {"anthropic": "ANTHROPIC_API_KEY", "openai": "OPENAI_API_KEY"}


def _provider_for_model(model: str | None) -> str:
    """Provider required by the e2e model (default model is Anthropic).

    Handles both bare ids ("gpt-5-nano") and gateway-prefixed ones
    ("openai/gpt-4o") — the Studio routes everything through the gateway with an
    ``openai/`` prefix, so a prefix-blind check would ask the backend for an
    Anthropic key and get a BadRequestException for the missing one.
    """
    if not model:
        return _PROVIDER
    provider, _, remainder = model.partition("/")
    if remainder and provider in _PROVIDER_SECRET_ENV:
        return provider
    return "openai" if model.startswith("gpt") else _PROVIDER


_ANTHROPIC_PROBE_MODEL = "claude-haiku-4-5"
_PROBE_TIMEOUT_S = 15
# Billing/quota exhaustion, which Anthropic reports as a 400, not a 401.
_BALANCE_HINT = re.compile(
    r"credit balance|too low|billing|quota|insufficient", re.IGNORECASE
)


def _is_credential_rejection(status: int, message: str) -> bool:
    """Whether a non-OK probe response condemns the credential itself.

    401/403 can only mean a bad credential. 400 is narrower: Anthropic returns
    it both for an exhausted balance and for a request this probe got wrong (an
    unknown model, schema drift), and treating every 400 as a dead key would let
    retiring the probe model silently move the whole suite onto OpenAI.
    """
    if status in (401, 403):
        return True
    if status == 400:
        return bool(_BALANCE_HINT.search(message))
    return False


def _anthropic_rejection_reason(api_key: str) -> str | None:
    """Reason Anthropic refuses this credential, or None if it looks usable.

    Presence and usability are different claims: the e2e key was live but
    disabled after an unexplained spend spike, which surfaced only as an opaque
    gateway AuthenticationError several minutes into an optimization. Probing up
    front turns that into a provider choice made before any test runs.

    Deliberately narrow, mirroring tests_end_to_end/e2e/core/llm-key-preflight.ts:
    only an authoritative rejection counts, so a timeout or 5xx keeps the key
    rather than letting a flaky network silently switch providers.
    """
    try:
        response = httpx.post(
            "https://api.anthropic.com/v1/messages",
            headers={
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            json={
                "model": _ANTHROPIC_PROBE_MODEL,
                "max_tokens": 1,
                "messages": [{"role": "user", "content": "hi"}],
            },
            timeout=_PROBE_TIMEOUT_S,
        )
    except httpx.HTTPError:
        return None

    if response.is_success:
        return None

    detail = f"HTTP {response.status_code}"
    try:
        message = response.json().get("error", {}).get("message")
        if message:
            detail = message
    except ValueError:
        pass

    return detail if _is_credential_rejection(response.status_code, detail) else None


@functools.lru_cache(maxsize=1)
def resolve_e2e_model() -> str:
    """The task model these tests should run, given the keys actually available.

    An explicit ``OPTSTUDIO_E2E_MODEL`` always wins — pinning a model must not be
    second-guessed. Otherwise prefer Anthropic, and fall back to OpenAI when the
    Anthropic key is absent or the provider rejects it, so a dead Anthropic
    credential costs the suite its provider rather than its coverage.

    Cached: the probe is a live network call and every test requests the model.
    """
    pinned = os.getenv("OPTSTUDIO_E2E_MODEL")
    if pinned:
        return pinned

    anthropic_key = os.getenv("ANTHROPIC_API_KEY")
    rejection = _anthropic_rejection_reason(anthropic_key) if anthropic_key else "not set"
    if not rejection:
        return ANTHROPIC_CLAUDE_HAIKU

    # Anthropic unusable. Fall back only if OpenAI can actually run; otherwise
    # stay on Anthropic so workspace_provider_key skips with a clear reason
    # rather than failing mid-optimization.
    if not os.getenv("OPENAI_API_KEY"):
        return ANTHROPIC_CLAUDE_HAIKU

    print(
        f"[e2e-preflight] Anthropic unusable ({rejection}); "
        f"running against {OPENAI_GPT_MINI} instead."
    )
    return OPENAI_GPT_MINI


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line(
        "markers",
        "e2e: end-to-end test requiring a running Opik backend with a workspace provider key",
    )


def _backend_base() -> str | None:
    base = os.getenv("OPIK_URL_OVERRIDE") or os.getenv("OPIK_URL")
    return base.rstrip("/") if base else None


def _workspace_headers() -> dict[str, str]:
    headers = {"Comet-Workspace": os.getenv("OPIK_WORKSPACE", "default")}
    api_key = os.getenv("OPIK_API_KEY")
    if api_key:
        headers["Authorization"] = api_key
    return headers


def _provider_configured(base: str, headers: dict[str, str], provider: str) -> bool:
    listing = httpx.get(
        f"{base}/v1/private/llm-provider-key", headers=headers, timeout=30
    )
    if listing.status_code != 200:
        return False
    return any(
        item.get("provider") == provider for item in listing.json().get("content", [])
    )


@pytest.fixture(scope="session")
def opik_client() -> Iterator[opik.Opik]:
    if not _backend_base():
        pytest.skip("OPIK_URL_OVERRIDE not set; e2e requires a running Opik backend")
    client = opik.Opik()
    yield client
    client.flush()


@pytest.fixture()
def workspace_provider_key() -> None:
    """Ensure the provider required by the e2e model has a key in the backend
    workspace, so the optimization resolves it server-side via the gateway —
    the key is never handed to the optimizer. For the default Anthropic model
    the key comes from the ANTHROPIC_API_KEY secret (CI); an OpenAI model takes
    OPENAI_API_KEY. For a local stack whose workspace already has the required
    provider configured this is a no-op. Skips when the provider is neither
    configured nor obtainable."""
    base = _backend_base()
    if not base:
        pytest.skip("OPIK_URL_OVERRIDE not set; e2e requires a running Opik backend")
    provider = _provider_for_model(resolve_e2e_model())
    headers = _workspace_headers()
    secret_env = _PROVIDER_SECRET_ENV[provider]
    secret = os.getenv(secret_env)

    # Staying on Anthropic despite a rejected key means no fallback was
    # available (see resolve_e2e_model) — skip with the reason rather than
    # storing a dead key and failing several minutes into the optimization.
    if provider == "anthropic" and secret:
        rejection = _anthropic_rejection_reason(secret)
        if rejection:
            pytest.skip(
                f"{secret_env} is set but Anthropic rejects it ({rejection}) "
                "and OPENAI_API_KEY is not set to fall back to"
            )

    if _provider_configured(base, headers, provider):
        return
    if not secret:
        pytest.skip(
            f"no {provider} provider key configured in the workspace and "
            f"{secret_env} is not set"
        )
    httpx.post(
        f"{base}/v1/private/llm-provider-key",
        headers=headers,
        json={"provider": provider, "api_key": secret},
        timeout=30,
    ).raise_for_status()


@pytest.fixture()
def project_name(opik_client: opik.Opik) -> Iterator[str]:
    """Unique per test so trace assertions never see another run's spans.

    The optimization creates the project lazily (by logging traces to it), so we
    just hand out the name and delete the project on teardown (best-effort —
    tolerates the never-created / already-deleted case).
    """
    name = f"optstudio-e2e-{uuid.uuid4().hex[:8]}"
    yield name
    try:
        project_id = opik_client.rest_client.projects.retrieve_project(name=name).id
        opik_client.rest_client.projects.delete_project_by_id(project_id)
    except Exception:
        pass


@pytest.fixture()
def seeded_sentiment_classification_dataset(
    opik_client: opik.Opik,
) -> Iterator[opik.Dataset]:
    """A small sentiment-classification dataset the optimizer can iterate on.

    Items expose `text` (referenced by the prompt as `{{text}}`) and `label`
    (the `equals` metric reference key).
    """
    name = f"optstudio-e2e-ds-{uuid.uuid4().hex[:8]}"
    items = [
        {"text": "An absolute masterpiece — I was moved to tears.", "label": "positive"},
        {"text": "Painfully boring; two hours I will never get back.", "label": "negative"},
        {"text": "Gorgeously shot and genuinely thrilling throughout.", "label": "positive"},
        {"text": "Wooden dialogue and a plot full of holes.", "label": "negative"},
    ]
    dataset = opik_client.get_or_create_dataset(name=name)
    dataset.insert(items)
    yield dataset
    try:
        opik_client.delete_dataset(name=name)
    except Exception:
        pass


@pytest.fixture()
def run_studio_optimization(
    opik_client: opik.Opik,
) -> Iterator[Callable[[str, str, dict[str, Any]], dict[str, Any]]]:
    """Run a studio optimization through the **real entrypoint**.

    Pre-creates the optimization record (as the Java backend would), then calls
    the job handler the RQ worker calls, which sets up the gateway env and runs
    ``optimizer_runner.py`` as an isolated subprocess. Returns the subprocess
    result dict. Optimization records created here are deleted on teardown.

    ``last_optimization_id`` is stamped on the returned callable (set right
    after the record is created, before the subprocess runs) so a caller can
    still fetch the persisted optimization — e.g. its ``status``/``error_info``
    — even when ``process_optimizer_job`` raises on a failed run.
    """
    created_optimization_ids: list[str] = []
    workspace = os.getenv("OPIK_WORKSPACE", "default")

    def _run(
        project_name: str, dataset_name: str, studio_config: dict[str, Any]
    ) -> dict[str, Any]:
        optimization = opik_client.create_optimization(
            dataset_name=dataset_name,
            objective_name=studio_config["evaluation"]["metrics"][0]["type"],
            project_name=project_name,
        )
        created_optimization_ids.append(optimization.id)
        _run.last_optimization_id = optimization.id
        job_message = {
            "optimization_id": optimization.id,
            "workspace_id": workspace,
            "workspace_name": workspace,
            "config": studio_config,
            "project_name": project_name,
        }
        # Cloud backends authenticate the gateway and status updates with the
        # workspace API key ("optional-api-key-for-cloud" in the job contract);
        # local CI stacks run unauthenticated, so None keeps today's behaviour.
        api_key = os.getenv("OPIK_API_KEY")
        if api_key:
            job_message["opik_api_key"] = api_key
        return process_optimizer_job(job_message)

    _run.last_optimization_id = None
    yield _run
    if created_optimization_ids:
        try:
            opik_client.delete_optimizations(created_optimization_ids)
        except Exception:
            pass
