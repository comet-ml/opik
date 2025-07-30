from typing import Callable, Any, Dict, Tuple
import os
import functools
import logging
import opik
import opik.opik_context as opik_context
from . import test_runs_storage, test_run_content
from opik.decorator import inspect_helpers
import opik.config as config

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

        @opik.track(capture_input=False)
        @functools.wraps(func)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            try:
                test_trace_data = opik_context.get_current_trace_data()
                test_span_data = opik_context.get_current_span_data()
                assert (
                    test_trace_data is not None and test_span_data is not None
                ), "Must not be None here by design assumption"

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

            result = func(*args, **kwargs)
            return result

        return wrapper

    return decorator


def _get_test_nodeid() -> str:
    # Examples of environment variables:
    # 'sdks/python/tests/tests_sandbox/test_things.py::TestGroup::test_example[13 32] (call)'
    # 'sdks/python/tests/tests_sandbox/test_things.py::TestGroup::test_example (call)'
    # 'sdks/python/tests/tests_sandbox/test_things.py::test_example (call)'

    return os.environ["PYTEST_CURRENT_TEST"].rpartition(" ")[0]


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
