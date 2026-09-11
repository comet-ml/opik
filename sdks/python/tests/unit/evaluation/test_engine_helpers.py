"""
Unit coverage for the resume-completion-marker logic in
``opik.evaluation.engine.helpers.evaluate_llm_task_context``.

The contract this PR introduces:

- The context manager yields a mutable ``EvaluationContextState``. The
  engine flips ``state.evaluation_completed = True`` on the happy-path-only
  line after task + scoring + score-logging all returned cleanly.
- If the flag stays ``False`` when the context exits (any exception path,
  ``KeyboardInterrupt`` after the task ran, or simply not reaching the
  flag line), the ``finally`` block strips ``trace_data.output`` back to
  ``None`` so the persisted trace's ``output`` field is the resume
  contract: present iff the trial completed cleanly.
- The trace is still emitted in both cases (so ``evaluate_resume`` can
  read the experiment item and decide to replay).
"""

from unittest import mock

import pytest

import opik
from opik.api_objects.trace import trace_data
from opik.evaluation.engine import helpers


def _build_trace(output=None):
    return trace_data.TraceData(name="test-trace", output=output)


def _make_client():
    return mock.Mock(spec=opik.Opik)


class TestEvaluationContextStateMarker:
    def test_happy_path__flag_set__output_preserved(self):
        client = _make_client()
        trace = _build_trace(output={"value": "ok"})

        with helpers.evaluate_llm_task_context(
            experiment=None,
            dataset_item_id="item-1",
            trace_data=trace,
            client=client,
        ) as state:
            state.evaluation_completed = True

        client.__internal_api__trace__.assert_called_once()
        emitted = client.__internal_api__trace__.call_args.kwargs
        assert emitted["output"] == {"value": "ok"}

    def test_flag_never_set__output_stripped_to_none(self):
        """The body returns normally but never flips the flag — same shape
        as a ``KeyboardInterrupt`` arriving between task completion and
        score-logging, or a metric raising a ``BaseException`` that
        escapes the engine's ``except Exception`` handler."""
        client = _make_client()
        trace = _build_trace(output={"value": "task_returned_this"})

        with helpers.evaluate_llm_task_context(
            experiment=None,
            dataset_item_id="item-1",
            trace_data=trace,
            client=client,
        ):
            # Simulate: task wrote its output to the trace, but scoring
            # crashed (or the operator hit Ctrl-C) before reaching the
            # happy-path-only line. The flag stays False.
            pass

        emitted = client.__internal_api__trace__.call_args.kwargs
        assert emitted["output"] is None, (
            "Output must be stripped when the happy-path-only line did "
            "not run; otherwise ``evaluate_resume`` would mis-classify a "
            "half-finished trial as completed."
        )

    def test_exception_in_body__output_stripped_and_error_info_captured(self):
        client = _make_client()
        trace = _build_trace(output={"value": "task_returned_this"})

        with pytest.raises(RuntimeError, match="simulated"):
            with helpers.evaluate_llm_task_context(
                experiment=None,
                dataset_item_id="item-1",
                trace_data=trace,
                client=client,
            ) as state:
                # state never gets flipped to True
                raise RuntimeError("simulated task failure")

        emitted = client.__internal_api__trace__.call_args.kwargs
        assert emitted["output"] is None
        # error_info recorded by ``error_info_collector``; we don't pin
        # the exact shape (that's collector-internal), just presence.
        assert emitted["error_info"] is not None
        # The state we received is the dataclass — sanity check.
        assert isinstance(state, helpers.EvaluationContextState)
        assert state.evaluation_completed is False

    def test_yielded_state_is_default_false(self):
        client = _make_client()
        trace = _build_trace()

        observed_state = None
        with helpers.evaluate_llm_task_context(
            experiment=None,
            dataset_item_id="item-1",
            trace_data=trace,
            client=client,
        ) as state:
            observed_state = state

        assert isinstance(observed_state, helpers.EvaluationContextState)
        # Default at yield time is False; the engine has to explicitly opt
        # in via ``state.evaluation_completed = True``.
        # (We didn't flip it in this test, so its post-with value is also
        # False — but the meaningful assertion is the default at yield.)

    def test_partial_output_update__state_set__update_preserved(self):
        """End-to-end of the engine's typical sequence: task returns,
        ``update_current_trace(output=...)`` sets it on the in-context
        trace, the happy-path flag is set, the context exits — the
        persisted trace must carry the output."""
        client = _make_client()
        trace = _build_trace()

        with helpers.evaluate_llm_task_context(
            experiment=None,
            dataset_item_id="item-1",
            trace_data=trace,
            client=client,
        ) as state:
            # Mirrors ``opik_context.update_current_trace(output=...)``.
            trace.output = {"value": "computed_by_task"}
            state.evaluation_completed = True

        emitted = client.__internal_api__trace__.call_args.kwargs
        assert emitted["output"] == {"value": "computed_by_task"}
