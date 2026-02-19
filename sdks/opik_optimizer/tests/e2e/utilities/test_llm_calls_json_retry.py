"""E2E test for JSON retry path in llm_calls."""

from __future__ import annotations

import os
from types import SimpleNamespace
from typing import Any

import pytest
import litellm
from pydantic import BaseModel

from opik_optimizer.core import llm_calls as _llm_calls
from tests.e2e.optimizers.utils import user_message


class JsonRetryResponse(BaseModel):
    """Response model for JSON retry integration test."""

    value: int


def _mock_bad_response() -> Any:
    message = SimpleNamespace(content='{"value": 1', parsed=None)
    choice = SimpleNamespace(message=message, finish_reason="stop")
    return SimpleNamespace(choices=[choice], model="openai/gpt-5-nano")


@pytest.mark.e2e
def test_call_model_retries_with_json_instructions_live(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Simulate a bad first response then ensure retry returns valid JSON."""
    if not os.environ.get("OPENAI_API_KEY"):
        pytest.skip("OPENAI_API_KEY not set - skipping live test")

    model = os.environ.get("OPIK_TEST_MODEL", "openai/gpt-5-nano")
    original_completion = litellm.completion
    call_count = {"n": 0}

    def flaky_completion(**kwargs: Any) -> Any:
        if call_count["n"] == 0:
            call_count["n"] += 1
            return _mock_bad_response()
        return original_completion(**kwargs)

    monkeypatch.setattr(litellm, "completion", flaky_completion)

    result = _llm_calls.call_model(
        messages=[user_message("Return JSON with key value=2")],
        model=model,
        response_model=JsonRetryResponse,
    )

    assert isinstance(result, JsonRetryResponse)
    assert isinstance(result.value, int)
    assert result.value == 2
