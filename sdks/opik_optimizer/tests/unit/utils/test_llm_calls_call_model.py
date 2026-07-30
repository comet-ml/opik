"""
Unit tests for call_model/call_model_async and related utilities in opik_optimizer.core.llm_calls.
"""

from __future__ import annotations

from collections.abc import Callable
from decimal import Decimal
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from pydantic import BaseModel

from opik_optimizer.core import llm_calls as _llm_calls
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core.state import OptimizationContext
from tests.unit.fixtures import user_message
from tests.unit.test_helpers import make_mock_response


def _assert_json_retry_instructions_injected(
    captured_messages: list[list[dict[str, str]]],
) -> None:
    assert len(captured_messages) == 2
    assert "STRICT OUTPUT FORMAT" in captured_messages[1][-1]["content"]


class SampleResponseModel(BaseModel):
    """Sample Pydantic model for testing structured output."""

    name: str
    score: float


class TestCallModelSync:
    def test_call_model_uses_strict_response_schema_for_openai(self) -> None:
        captured_kwargs: dict[str, Any] = {}
        mock_response = make_mock_response(
            '{"inner": {"value": 1, "detail": null}, "note": null}'
        )

        class Inner(BaseModel):
            value: int
            detail: str | None = None

        class Outer(BaseModel):
            inner: Inner
            note: str | None = None

        def capture_completion(**kwargs: Any) -> MagicMock:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_completion

            result = _llm_calls.call_model(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=Outer,
            )

        assert isinstance(result, Outer)
        assert result.inner.value == 1
        assert result.inner.detail is None
        assert result.note is None
        response_format = captured_kwargs.get("response_format", {})
        schema = response_format.get("json_schema", {}).get("schema", {})
        assert schema.get("additionalProperties") is False
        assert schema.get("required") == ["inner", "note"]
        assert schema.get("$defs", {}).get("Inner", {}).get("additionalProperties") is (
            False
        )
        assert schema.get("$defs", {}).get("Inner", {}).get("required") == [
            "value",
            "detail",
        ]

    def test_call_model_increments_counter(self) -> None:
        mock_response = make_mock_response("response")

        with patch(
            "opik_optimizer.core.llm_calls._increment_llm_counter_if_in_optimizer"
        ) as mock_inc:
            with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
                mock_track.return_value = lambda x: x
                with patch("litellm.completion", return_value=mock_response):
                    _llm_calls.call_model(
                        messages=[user_message("test")],
                        model="gpt-4o",
                    )
                    mock_inc.assert_called_once()

    def test_call_model_with_structured_output(self) -> None:
        mock_response = make_mock_response('{"name": "test", "score": 0.9}')

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_completion = MagicMock(return_value=mock_response)
            mock_track.return_value = lambda x: mock_completion

            result = _llm_calls.call_model(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
            )

            assert isinstance(result, SampleResponseModel)
            assert result.name == "test"

    def test_call_model_retries_with_json_instructions(self) -> None:
        captured_messages: list[list[dict[str, str]]] = []
        call_count = {"n": 0}

        def mock_completion(**kwargs: Any) -> MagicMock:
            captured_messages.append(kwargs["messages"])
            if call_count["n"] == 0:
                call_count["n"] += 1
                return make_mock_response("not json")
            return make_mock_response('{"name": "retry", "score": 1.0}')

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: mock_completion

            result = _llm_calls.call_model(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
            )

            assert isinstance(result, SampleResponseModel)
            assert result.name == "retry"
            _assert_json_retry_instructions_injected(captured_messages)

    def test_call_model_preserves_model_parameter_response_format(self) -> None:
        mock_response = make_mock_response('{"name": "test", "score": 0.9}')
        captured_kwargs: dict[str, Any] = {}
        custom_response_format = {"type": "json_object"}

        def capture_completion(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_completion
            result = _llm_calls.call_model(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
                model_parameters={"response_format": custom_response_format},
            )

        assert isinstance(result, SampleResponseModel)
        assert captured_kwargs.get("response_format") == custom_response_format


class TestCallModelAsync:
    @pytest.mark.asyncio
    async def test_call_model_async_uses_strict_response_schema_for_openai(
        self,
    ) -> None:
        captured_kwargs: dict[str, Any] = {}
        mock_response = make_mock_response(
            '{"inner": {"value": 1, "detail": null}, "note": null}'
        )

        class Inner(BaseModel):
            value: int
            detail: str | None = None

        class Outer(BaseModel):
            inner: Inner
            note: str | None = None

        async def capture_completion(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_completion

            result = await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=Outer,
            )

        assert isinstance(result, Outer)
        assert result.inner.value == 1
        assert result.inner.detail is None
        assert result.note is None
        response_format = captured_kwargs.get("response_format", {})
        schema = response_format.get("json_schema", {}).get("schema", {})
        assert schema.get("additionalProperties") is False
        assert schema.get("required") == ["inner", "note"]
        assert schema.get("$defs", {}).get("Inner", {}).get("additionalProperties") is (
            False
        )
        assert schema.get("$defs", {}).get("Inner", {}).get("required") == [
            "value",
            "detail",
        ]

    @pytest.mark.asyncio
    async def test_call_model_async_increments_counter(self) -> None:
        mock_response = make_mock_response("response")

        with patch(
            "opik_optimizer.core.llm_calls._increment_llm_counter_if_in_optimizer"
        ) as mock_inc:
            with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
                async_mock = AsyncMock(return_value=mock_response)
                mock_track.return_value = lambda x: async_mock

                await _llm_calls.call_model_async(
                    messages=[user_message("test")],
                    model="gpt-4o",
                )
                mock_inc.assert_called_once()

    @pytest.mark.asyncio
    async def test_call_model_async_with_structured_output(self) -> None:
        mock_response = make_mock_response('{"name": "test", "score": 0.9}')

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            async_mock = AsyncMock(return_value=mock_response)
            mock_track.return_value = lambda x: async_mock

            result = await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
            )

            assert isinstance(result, SampleResponseModel)
            assert result.name == "test"

    @pytest.mark.asyncio
    async def test_call_model_async_retries_with_json_instructions(self) -> None:
        captured_messages: list[list[dict[str, str]]] = []
        call_count = {"n": 0}

        async def mock_completion(**kwargs: Any) -> MagicMock:
            captured_messages.append(kwargs["messages"])
            if call_count["n"] == 0:
                call_count["n"] += 1
                return make_mock_response("not json")
            return make_mock_response('{"name": "retry", "score": 1.0}')

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: mock_completion

            result = await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
            )

            assert isinstance(result, SampleResponseModel)
            assert result.name == "retry"
            _assert_json_retry_instructions_injected(captured_messages)

    @pytest.mark.asyncio
    async def test_call_model_async_preserves_model_parameter_response_format(
        self,
    ) -> None:
        mock_response = make_mock_response('{"name": "test", "score": 0.9}')
        captured_kwargs: dict[str, Any] = {}
        custom_response_format = {"type": "json_object"}

        async def capture_completion(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_completion
            result = await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                response_model=SampleResponseModel,
                model_parameters={"response_format": custom_response_format},
            )

        assert isinstance(result, SampleResponseModel)
        assert captured_kwargs.get("response_format") == custom_response_format

    @pytest.mark.asyncio
    async def test_call_model_async_passes_model_parameters(self) -> None:
        mock_response = make_mock_response("response")
        captured_kwargs: dict[str, Any] = {}

        async def capture_call(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return mock_response

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda x: capture_call

            await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                model_parameters={"temperature": 0.5},
                temperature=0.7,
            )

            assert captured_kwargs["temperature"] == 0.7

    @pytest.mark.asyncio
    async def test_call_model_async_project_name_passed(self) -> None:
        mock_response = make_mock_response("response")
        captured_project: str | None = None

        def capture_track(
            project_name: str | None = None,
        ) -> Callable[[Any], AsyncMock]:
            nonlocal captured_project
            captured_project = project_name
            return lambda x: AsyncMock(return_value=mock_response)

        with patch(
            "opik_optimizer.core.llm_calls.track_completion", side_effect=capture_track
        ):
            await _llm_calls.call_model_async(
                messages=[user_message("test")],
                model="gpt-4o",
                project_name="my-project",
            )

            assert captured_project == "my-project"


