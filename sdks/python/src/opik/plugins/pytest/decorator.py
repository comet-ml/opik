from typing import Callable, Any, Dict, Tuple
import os
import functools
import logging
import inspect
import opik
import opik.opik_context as opik_context
from . import test_runs_storage, test_run_content
from opik.decorator import inspect_helpers
import opik.config as config
from opik.simulation.episode import EpisodeResult

LOGGER = logging.getLogger(__name__)


def llm_unit(
    expected_output_key: str = "expected_output",
    input_key: str = "input",
    metadata_key: str = "metadata",
) -> Callable[[Any], Any]:
    """
    Decorator used for special tests tracking.
    Mark your test with `llm_unit` and when you run `pytest`, Opik will
    create an experiment and log test results to it: test name, test inputs, result.

    Arguments:
        expected_output_key: test argument name that will be logged as `expected_output` of the LLM task.
            If not provided, Opik will try to find `expected_output` in arguments.
        input_key: test argument name that will be logged as `input` of the LLM task.
            If not provided, Opik will try to find `input` in arguments.
        metadata_key: test argument name that will be logged as `metadata`.
            If not provided, Opik will try to find `metadata` in arguments.
    """
    argnames_mapping = {
        "expected_output": expected_output_key,
        "input": input_key,
        "metadata": metadata_key,
    }

    def decorator(func: Callable[[Any], Any]) -> Callable[[Any], Any]:
        config_ = config.get_from_user_inputs()
        if not config_.pytest_experiment_enabled:
            return func

        def _capture_test_run_content(*args: Any, **kwargs: Any) -> None:
            try:
                test_trace_data = opik_context.get_current_trace_data()
                test_span_data = opik_context.get_current_span_data()
                assert test_trace_data is not None and test_span_data is not None, (
                    "Must not be None here by design assumption"
                )

                node_id: str = _get_test_nodeid()
                test_runs_storage.LLM_UNIT_TEST_RUNS.add(node_id)

                test_run_content_ = _get_test_run_content(
                    func=func,
                    args=args,
                    kwargs=kwargs,
                    argnames_mapping=argnames_mapping,
                )

                trace_input = {**test_run_content_.input}
                trace_input.pop("test_name")  # we don't need it in traces
                opik_context.update_current_trace(
                    input=trace_input,
                    metadata=test_run_content_.metadata,
                )
                opik_context.update_current_span(
                    input=trace_input,
                    metadata=test_run_content_.metadata,
                )

                test_runs_storage.TEST_RUNS_TO_TRACE_DATA[node_id] = test_trace_data
                test_runs_storage.TEST_RUNS_CONTENTS[node_id] = test_run_content_
            except Exception:
                LOGGER.error(
                    "Unexpected exception occured during llm_unit test tracking for test %s",
                    func.__name__,
                    exc_info=True,
                )

        if inspect.iscoroutinefunction(func):

            @opik.track(capture_input=False)
            @functools.wraps(func)
            async def wrapper(*args: Any, **kwargs: Any) -> Any:
                _capture_test_run_content(*args, **kwargs)
                return await func(*args, **kwargs)

        else:

            @opik.track(capture_input=False)
            @functools.wraps(func)
            def wrapper(*args: Any, **kwargs: Any) -> Any:
                _capture_test_run_content(*args, **kwargs)
                return func(*args, **kwargs)

        return wrapper

    return decorator


