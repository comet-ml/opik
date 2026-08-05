# mypy: disable-error-code=no-untyped-def
"""OPIK-7521: the tracing boundary an optimization run's cost depends on.

The backend attributes optimizer-internal spend to a run by the optimization id
on the TRACE, and prices the call from the span's model/provider/usage. Both
halves live outside the SDK, so unit tests can only pin the params we hand to
LiteLLM — this asserts the record that actually reached the backend.

litellm's `mock_response` stands in for the provider, so the whole tracing stack
runs without spending anything on a model.
"""

from __future__ import annotations

import time
import uuid
from typing import Any

import opik
import pytest

from opik_optimizer.core import llm_calls

pytestmark = pytest.mark.e2e


def _has_a_deliberate_target() -> bool:
    """Is Opik pointed somewhere on purpose?

    Deliberately not a credentials check. The e2e workflow stands up a self-hosted
    Opik that needs no api_key, so gating on one skipped this test on every CI run
    since it was added, on every Python version, while the other 21 tests in
    tests/e2e ran unguarded against that same stack.

    It cannot simply be dropped either. Unconfigured, `url_override` defaults to
    OPIK_URL_CLOUD, and `OpikConfig.check_for_known_misconfigurations` returns early
    when "pytest" is in sys.modules - so a bare `pytest tests/e2e` on a laptop would
    silently queue unauthenticated requests at the public cloud and then spend the
    poll loop below failing with "no trace carried the optimization id", which
    describes neither the cause nor the fix.

    So: run when the URL was moved off the cloud default (a self-hosted stack, which
    is CI), or when a key is present (cloud, authenticated on purpose). Skip only
    when nothing was configured at all.
    """
    try:
        config = opik.config.OpikConfig()
    except Exception:
        return False
    url = (config.url_override or "").rstrip("/")
    if url and url != opik.config.OPIK_URL_CLOUD.rstrip("/"):
        return True
    return bool(config.api_key)


@pytest.mark.skipif(
    not _has_a_deliberate_target(),
    reason="no Opik target configured: set OPIK_URL_OVERRIDE to a self-hosted stack "
    "(as the e2e workflow does) or configure an api_key",
)
def test_optimizer_llm_call__lands_in_one_trace_tagged_with_the_optimization_id(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    project = f"e2e-optimizer-attribution-{uuid.uuid4().hex[:8]}"
    optimization_id = f"opt-{uuid.uuid4().hex[:12]}"
    monkeypatch.setenv("OPIK_PROJECT_NAME", project)

    llm_calls.call_model(
        model="gpt-4o-mini",
        messages=[{"role": "user", "content": "ping"}],
        optimization_id=optimization_id,
        project_name=project,
        metadata={"opik_call_type": "e2e_attribution"},
        model_parameters={"mock_response": "pong"},
    )

    client = opik.Opik(project_name=project)
    client.flush()

    traces: list[Any] = []
    for _ in range(20):
        # Ingestion is asynchronous, and until the first trace lands the project
        # itself does not exist — the search 404s rather than returning empty.
        try:
            traces = [
                t
                for t in client.search_traces(project_name=project, max_results=50)
                if optimization_id in (t.tags or [])
            ]
        except Exception:
            traces = []
        if traces:
            break
        time.sleep(3)

    assert traces, (
        f"no trace carried the optimization id {optimization_id}; without that tag "
        "the backend cannot attribute this call's cost to the run"
    )
    trace = traces[0]
    assert trace.name == "e2e_attribution"  # named after the call type
    # Exactly one: a second LLM span here is the duplicate OpikLogger regression,
    # and it would double the cost the backend computes for the run.
    assert trace.llm_span_count == 1