class TestStripProjectName:
    def test_strip_removes_project_name(self) -> None:
        params: dict[str, Any] = {
            "model": "gpt-4o",
            "metadata": {"opik": {"project_name": "test", "tags": ["tag1"]}},
        }

        result: dict[str, Any] = _llm_calls._strip_project_name(params)

        assert "project_name" in params["metadata"]["opik"]
        assert "project_name" not in result["metadata"]["opik"]
        assert result["metadata"]["opik"]["tags"] == ["tag1"]

    def test_strip_handles_missing_metadata(self) -> None:
        params = {"model": "gpt-4o"}
        result = _llm_calls._strip_project_name(params)
        assert result == params

    def test_strip_handles_empty_opik(self) -> None:
        params = {"metadata": {"opik": {"project_name": "test"}}}
        result = _llm_calls._strip_project_name(params)
        assert "opik" not in result["metadata"]


def _make_mock_optimizer() -> BaseOptimizer:
    class MockOptimizer(BaseOptimizer):
        DEFAULT_PROMPTS: dict[str, str] = {}

        def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
            pass

        def run_optimization(self, context: OptimizationContext) -> Any:
            pass

        def get_config(self, context: OptimizationContext) -> dict[str, Any]:
            return {"optimizer": "MockOptimizer"}

        def get_optimizer_metadata(self) -> dict[str, Any]:
            return {}

    return MockOptimizer(model="gpt-4o")


