import contextlib
from typing import Optional, Iterator

from opik.api_objects import experiment, opik_client, trace
from opik.api_objects.experiment import experiment_item
from opik.decorator import error_info_collector
from opik.types import ErrorInfoDict

import opik.context_storage as context_storage


@contextlib.contextmanager
def evaluate_llm_task_context(
    experiment: experiment.Experiment,
    dataset_item_id: str,
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

        client = client if client is not None else opik_client.get_client_cached()
        client.trace(**trace_data.as_parameters)

        experiment_item_ = experiment_item.ExperimentItemReferences(
            dataset_item_id=dataset_item_id,
            trace_id=trace_data.id,
        )

        experiment.insert(experiment_items_references=[experiment_item_])
