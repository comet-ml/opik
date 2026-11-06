from typing import Callable, Any, Dict, List, Optional, Tuple
import os
import functools
import logging
import opik
import opik.opik_context as opik_context
from opik import context_storage
from opik.api_objects import span
from . import test_runs_storage, test_run_content
from opik.decorator import inspect_helpers
import opik.config as config
from opik import tracing_runtime_config

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

        if inspect_helpers.is_async(func):

            @opik.track(name=func.__name__, capture_input=False)
            async def tracked_async(
                stopped_by: List[BaseException], /, *args: Any, **kwargs: Any
            ) -> Any:
                test_span = opik_context.get_current_span_data()
                _register_test_run(func, args, kwargs, argnames_mapping)
                try:
                    return await func(*args, **kwargs)
                except Exception:
                    raise
                except BaseException as exception:
                    _hold_until_trace_ends(exception, stopped_by, test_span)
                    return None

            @functools.wraps(func)
            async def async_wrapper(*args: Any, **kwargs: Any) -> Any:
                if not tracing_runtime_config.is_tracing_active():
                    return await func(*args, **kwargs)

                stopped_by: List[BaseException] = []
                result = await tracked_async(stopped_by, *args, **kwargs)
                if stopped_by:
                    raise stopped_by[0]
                return result

            setattr(async_wrapper, "_opik_llm_unit", True)
            return async_wrapper

        @opik.track(name=func.__name__, capture_input=False)
        def tracked(
            stopped_by: List[BaseException], /, *args: Any, **kwargs: Any
        ) -> Any:
            test_span = opik_context.get_current_span_data()
            _register_test_run(func, args, kwargs, argnames_mapping)
            try:
                return func(*args, **kwargs)
            except Exception:
                raise
            except BaseException as exception:
                _hold_until_trace_ends(exception, stopped_by, test_span)
                return None

        @functools.wraps(func)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            if not tracing_runtime_config.is_tracing_active():
                return func(*args, **kwargs)

            stopped_by: List[BaseException] = []
            result = tracked(stopped_by, *args, **kwargs)
            if stopped_by:
                raise stopped_by[0]
            return result

        setattr(wrapper, "_opik_llm_unit", True)
        return wrapper

    return decorator


def _hold_until_trace_ends(
    exception: BaseException,
    stopped_by: List[BaseException],
    test_span: Optional[span.SpanData],
) -> None:
    # pytest.skip(), pytest.xfail(), pytest.fail() and sys.exit() raise
    # BaseExceptions that @opik.track doesn't handle, which would leave the
    # trace open for the next test. Hold the exception until the trace has
    # ended. If it came from a tracked helper inside the test, that helper's
    # span is still on the stack, so trim back to the test's own span first.
    stopped_by.append(exception)
    if test_span is not None:
        context_storage.trim_span_data_stack_to_certain_span(test_span.id)


def _register_test_run(
    func: Callable,
    args: Tuple,
    kwargs: Dict[str, Any],
    argnames_mapping: Dict[str, str],
) -> None:
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
            "Unexpected exception occurred during llm_unit test tracking for test %s",
            func.__name__,
            exc_info=True,
        )


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