class TestCostUsageCapture:
    """OPIK-7521: call_model must accumulate cost/usage onto the calling optimizer."""

    def _make_costed_response(self) -> Any:
        from types import SimpleNamespace

        response = make_mock_response("ok")
        response.cost = 0.25
        response.usage = SimpleNamespace(
            prompt_tokens=10, completion_tokens=5, total_tokens=15
        )
        return response

    def test_call_model_records_cost_and_usage_to_optimizer(self) -> None:
        optimizer = _make_mock_optimizer()
        response = self._make_costed_response()

        def inner_call(self: Any) -> None:
            _llm_calls.call_model(messages=[user_message("test")], model="gpt-4o")

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: lambda **kw: response
            inner_call(optimizer)

        assert optimizer.llm_call_counter == 1
        assert optimizer.llm_cost_total == pytest.approx(0.25)
        assert optimizer.llm_token_usage_total == {
            "prompt_tokens": 10,
            "completion_tokens": 5,
            "total_tokens": 15,
        }

    def test_call_model_cost_falls_back_to_hidden_params(self) -> None:
        optimizer = _make_mock_optimizer()
        response = make_mock_response("ok")
        response.cost = None
        response.usage = None
        response._hidden_params = {"response_cost": 0.5}

        def inner_call(self: Any) -> None:
            _llm_calls.call_model(messages=[user_message("test")], model="gpt-4o")

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: lambda **kw: response
            inner_call(optimizer)

        assert optimizer.llm_cost_total == pytest.approx(0.5)

    def test_cost_extraction_is_robust_to_exotic_response_objects(self) -> None:
        """Robustness guard, not regression coverage: cost/usage extraction is
        telemetry and must never abort an otherwise-successful run, whatever the
        provider returns. Passes on unfixed code by construction."""
        optimizer = _make_mock_optimizer()

        class Hostile:
            """Response whose cost/usage explode in every way we've seen."""

            choices = [
                SimpleNamespace(message=SimpleNamespace(content="ok", parsed=None))
            ]

            @property
            def cost(self) -> Any:
                raise RuntimeError("provider blew up reading cost")

            @property
            def usage(self) -> Any:
                return SimpleNamespace(
                    prompt_tokens=float("inf"),  # non-finite
                    completion_tokens="many",  # not a number
                    total_tokens=None,
                )

        def inner_call(self: Any) -> None:
            _llm_calls.call_model(messages=[user_message("test")], model="gpt-4o")

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: lambda **kw: Hostile()
            inner_call(optimizer)

        # The call still succeeded and recorded nothing rather than raising.
        assert optimizer.llm_call_counter == 1
        assert optimizer.llm_cost_total == 0.0
        assert optimizer.llm_token_usage_total["total_tokens"] == 0

    def test_call_model_records_dict_usage(self) -> None:
        """Some providers hand back `usage` as a plain dict."""
        optimizer = _make_mock_optimizer()
        response = make_mock_response("ok")
        response.cost = None
        response._hidden_params = {}
        response.usage = {
            "prompt_tokens": 7,
            "completion_tokens": 3,
            "total_tokens": 10,
        }

        def inner_call(self: Any) -> None:
            _llm_calls.call_model(messages=[user_message("test")], model="gpt-4o")

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: lambda **kw: response
            inner_call(optimizer)

        assert optimizer.llm_token_usage_total == {
            "prompt_tokens": 7,
            "completion_tokens": 3,
            "total_tokens": 10,
        }

    def test_call_model_records_decimal_cost(self) -> None:
        """Decimal cost must not be silently dropped."""
        optimizer = _make_mock_optimizer()
        response = make_mock_response("ok")
        response.cost = Decimal("0.125")
        response.usage = None

        def inner_call(self: Any) -> None:
            _llm_calls.call_model(messages=[user_message("test")], model="gpt-4o")

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: lambda **kw: response
            inner_call(optimizer)

        assert optimizer.llm_cost_total == pytest.approx(0.125)

    @pytest.mark.asyncio
    async def test_call_model_async_records_cost_and_usage_to_optimizer(self) -> None:
        optimizer = _make_mock_optimizer()
        response = self._make_costed_response()

        async def completion(**kw: Any) -> Any:
            return response

        async def inner_call(self: Any) -> None:
            await _llm_calls.call_model_async(
                messages=[user_message("test")], model="gpt-4o"
            )

        with patch("opik_optimizer.core.llm_calls.track_completion") as mock_track:
            mock_track.return_value = lambda completion_fn: completion
            await inner_call(optimizer)

        assert optimizer.llm_call_counter == 1
        assert optimizer.llm_cost_total == pytest.approx(0.25)
        assert optimizer.llm_token_usage_total["total_tokens"] == 15