def llm_episode(
    scenario_id_key: str = "scenario_id",
    thread_id_key: str = "thread_id",
    expected_output_key: str = "expected_output",
    input_key: str = "input",
    metadata_key: str = "metadata",
) -> Callable[[Any], Any]:
    """
    Decorator for episode-based agent tests.

    Works like llm_unit for trace linking and additionally captures EpisodeResult
    when the test returns one (or returns a compatible dictionary).
    """
    argnames_mapping = {
        "expected_output": expected_output_key,
        "input": input_key,
        "metadata": metadata_key,
    }

    def decorator(func: Callable[[Any], Any]) -> Callable[[Any], Any]:
        config_ = config.get_from_user_inputs()
        if not config_.pytest_experiment_enabled:
            return func

        signature = inspect.signature(func)

        def _capture_test_run_content(*args: Any, **kwargs: Any) -> str:
            node_id = _get_test_nodeid()
            try:
                test_trace_data = opik_context.get_current_trace_data()
                test_span_data = opik_context.get_current_span_data()
                assert test_trace_data is not None and test_span_data is not None, (
                    "Must not be None here by design assumption"
                )

                test_runs_storage.LLM_UNIT_TEST_RUNS.add(node_id)

                test_run_content_ = _get_test_run_content(
                    func=func,
                    args=args,
                    kwargs=kwargs,
                    argnames_mapping=argnames_mapping,
                )

                trace_input = {**test_run_content_.input}
                trace_input.pop("test_name")  # we don't need it in traces
                opik_context.update_current_trace(
                    input=trace_input,
                    metadata=test_run_content_.metadata,
                )
                opik_context.update_current_span(
                    input=trace_input,
                    metadata=test_run_content_.metadata,
                )

                test_runs_storage.TEST_RUNS_TO_TRACE_DATA[node_id] = test_trace_data
                test_runs_storage.TEST_RUNS_CONTENTS[node_id] = test_run_content_
            except Exception:
                LOGGER.error(
                    "Unexpected exception occured during llm_episode test tracking for test %s",
                    func.__name__,
                    exc_info=True,
                )
                raise
            return node_id

        def _capture_episode_result(
            node_id: str,
            result_candidate: Any,
            args: Tuple[Any, ...],
            kwargs: Dict[str, Any],
        ) -> None:
            try:
                bound_arguments = signature.bind_partial(*args, **kwargs)
                call_arguments = dict(bound_arguments.arguments)
            except Exception:
                call_arguments = dict(kwargs)

            scenario_id_fallback = str(call_arguments.get(scenario_id_key, node_id))
            thread_id_fallback = call_arguments.get(thread_id_key)

            try:
                episode_result = EpisodeResult.from_any(
                    result_candidate,
                    fallback_scenario_id=scenario_id_fallback,
                    fallback_thread_id=thread_id_fallback,
                )
            except Exception:
                LOGGER.error(
                    "Failed to parse EpisodeResult for test %s",
                    func.__name__,
                    exc_info=True,
                )
                return

            if episode_result is not None:
                test_runs_storage.TEST_RUNS_EPISODES[node_id] = episode_result

        if inspect.iscoroutinefunction(func):

            @opik.track(capture_input=False)
            @functools.wraps(func)
            async def wrapper(*args: Any, **kwargs: Any) -> Any:
                node_id = _capture_test_run_content(*args, **kwargs)
                result = await func(*args, **kwargs)
                _capture_episode_result(
                    node_id=node_id,
                    result_candidate=result,
                    args=args,
                    kwargs=kwargs,
                )
                return result

        else:

            @opik.track(capture_input=False)
            @functools.wraps(func)
            def wrapper(*args: Any, **kwargs: Any) -> Any:
                node_id = _capture_test_run_content(*args, **kwargs)
                result = func(*args, **kwargs)
                _capture_episode_result(
                    node_id=node_id,
                    result_candidate=result,
                    args=args,
                    kwargs=kwargs,
                )
                return result

        return wrapper

    return decorator


def _get_test_nodeid() -> str:
    # Examples of environment variables:
    # 'sdks/python/tests/tests_sandbox/test_things.py::TestGroup::test_example[13 32] (call)'
    # 'sdks/python/tests/tests_sandbox/test_things.py::TestGroup::test_example (call)'
    # 'sdks/python/tests/tests_sandbox/test_things.py::test_example (call)'

    current_test = os.environ.get("PYTEST_CURRENT_TEST")
    if not current_test:
        raise KeyError("PYTEST_CURRENT_TEST is not set")

    nodeid, _, _ = current_test.rpartition(" ")
    return nodeid or current_test


def _get_test_run_content(
    func: Callable,
    args: Tuple,
    kwargs: Dict[str, Any],
    argnames_mapping: Dict[str, str],
) -> test_run_content.TestRunContent:
    test_inputs = inspect_helpers.extract_inputs(func, args, kwargs)
    input = test_inputs.get(argnames_mapping["input"], {})
    metadata = test_inputs.get(argnames_mapping["metadata"], None)
    expected_output = test_inputs.get(argnames_mapping["expected_output"], None)

    if not isinstance(input, dict):
        input = {"test_name": _get_test_nodeid(), "input": input}
    else:
        input = {"test_name": _get_test_nodeid(), **input}

    if expected_output is not None and not isinstance(expected_output, dict):
        expected_output = {"expected_output": expected_output}

    if metadata is not None and not isinstance(metadata, dict):
        metadata = {"metadata": metadata}

    result = test_run_content.TestRunContent(
        input=input,
        expected_output=expected_output,
        metadata=metadata,
    )

    return result
