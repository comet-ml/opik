"""E2E test infrastructure for opik-python-backend.

These tests talk to a real, running Opik backend (for dataset access and trace
storage) and run a real optimization, so they live in a separate directory from
the unit suite (`tests/`) and are gated behind the `e2e` marker.

Following the same principle as the rest of Opik (and the whole point of the
Optimization Studio gateway work): **no provider API key is ever passed to the
optimizer/LiteLLM directly**. The Anthropic key comes from a CI secret, is
stored in the backend workspace, and LLM calls are routed through the backend's
`/v1/private` gateway, which resolves the key server-side.
"""

import os
import uuid

import httpx
import pytest

import opik

# Anthropic is the provider these tests use. The "openai/" prefix routes the
# call through LiteLLM's OpenAI-compatible handler to the gateway, which strips
# it and dispatches to Anthropic by the remaining model name.
_PROVIDER = "anthropic"
_GATEWAY_MODEL = "openai/claude-haiku-4-5-20251001"
_EXPECTED_SPAN_MODEL = "claude-haiku"


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


def _anthropic_configured(base: str, headers: dict[str, str]) -> bool:
    listing = httpx.get(
        f"{base}/v1/private/llm-provider-key", headers=headers, timeout=30
    )
    if listing.status_code != 200:
        return False
    return any(
        item.get("provider") == _PROVIDER for item in listing.json().get("content", [])
    )


def _ensure_anthropic_key(base: str, headers: dict[str, str]) -> bool:
    """Store the Anthropic key (from the ANTHROPIC_API_KEY secret) in the backend
    workspace so the optimization resolves it server-side via the gateway. The key
    is never handed to the optimizer. Returns False if no key is available (skip).
    """
    if _anthropic_configured(base, headers):
        return True
    secret = os.getenv("ANTHROPIC_API_KEY")
    if not secret:
        return False
    httpx.post(
        f"{base}/v1/private/llm-provider-key",
        headers=headers,
        json={"provider": _PROVIDER, "api_key": secret},
        timeout=30,
    ).raise_for_status()
    return True


@pytest.fixture(scope="session")
def opik_client() -> opik.Opik:
    if not _backend_base():
        pytest.skip("OPIK_URL_OVERRIDE not set; e2e requires a running Opik backend")
    client = opik.Opik()
    yield client
    client.flush()


@pytest.fixture()
def studio_gateway(opik_client, monkeypatch) -> dict:
    """Store the Anthropic key in the backend and route the optimizer's LLM calls
    through the `/v1/private` gateway using that workspace-stored key — exactly how
    the Studio runs in production, with no provider key reaching LiteLLM. Returns
    the gateway model + expected span model.

    Uses ``monkeypatch`` so the env vars and module attribute are restored after
    the test and the suite stays order-independent.
    """
    base = _backend_base()
    headers = _workspace_headers()
    workspace = os.getenv("OPIK_WORKSPACE", "default")

    if not _ensure_anthropic_key(base, headers):
        pytest.skip(
            "ANTHROPIC_API_KEY not set and no Anthropic provider configured in the workspace"
        )

    # Point LiteLLM at the gateway and inject the Comet-Workspace header, the
    # same wiring optimizer.py/optimizer_runner.py set up for the subprocess.
    # OPENAI_API_KEY is only a non-empty placeholder — the gateway authenticates
    # by workspace, not by this value.
    import opik_backend.jobs.optimizer_runner as optimizer_runner

    gateway_base = f"{base}/v1/private"
    # LiteLLM reads OPENAI_API_BASE from the env to pick the endpoint; the module
    # attribute only gates the Comet-Workspace header wrapper. Set both.
    monkeypatch.setenv("OPENAI_API_BASE", gateway_base)
    monkeypatch.setenv("OPENAI_API_KEY", "opik-local")
    monkeypatch.setattr(optimizer_runner, "OPENAI_API_BASE", gateway_base)
    optimizer_runner.route_litellm_calls_through_gateway(workspace)

    return {
        "workspace": workspace,
        "model": _GATEWAY_MODEL,
        "expected_substring": _EXPECTED_SPAN_MODEL,
    }


@pytest.fixture()
def project_name() -> str:
    """Unique per test so trace assertions never see another run's spans."""
    return f"optstudio-e2e-{uuid.uuid4().hex[:8]}"


@pytest.fixture()
def seeded_dataset(opik_client: opik.Opik):
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
