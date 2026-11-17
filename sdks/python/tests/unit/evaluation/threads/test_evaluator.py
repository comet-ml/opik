from unittest import mock

import pytest

from opik.evaluation.threads import evaluator


@pytest.mark.parametrize(
    "kwargs, expected_max_threads",
    [({}, 1000), ({"max_threads": 7}, 7)],
)
def test_evaluate_threads__max_threads__passed_to_engine(kwargs, expected_max_threads):
    with (
        mock.patch.object(evaluator.opik_client, "get_global_client"),
        mock.patch.object(evaluator.threads_client, "ThreadsClient"),
        mock.patch.object(
            evaluator.evaluation_engine, "ThreadsEvaluationEngine"
        ) as engine_class,
    ):
        evaluator.evaluate_threads(
            project_name="some_project",
            filter_string=None,
            eval_project_name=None,
            metrics=[mock.Mock()],
            trace_input_transform=lambda x: "",
            trace_output_transform=lambda x: "",
            verbose=0,
            **kwargs,
        )

    engine = engine_class.return_value
    assert (
        engine.evaluate_threads.call_args.kwargs["max_threads"] == expected_max_threads
    )
