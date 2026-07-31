# mypy: disable-error-code=no-untyped-def
"""
OPIK-7521: GEPA's reflection LLM must go through the instrumented call path.

Passing `reflection_lm` to gepa as a plain model string makes gepa build its own
bare litellm client — no Opik span is created and the spend is missing from every
cost report, even though the call is billed to the user's provider key. These
tests pin the contract: gepa receives a callable that routes through
`core.llm_calls.call_model` (counter increment, cost accumulation into the final
OptimizationResult, and an optimization-tagged trace).

`track_completion` is patched only to stand in for the provider call, the same way
the rest of the optimizer unit suite isolates LiteLLM (see
`tests/unit/utils/test_llm_calls_call_model.py`). The assertions are on observable
outcomes — the text handed back to gepa, the request built for the provider, the
trace tags, and the counters/costs on the result — not on the patch itself.
"""

from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from opik import exceptions as opik_exceptions

from opik_optimizer import GepaOptimizer
from opik_optimizer.core.exceptions import EmptyLLMResponseError

from tests.unit.fixtures.builders import make_mock_response


def _make_mock_gepa_result(**overrides: Any) -> MagicMock:
    mock_gepa_result = MagicMock()
    mock_gepa_result.history = []
    mock_gepa_result.pareto_front = []
    mock_gepa_result.total_metric_calls = 1
    for key, value in overrides.items():
        setattr(mock_gepa_result, key, value)
    return mock_gepa_result


def _make_reflection_response(
    # None models a content-filtered / tool-call-only completion.
    content: str | None = "new instruction",
    *,
    cost: float | None = None,
    usage: dict[str, int] | None = None,
) -> MagicMock:
    response = make_mock_response(content)
    response.cost = cost
    if usage is None:
        response.usage = None
    else:
        response.usage = SimpleNamespace(
            prompt_tokens=usage.get("prompt_tokens", 0),
            completion_tokens=usage.get("completion_tokens", 0),
            total_tokens=usage.get("total_tokens", 0),
        )
    return response


def _run_optimize(
    monkeypatch,
    mock_optimization_context,
    simple_chat_prompt,
    mock_dataset,
    sample_dataset_items,
    sample_metric,
    *,
    fake_optimize=None,
) -> tuple[Any, dict[str, Any], GepaOptimizer]:
    """Run optimize_prompt with gepa.optimize mocked; return (result, kwargs, optimizer)."""
    mock_optimization_context()

    optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
    dataset = mock_dataset(
        sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
    )
    monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

    captured: dict[str, Any] = {}

    def default_fake_optimize(**kwargs: Any) -> MagicMock:
        captured.update(kwargs)
        return _make_mock_gepa_result()

    def capturing_fake_optimize(**kwargs: Any) -> MagicMock:
        captured.update(kwargs)
        return fake_optimize(**kwargs)

    monkeypatch.setattr(
        "gepa.optimize",
        capturing_fake_optimize if fake_optimize is not None else default_fake_optimize,
    )

    result = optimizer.optimize_prompt(
        prompt=simple_chat_prompt,
        dataset=dataset,
        metric=sample_metric,
        # max_trials must exceed reflection_minibatch_size, or GEPA skips
        # reflection entirely and the fixture would not represent a real run.
        max_trials=6,
        n_samples=2,
        reflection_minibatch_size=1,
    )
    return result, captured, optimizer