class TestDuplicateOpikLoggerStripped:
    """OPIK-7521: the litellm OpikLogger must be dropped only when it would
    duplicate a span we already log, i.e. when a tracked span is open.

    With a span open (GEPA reflection under @opik.track), track_completion logs
    the call and the OpikLogger would nest a second, identically-priced span
    into the same trace — doubling the cost the backend sums per trace.

    With no span open (every other call_model caller), the OpikLogger creates
    its OWN trace and is the only thing that stamps
    metadata["opik"]["tags"] = [optimization_id, ...] onto it — track_completion
    hardcodes tags=["litellm"]. Stripping it there would delete the sole
    optimization-tagged trace and hide that spend from the run's cost.
    """

    def _make_logger(self) -> Any:
        from litellm.integrations.opik.opik import OpikLogger

        return OpikLogger()

    def _call_with_callbacks(
        self, opik_logger: Any, other_callback: Any, *, span_open: bool
    ) -> dict[str, Any]:
        captured_kwargs: dict[str, Any] = {}

        def fake_monitoring(params: dict[str, Any]) -> dict[str, Any]:
            return {
                **params,
                "success_callback": [opik_logger, other_callback],
                "failure_callback": [opik_logger],
            }

        def capture(**kwargs: Any) -> Any:
            captured_kwargs.update(kwargs)
            return make_mock_response("ok")

        with patch(
            "opik_optimizer.core.llm_calls.opik_litellm_monitor."
            "try_add_opik_monitoring_to_params",
            side_effect=fake_monitoring,
        ):
            with patch(
                "opik_optimizer.core.llm_calls.opik_context_storage."
                "span_data_stack_empty",
                return_value=not span_open,
            ):
                with patch(
                    "opik_optimizer.core.llm_calls.track_completion"
                ) as mock_track:
                    mock_track.return_value = lambda completion_fn: capture
                    _llm_calls.call_model(
                        messages=[user_message("test")], model="gpt-4o"
                    )

        return captured_kwargs

    def test_strips_opik_logger_when_a_span_is_already_open(self) -> None:
        opik_logger = self._make_logger()
        other_callback = object()

        captured_kwargs = self._call_with_callbacks(
            opik_logger, other_callback, span_open=True
        )

        assert opik_logger not in captured_kwargs.get("success_callback", [])
        assert opik_logger not in captured_kwargs.get("failure_callback", [])
        # A caller's own callbacks must survive.
        assert other_callback in captured_kwargs.get("success_callback", [])

    def test_keeps_opik_logger_when_no_span_is_open(self) -> None:
        """Without this, non-GEPA optimizers lose optimization-id trace tagging."""
        opik_logger = self._make_logger()
        other_callback = object()

        captured_kwargs = self._call_with_callbacks(
            opik_logger, other_callback, span_open=False
        )

        assert opik_logger in captured_kwargs.get("success_callback", [])
        assert opik_logger in captured_kwargs.get("failure_callback", [])
        assert other_callback in captured_kwargs.get("success_callback", [])


class TestCounterIncrement:
    def test_increment_llm_counter_walks_stack(self) -> None:
        class MockOptimizer(BaseOptimizer):
            DEFAULT_PROMPTS: dict[str, str] = {}

            def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
                pass

            def run_optimization(self, context: OptimizationContext) -> Any:
                pass

            def get_config(self, context: OptimizationContext) -> dict[str, Any]:
                return {"optimizer": "MockOptimizer"}

            def get_optimizer_metadata(self) -> dict[str, Any]:
                return {}

        optimizer = MockOptimizer(model="gpt-4o")
        initial_count = optimizer.llm_call_counter

        def inner_call(self: Any) -> None:
            _llm_calls._increment_llm_counter_if_in_optimizer()

        inner_call(optimizer)

        assert optimizer.llm_call_counter == initial_count + 1

    def test_increment_llm_call_tools_counter_walks_stack(self) -> None:
        class MockOptimizer(BaseOptimizer):
            DEFAULT_PROMPTS: dict[str, str] = {}

            def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
                pass

            def run_optimization(self, context: OptimizationContext) -> Any:
                pass

            def get_config(self, context: OptimizationContext) -> dict[str, Any]:
                return {"optimizer": "MockOptimizer"}

            def get_optimizer_metadata(self) -> dict[str, Any]:
                return {}

        optimizer = MockOptimizer(model="gpt-4o")
        initial_count = optimizer.llm_call_tools_counter

        def inner_call(self: Any) -> None:
            _llm_calls._increment_llm_call_tools_counter_if_in_optimizer()

        inner_call(optimizer)

        assert optimizer.llm_call_tools_counter == initial_count + 1
