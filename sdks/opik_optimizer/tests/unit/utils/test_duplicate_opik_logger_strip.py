# mypy: disable-error-code=no-untyped-def
"""OPIK-7521: drop Opik's LiteLLM callback only where it duplicates our span.

`call_model` wraps the call with `track_completion`, while `opik_monitor` injects
an `OpikLogger` whose "already decorated" guard inspects the module-level
`litellm.completion` and so cannot see our decorated copy. With a span already
open both log the same call, and since the backend prices spans, the duplicate
doubles the reported cost. With no span open the OpikLogger is instead the only
thing stamping the optimization id onto the trace, so removing it there would
delete the attribution the run's cost aggregation depends on.
"""

from typing import Any

import pytest
from litellm.integrations.opik.opik import OpikLogger

from opik_optimizer.core import llm_calls


@pytest.fixture
def span_open(monkeypatch) -> None:
    monkeypatch.setattr(
        llm_calls.opik_context, "get_current_span_data", lambda: object()
    )


@pytest.fixture
def no_span_open(monkeypatch) -> None:
    monkeypatch.setattr(llm_calls.opik_context, "get_current_span_data", lambda: None)


def _params_with_logger() -> dict[str, Any]:
    logger_instance = OpikLogger()
    return {
        "success_callback": [logger_instance, "other"],
        "failure_callback": [logger_instance],
        "model": "gpt-4o-mini",
    }


def test_span_open__opik_logger_is_dropped_from_both_callback_lists(span_open) -> None:
    stripped = llm_calls._strip_duplicate_opik_logger(_params_with_logger())

    assert stripped["success_callback"] == ["other"]
    assert stripped["failure_callback"] == []
    assert stripped["model"] == "gpt-4o-mini"


def test_span_open__original_params_are_not_mutated(span_open) -> None:
    params = _params_with_logger()
    llm_calls._strip_duplicate_opik_logger(params)

    assert any(isinstance(cb, OpikLogger) for cb in params["success_callback"])


def test_no_span_open__callbacks_are_left_alone(no_span_open) -> None:
    """The OpikLogger is the only thing tagging the trace with the optimization
    id on these paths; dropping it would hide that spend from the run's cost."""
    params = _params_with_logger()
    stripped = llm_calls._strip_duplicate_opik_logger(params)

    assert stripped is params


def test_span_open__params_without_callbacks_pass_through(span_open) -> None:
    params: dict[str, Any] = {"model": "gpt-4o-mini", "success_callback": "not-a-list"}
    assert llm_calls._strip_duplicate_opik_logger(params) is params