class TestGepaReflectionLmInstrumentation:
    def test_reflection_lm__handed_to_gepa_instead_of_a_model_string__is_a_callable(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """A string makes gepa build its own uninstrumented litellm client."""
        _, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )

        reflection_lm = captured["reflection_lm"]
        assert not isinstance(reflection_lm, str)
        assert callable(reflection_lm)

    def test_reflection_call__invoked_with_a_prompt_string__requests_the_optimizer_model_with_reflection_metadata(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """The callable must route the prompt through the optimizer's own model and
        label the request as reflection, so the spend is attributable."""
        _, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )
        reflection_lm = captured["reflection_lm"]

        captured_kwargs: dict[str, Any] = {}

        def capture_completion(**kwargs: Any) -> MagicMock:
            captured_kwargs.update(kwargs)
            return _make_reflection_response("improved instruction")

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: capture_completion
            output = reflection_lm("please improve this prompt")

        assert output == "improved instruction"
        assert captured_kwargs["model"] == "gpt-4o-mini"
        assert captured_kwargs["messages"] == [
            {"role": "user", "content": "please improve this prompt"}
        ]
        metadata = captured_kwargs.get("metadata") or {}
        assert metadata.get("opik_call_type") == "gepa_reflection"

    def test_reflection_call__trace_created_for_the_call__tagged_with_the_optimization_id(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """The backend attributes reflection cost by TRACE TAGS, so this is the
        assertion that keeps the spend visible. track_completion hardcodes
        tags=["litellm"], so the tags must come from _tag_trace."""
        _, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )
        reflection_lm = captured["reflection_lm"]

        tag_calls: list[Any] = []
        monkeypatch.setattr(
            "opik_optimizer.base_optimizer.opik_context.update_current_trace",
            lambda **kwargs: tag_calls.append(kwargs),
        )

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: (
                lambda **kw: _make_reflection_response("improved")
            )
            reflection_lm("improve this prompt")

        assert tag_calls, "reflection must tag its trace for cost attribution"
        tags = tag_calls[-1]["tags"]
        assert "test-opt-123" in tags
        assert "Reflection" in tags
        assert "GEPA" in tags

    def test_reflection_call__gepa_passes_a_messages_list__forwarded_to_the_provider_unchanged(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """gepa >= 0.1.x may pass an OpenAI-style messages list (multimodal)."""
        _, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )
        reflection_lm = captured["reflection_lm"]

        messages = [
            {"role": "system", "content": "you are a prompt engineer"},
            {"role": "user", "content": [{"type": "text", "text": "improve this"}]},
        ]
        captured_kwargs: dict[str, Any] = {}

        def capture_completion(**kwargs: Any) -> MagicMock:
            captured_kwargs.update(kwargs)
            return _make_reflection_response("multimodal instruction")

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: capture_completion
            output = reflection_lm(messages)

        assert output == "multimodal instruction"
        assert captured_kwargs["messages"] == messages

    def test_reflection_call__completion_carries_no_content__raises_empty_llm_response_error(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """A content-filtered or tool-call-only response yields None content.
        Stringifying it would hand gepa the literal instruction "None" and burn
        a trial on a corrupted prompt; fail loudly instead — and inside Opik's
        exception hierarchy, so callers can handle it with the rest of the SDK."""
        _, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )
        reflection_lm = captured["reflection_lm"]

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: (
                lambda **kw: _make_reflection_response(None)
            )
            with pytest.raises(EmptyLLMResponseError, match="empty response") as exc:
                reflection_lm("improve this prompt")

        assert isinstance(exc.value, opik_exceptions.OpikException)
        assert exc.value.model == "gpt-4o-mini"

    def test_reflection_lm__wrapped_in_gepas_real_tracking_lm__returns_a_str(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """gepa wraps a non-str reflection_lm in TrackingLM and invokes it with a
        single positional arg, expecting a str back. Pin that against the real
        class so a signature drift in gepa breaks here, not in a live run."""
        from gepa.lm import TrackingLM

        _, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )

        tracking_lm = TrackingLM(captured["reflection_lm"])

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: (
                lambda **kw: _make_reflection_response("instruction via gepa")
            )
            out = tracking_lm("improve this prompt")

        assert isinstance(out, str)
        assert out.strip() == "instruction via gepa"

    def test_reflection_call__provider_reports_cost_and_usage__both_reach_the_optimization_result(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """Both ends of OPIK-7521: the call is counted AND its cost reaches the result."""

        def fake_optimize(**kwargs: Any) -> MagicMock:
            def completion(**_ignored: Any) -> MagicMock:
                return _make_reflection_response(
                    "new instruction",
                    cost=1.5,
                    usage={
                        "prompt_tokens": 20,
                        "completion_tokens": 10,
                        "total_tokens": 30,
                    },
                )

            with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
                mock_track.return_value = lambda completion_fn: completion
                kwargs["reflection_lm"]("please improve this prompt")
            return _make_mock_gepa_result()

        result, _, optimizer = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            fake_optimize=fake_optimize,
        )

        assert optimizer.llm_call_counter >= 1
        assert optimizer.llm_cost_total == pytest.approx(1.5)
        assert result.llm_calls >= 1
        assert result.llm_cost_total == pytest.approx(1.5)
        assert result.llm_token_usage_total is not None
        assert result.llm_token_usage_total["total_tokens"] == 30
