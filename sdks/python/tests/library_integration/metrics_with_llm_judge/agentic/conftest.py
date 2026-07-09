"""Fixtures for the agentic LLM-judge integration tests.

These tests exercise the agentic LLM judge against real provider
models with deterministically-constructed `TraceToolContext` inputs.
They sit between unit tests (mocked LLM, stubbed `run_agentic_judge`)
and the e2e suite (full `opik.run_tests` pipeline): inputs are pinned,
the judge is the only moving part, and verdicts are checked field by
field.

The parent `metrics_with_llm_judge/conftest.py` supplies the shared
autouse fixtures — `ensure_litellm_monitoring_disabled` and
`_isolate_from_real_backend` — so this conftest adds only what's
specific to the agentic suite: a parametrized judge-model fixture
that covers OpenAI, Anthropic, and Gemini (via Vertex AI), plus
matching skip-on-missing-credentials wrappers.
"""

import os
from typing import Any, Iterator, List, Tuple

import pytest

from opik.evaluation.models import models_factory
from tests import llm_constants


# Each entry: `(litellm_model_name, [names of fixtures to materialize])`.
#
# Model picks across providers:
# - OpenAI `gpt-4o-mini` (LiteLLM-routed by the factory): known to
#   engage the tool loop. The SDK default `gpt-5-nano` is the
#   canonical "doesn't call tools" failure mode (see
#   `SupportedJudgeProvider.java`), so don't swap it in here without
#   re-validating tool-use tests by hand.
# - Anthropic `claude-sonnet-4-6` (native `AnthropicChatModel`): the
#   Claude tier we run the agentic judge against. Haiku is cheaper but
#   on the agentic path (tools in the request, so `response_format` is
#   best-effort) it narrates in prose and wraps the verdict in a
#   ```json fence rather than emitting the bare object the parser
#   expects; Sonnet follows the structured-output contract reliably.
# - Anthropic `claude-sonnet-4-6` *via LiteLLM* (`litellm_anthropic`):
#   identical model string as the native row, but routed through the
#   LiteLLM adapter by forcing `_should_use_anthropic_native=False`.
#   This is the only parametrize entry that exercises Anthropic-by-
#   LiteLLM; without it, the LiteLLM path for tool-use never gets
#   touched by this suite (OpenAI uses LiteLLM too, but its provider
#   quirks differ from Anthropic's).
#
# The fixture-name lists materialize via `request.getfixturevalue` —
# each wrapper either yields (creds present) or calls `pytest.skip`,
# so a developer running locally without (say) Anthropic credentials
# gets a clean skip rather than a hard error.
_JUDGE_MODEL_PARAMS: List[Tuple[str, List[str]]] = [
    (llm_constants.OPENAI_GPT_4O_MINI, ["_skip_unless_openai_configured"]),
    (
        f"{llm_constants.ANTHROPIC_CLAUDE_SONNET}",
        ["_skip_unless_anthropic_configured"],
    ),
    (
        f"{llm_constants.LITELLM_ANTHROPIC_CLAUDE_SONNET}",
        ["_skip_unless_anthropic_configured", "_force_litellm_path"],
    ),
]


JUDGE_MODEL_PARAMS = [
    pytest.param(_JUDGE_MODEL_PARAMS[0], id="openai"),
    pytest.param(_JUDGE_MODEL_PARAMS[1], id="anthropic"),
    pytest.param(_JUDGE_MODEL_PARAMS[2], id="litellm_anthropic"),
]


def _skip_if_raises(request: Any, fixture_name: str, provider: str) -> None:
    """Materialize a provider's session-scoped `ensure_*_configured`
    fixture, translating its hard `raise` into a `pytest.skip`.

    The shared fixtures in `tests/conftest.py` are designed for CI
    lanes where credentials are guaranteed and a missing env var
    should fail loudly. For an integration test that's meant to be
    runnable locally, we want "skip" instead. This helper bridges
    the two stances without duplicating the credential checks.
    """
    try:
        request.getfixturevalue(fixture_name)
    except Exception as exc:  # noqa: BLE001 — propagate as skip
        pytest.skip(f"{provider} not configured: {exc}")


@pytest.fixture
def _skip_unless_openai_configured(request: Any) -> Iterator[None]:
    _skip_if_raises(request, "ensure_openai_configured", "OpenAI")
    # Surface the org id under the variable litellm forwards to the
    # OpenAI SDK. Setting both flavors keeps either client happy.
    org_id = os.environ.get("OPENAI_ORG_ID")
    previous_org = os.environ.get("OPENAI_ORGANIZATION")
    if org_id and previous_org is None:
        os.environ["OPENAI_ORGANIZATION"] = org_id
        try:
            yield
        finally:
            os.environ.pop("OPENAI_ORGANIZATION", None)
    else:
        yield


@pytest.fixture
def _skip_unless_anthropic_configured(request: Any) -> None:
    _skip_if_raises(request, "ensure_anthropic_configured", "Anthropic")


@pytest.fixture
def _force_litellm_path(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    """Route Anthropic-prefixed model names through `LiteLLMChatModel`
    instead of the native `AnthropicChatModel`.

    The factory at `models_factory._should_use_anthropic_native`
    answers True for anything starting with `anthropic/` or `claude`
    whenever the `anthropic` SDK is importable — which it is in CI.
    Patching that predicate to False is the only way to force the
    LiteLLM path for the same model string without inventing a
    second model name or shipping a separate test file. The cached
    factory instance must also be cleared so a previously-stored
    `AnthropicChatModel` for the same cache key doesn't shadow the
    LiteLLM build.
    """
    monkeypatch.setattr(
        models_factory, "_should_use_anthropic_native", lambda _name: False
    )
    models_factory._MODEL_CACHE.clear()
    try:
        yield
    finally:
        # Clear again on the way out so subsequent tests start from a
        # clean cache and the native path is rebuilt freshly.
        models_factory._MODEL_CACHE.clear()


@pytest.fixture(params=JUDGE_MODEL_PARAMS)
def judge_model_name(request: Any) -> str:
    """Parametrized model name fed to `LLMJudge(model=...)` and
    `models_factory.get(...)`.

    Each parametrize id (`openai` / `anthropic` / `vertex_ai`) maps
    to a model string + a credential fixture; the fixture short-
    circuits via `pytest.skip` when the corresponding provider isn't
    configured. Tests don't need to know which provider they're
    running against — they just consume `judge_model_name`.
    """
    model_name, fixture_names = request.param
    for fixture_name in fixture_names:
        request.getfixturevalue(fixture_name)
    return model_name
