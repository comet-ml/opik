"""E2E test infrastructure for opik-python-backend.

These tests talk to a real, running Opik backend (for dataset access and trace
storage) and run a real optimization, so they live in a separate directory from
the unit suite (`tests/`) and are gated behind the `e2e` marker.

Following the same principle as the rest of Opik (and the whole point of the
Optimization Studio gateway work): **no provider API key is ever passed to the
optimizer/LiteLLM directly**. The provider key lives in the workspace and LLM
calls are routed through the backend's `/v1/private` gateway, which resolves the
key server-side. The fixtures here set that routing up; a workspace provider key
must already exist (or be storable from a secret) for the optimization to run.
"""

import os
import uuid

import httpx
import pytest

import opik

# Workspace provider -> (gateway model string, substring expected on the
# produced spans). The "openai/" prefix routes the call through LiteLLM's
# OpenAI-compatible handler to the gateway, which strips it and dispatches to
# the real provider by the remaining model name.
_PROVIDER_MODELS = {
    "anthropic": ("openai/claude-haiku-4-5-20251001", "claude-haiku"),
    "openai": ("openai/gpt-4o-mini", "gpt-4o-mini"),
}


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


def _resolve_workspace_provider(base: str, headers: dict[str, str]) -> str | None:
    """Return a provider configured in the workspace, storing one from a secret
    if none is present yet. None means the test should skip."""
    listing = httpx.get(
        f"{base}/v1/private/llm-provider-key", headers=headers, timeout=30
    )
    configured = (
        {item.get("provider") for item in listing.json().get("content", [])}
        if listing.status_code == 200
        else set()
    )
    for provider in _PROVIDER_MODELS:
        if provider in configured:
            return provider

    # None stored yet — populate from a secret if CI provides one. The key is
    # stored in the workspace (not handed to the optimizer); calls still go
    # through the gateway.
    for provider, env_var in (("anthropic", "ANTHROPIC_API_KEY"), ("openai", "OPENAI_API_KEY")):
        secret = os.getenv(env_var)
        if secret:
            httpx.post(
                f"{base}/v1/private/llm-provider-key",
                headers=headers,
                json={"provider": provider, "api_key": secret},
                timeout=30,
            ).raise_for_status()
            return provider
    return None


@pytest.fixture(scope="session")
def opik_client() -> opik.Opik:
    if not _backend_base():
        pytest.skip("OPIK_URL_OVERRIDE not set; e2e requires a running Opik backend")
    client = opik.Opik()
    yield client
    client.flush()


@pytest.fixture()
def studio_gateway(opik_client, monkeypatch) -> dict:
    """Route the optimizer's LLM calls through the backend gateway using the
    workspace-stored key — exactly how the Studio runs in production, with no
    provider key reaching LiteLLM. Returns the gateway model + expected span model.

    Uses ``monkeypatch`` so the env vars and module attribute are restored after
    the test and the suite stays order-independent.
    """
    base = _backend_base()
    headers = _workspace_headers()
    workspace = os.getenv("OPIK_WORKSPACE", "default")

    provider = _resolve_workspace_provider(base, headers)
    if provider is None:
        pytest.skip(
            "No workspace provider key configured and no provider secret to store one"
        )
    model, expected_substring = _PROVIDER_MODELS[provider]

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

    return {"workspace": workspace, "model": model, "expected_substring": expected_substring}


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
