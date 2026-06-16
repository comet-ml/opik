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

import os
import uuid

import httpx
import pytest

import opik

_PROVIDER = "anthropic"


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


@pytest.fixture(scope="session")
def opik_client() -> opik.Opik:
    if not _backend_base():
        pytest.skip("OPIK_URL_OVERRIDE not set; e2e requires a running Opik backend")
    client = opik.Opik()
    yield client
    client.flush()


@pytest.fixture()
def anthropic_workspace_key() -> None:
    """Take the Anthropic key from the ANTHROPIC_API_KEY secret and store it in
    the backend workspace, so the optimization resolves it server-side via the
    gateway. The key is never handed to the optimizer. Skips if no key is
    available (and none is already configured)."""
    base = _backend_base()
    headers = _workspace_headers()
    if _anthropic_configured(base, headers):
        return
    secret = os.getenv("ANTHROPIC_API_KEY")
    if not secret:
        pytest.skip(
            "ANTHROPIC_API_KEY not set and no Anthropic provider configured in the workspace"
        )
    httpx.post(
        f"{base}/v1/private/llm-provider-key",
        headers=headers,
        json={"provider": _PROVIDER, "api_key": secret},
        timeout=30,
    ).raise_for_status()


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
