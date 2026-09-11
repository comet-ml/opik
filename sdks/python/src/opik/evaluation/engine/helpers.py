import contextlib
import dataclasses
from typing import Any, Dict, Optional, Iterator

from opik.api_objects import experiment, opik_client, trace
from opik.api_objects.experiment import experiment_item
from opik.decorator import error_info_collector
from opik.types import ErrorInfoDict

import opik.context_storage as context_storage


@dataclasses.dataclass
class EvaluationContextState:
    """
    Mutable state yielded by :func:`evaluate_llm_task_context`. The engine
    sets :attr:`evaluation_completed` on the happy-path-only line after
    task + scoring + score-logging all returned; the context manager's
    ``finally`` strips ``trace_data.output`` when the flag stays unset,
    so a persisted trace has ``output`` populated iff the trial
    completed cleanly. ``evaluate_resume`` relies on that invariant.
    """

    evaluation_completed: bool = False


@contextlib.contextmanager
def evaluate_llm_task_context(
    experiment: Optional[experiment.Experiment],
    dataset_item_id: str,
    trace_data: trace.TraceData,
    client: opik_client.Opik,
    execution_policy: Optional[Dict[str, Any]] = None,
) -> Iterator[EvaluationContextState]:
    state = EvaluationContextState()
    error_info: Optional[ErrorInfoDict] = None
    try:
        context_storage.set_trace_data(trace_data)
        yield state
    except Exception as exception:
        error_info = error_info_collector.collect(exception)
        raise
    finally:
        trace_data = context_storage.pop_trace_data()  # type: ignore

        assert trace_data is not None

        if error_info is not None:
            trace_data.error_info = error_info

        if not state.evaluation_completed:
            # The trace did not reach the happy-path-only line — the
            # output we collected (if any) reflects a half-finished
            # trial. Strip it so the persisted trace's ``output`` field
            # is the resume contract: present iff the trial completed.
            trace_data.output = None

        trace_data.init_end_time()

        client.__internal_api__trace__(**trace_data.as_parameters)

        # Only insert experiment item if an experiment is provided
        if experiment is not None:
            experiment_item_ = experiment_item.ExperimentItemReferences(
                dataset_item_id=dataset_item_id,
                trace_id=trace_data.id,
                project_name=trace_data.project_name,
                execution_policy=execution_policy,
            )
            experiment.insert(experiment_items_references=[experiment_item_])


@contextlib.contextmanager
def evaluate_llm_task_result_spans_context(
    trace_data: trace.TraceData,
    client: opik_client.Opik,
) -> Iterator[None]:
    error_info: Optional[ErrorInfoDict] = None
    try:
        context_storage.set_trace_data(trace_data)
        yield
    except Exception as exception:
        error_info = error_info_collector.collect(exception)
        raise
    finally:
        trace_data = context_storage.pop_trace_data()  # type: ignore

        assert trace_data is not None

        if error_info is not None:
            trace_data.error_info = error_info

        trace_data.init_end_time()
        client.__internal_api__trace__(**trace_data.as_parameters)
