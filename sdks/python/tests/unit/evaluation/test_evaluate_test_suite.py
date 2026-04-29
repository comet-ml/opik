"""Unit tests for run_tests() and the internal test suite evaluation pipeline."""

import threading
import unittest.mock as mock

from opik import url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik.api_objects.dataset.test_suite import test_suite
from opik.evaluation import evaluator as evaluator_module

from ...testlib import ANY_BUT_NONE, SpanModel, assert_equal
from ...testlib.models import TraceModel


def _create_mock_dataset(name="test-dataset", items=None, execution_policy=None):
    mock_dataset = mock.MagicMock()
    mock_dataset.name = name
    mock_dataset.id = "dataset-id-123"
    mock_dataset.project_name = None
    mock_dataset.dataset_items_count = len(items) if items else 0
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.get_execution_policy.return_value = execution_policy or {
        "runs_per_item": 1,
        "pass_threshold": 1,
    }
    mock_dataset.__internal_api__stream_items_as_dataclasses__ = mock.MagicMock(
        return_value=iter(items if items else [])
    )
    mock_dataset.client = None
    return mock_dataset


def _create_suite(mock_dataset, client=None):
    mock_dataset.client = client
    return test_suite.TestSuite(
        name=mock_dataset.name,
        dataset_=mock_dataset,
        client=client,
    )


def test_run_tests__creates_experiment_with_evaluation_method_test_suite():
    mock_dataset = _create_mock_dataset()
    mock_experiment = mock.MagicMock()
    mock_experiment.id = "exp-123"
    mock_experiment.name = "test-experiment"

    mock_client = mock.MagicMock()
    mock_client.create_experiment.return_value = mock_experiment

    suite = _create_suite(mock_dataset, client=mock_client)

    with mock.patch.object(
        evaluator_module.url_helpers,
        "get_experiment_url_by_id",
        return_value="http://example.com/exp",
    ):
        evaluator_module.run_tests(
            test_suite=suite,
            task=lambda item: {"input": item, "output": "response"},
            experiment_name="test-experiment",
            verbose=0,
        )

    mock_client.create_experiment.assert_called_once()
    call_kwargs = mock_client.create_experiment.call_args[1]
    # TODO: OPIK-5795 - migrate DB value from 'evaluation_suite' to 'test_suite'
    assert call_kwargs["evaluation_method"] == "evaluation_suite"


def test_run_tests__passes_evaluation_method_not_dataset():
    """Verify it's specifically 'evaluation_suite', not 'dataset'."""
    mock_dataset = _create_mock_dataset()
    mock_experiment = mock.MagicMock()
    mock_experiment.id = "exp-456"
    mock_experiment.name = "test-experiment-2"

    mock_client = mock.MagicMock()
    mock_client.create_experiment.return_value = mock_experiment

    suite = _create_suite(mock_dataset, client=mock_client)

    with mock.patch.object(
        evaluator_module.url_helpers,
        "get_experiment_url_by_id",
        return_value="http://example.com/exp",
    ):
        evaluator_module.run_tests(
            test_suite=suite,
            task=lambda item: {"input": item, "output": "response"},
            experiment_name="test-experiment-2",
            verbose=0,
        )

    call_kwargs = mock_client.create_experiment.call_args[1]
    assert call_kwargs["evaluation_method"] != "dataset"
    # TODO: OPIK-5795 - migrate DB value from 'evaluation_suite' to 'test_suite'
    assert call_kwargs["evaluation_method"] == "evaluation_suite"


def _call_run_tests(items, client=None):
    """Helper that runs run_tests through the real streamer pipeline."""
    mock_dataset = _create_mock_dataset(items=items)

    mock_experiment = mock.Mock()
    mock_experiment.id = "exp-789"
    mock_experiment.name = "source-test-experiment"

    mock_create_experiment = mock.Mock(return_value=mock_experiment)
    mock_get_url = mock.Mock(return_value="any_url")

    suite = _create_suite(mock_dataset, client=client)

    def simple_task(item):
        return {"input": item, "output": "response"}

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(url_helpers, "get_experiment_url_by_id", mock_get_url):
            evaluator_module.run_tests(
                test_suite=suite,
                task=simple_task,
                experiment_name="source-test-experiment",
                verbose=0,
                worker_threads=1,
            )


def test_run_tests__trace_tree_source_is_experiment(fake_backend):
    """run_tests produces traces with source='experiment'."""
    items = [
        dataset_item.DatasetItem(
            id="item-1", input={"message": "hello"}, reference="hello"
        ),
    ]
    _call_run_tests(items=items)

    expected = TraceModel(
        id=ANY_BUT_NONE,
        name="evaluation_task",
        input=ANY_BUT_NONE,
        output=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        source="experiment",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="simple_task",
                type="general",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                source="experiment",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="metrics_calculation",
                type="general",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                source="experiment",
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected, fake_backend.trace_trees[0])


def test_internal_run__with_optimization_id__trace_source_optimization(
    fake_backend,
):
    """When optimization_id is set via internal API, traces carry source='optimization'."""
    items = [
        dataset_item.DatasetItem(
            id="item-1", input={"message": "hello"}, reference="hello"
        ),
    ]
    mock_dataset = _create_mock_dataset(items=items)

    mock_experiment = mock.Mock()
    mock_experiment.id = "exp-789"
    mock_experiment.name = "source-test-experiment"

    mock_create_experiment = mock.Mock(return_value=mock_experiment)
    mock_get_url = mock.Mock(return_value="any_url")

    def optimization_task(item):
        return {"input": item, "output": "response"}

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(url_helpers, "get_experiment_url_by_id", mock_get_url):
            evaluator_module.__internal_api__run_test_suite__(
                suite_dataset=mock_dataset,
                task=optimization_task,
                client=None,
                experiment_name="source-test-experiment",
                verbose=0,
                task_threads=1,
                optimization_id="opt-789",
            )

    expected = TraceModel(
        id=ANY_BUT_NONE,
        name="evaluation_task",
        input=ANY_BUT_NONE,
        output=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        source="optimization",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="optimization_task",
                type="general",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                source="optimization",
            ),
            SpanModel(
                id=ANY_BUT_NONE,
                name="metrics_calculation",
                type="general",
                input=ANY_BUT_NONE,
                output=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                source="optimization",
            ),
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected, fake_backend.trace_trees[0])


def test_run_tests__explicit_client__used_for_experiment_creation():
    """When a suite has an explicit client, run_tests uses it."""
    mock_dataset = _create_mock_dataset()
    mock_experiment = mock.MagicMock()
    mock_experiment.id = "exp-explicit"
    mock_experiment.name = "explicit-experiment"

    explicit_client = mock.MagicMock(spec=opik_client.Opik)
    explicit_client.create_experiment.return_value = mock_experiment

    suite = _create_suite(mock_dataset, client=explicit_client)

    with mock.patch.object(
        evaluator_module.url_helpers,
        "get_experiment_url_by_id",
        return_value="http://example.com/exp",
    ):
        evaluator_module.run_tests(
            test_suite=suite,
            task=lambda item: {"input": item, "output": "response"},
            experiment_name="explicit-experiment",
            verbose=0,
        )

    explicit_client.create_experiment.assert_called_once()


def test_run_tests__explicit_client__propagated_to_worker_threads(
    fake_backend,
):
    """The suite's client is visible via get_global_client() inside worker threads."""
    items = [
        dataset_item.DatasetItem(
            id="item-1", input={"message": "hello"}, reference="ref"
        ),
        dataset_item.DatasetItem(
            id="item-2", input={"message": "world"}, reference="ref"
        ),
    ]
    mock_dataset = _create_mock_dataset(items=items)

    mock_experiment = mock.Mock()
    mock_experiment.id = "exp-thread"
    mock_experiment.name = "thread-test-experiment"

    mock_create_experiment = mock.Mock(return_value=mock_experiment)
    mock_get_url = mock.Mock(return_value="any_url")

    clients_seen_in_threads = []

    def task_that_captures_client(item):
        client = opik_client.get_global_client()
        clients_seen_in_threads.append((threading.current_thread().name, id(client)))
        return {"input": item, "output": "response"}

    suite = _create_suite(mock_dataset)

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock_create_experiment
    ):
        with mock.patch.object(url_helpers, "get_experiment_url_by_id", mock_get_url):
            evaluator_module.run_tests(
                test_suite=suite,
                task=task_that_captures_client,
                experiment_name="thread-test-experiment",
                verbose=0,
                worker_threads=2,
            )

    assert len(clients_seen_in_threads) == 2
    client_ids = {entry[1] for entry in clients_seen_in_threads}
    assert len(client_ids) == 1, (
        f"Worker threads should all see the same client instance, "
        f"but saw {len(client_ids)} distinct clients"
    )


# =============================================================================
# Execution policy — previously covered by e2e tests in
# tests/e2e/evaluation/test_test_suite.py, moved here because the behaviour is
# SDK-local (engine loops `runs_per_item` times; item-level policy overrides
# suite-level) and does not require a real backend.
# =============================================================================


def test_run_tests__runs_per_item__task_called_n_times():
    """Suite-level runs_per_item=2 causes the task to run twice per item."""
    items = [dataset_item.DatasetItem(id="item-1", input={"q": "hi"})]
    mock_dataset = _create_mock_dataset(
        items=items,
        execution_policy={"runs_per_item": 2, "pass_threshold": 1},
    )
    suite = _create_suite(mock_dataset)

    call_count = 0

    def task(item):
        nonlocal call_count
        call_count += 1
        return {"input": item, "output": "ok"}

    mock_experiment = mock.Mock(id="exp", name="exp")
    with (
        mock.patch.object(
            opik_client.Opik, "create_experiment", return_value=mock_experiment
        ),
        mock.patch.object(
            url_helpers, "get_experiment_url_by_id", return_value="any_url"
        ),
    ):
        evaluator_module.run_tests(
            test_suite=suite,
            task=task,
            experiment_name="exp",
            verbose=0,
            worker_threads=1,
        )

    assert call_count == 2


def test_run_tests__item_level_policy_overrides_suite_policy():
    """Per-item execution_policy wins over the suite-level default."""
    items = [
        dataset_item.DatasetItem(
            id="item-1",
            input={"q": "hi"},
            execution_policy=dataset_item.ExecutionPolicyItem(
                runs_per_item=3, pass_threshold=1
            ),
        )
    ]
    mock_dataset = _create_mock_dataset(
        items=items,
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )
    suite = _create_suite(mock_dataset)

    call_count = 0

    def task(item):
        nonlocal call_count
        call_count += 1
        return {"input": item, "output": "ok"}

    mock_experiment = mock.Mock(id="exp", name="exp")
    with (
        mock.patch.object(
            opik_client.Opik, "create_experiment", return_value=mock_experiment
        ),
        mock.patch.object(
            url_helpers, "get_experiment_url_by_id", return_value="any_url"
        ),
    ):
        evaluator_module.run_tests(
            test_suite=suite,
            task=task,
            experiment_name="exp",
            verbose=0,
            worker_threads=1,
        )

    assert call_count == 3


def test_run_tests__no_assertions__items_pass_with_single_run(fake_backend):
    """Default policy with no assertions: task runs once per item and all pass."""
    items = [
        dataset_item.DatasetItem(id="item-1", input={"q": "a"}),
        dataset_item.DatasetItem(id="item-2", input={"q": "b"}),
    ]
    mock_dataset = _create_mock_dataset(items=items)
    suite = _create_suite(mock_dataset)

    call_count = 0
    lock = threading.Lock()

    def task(item):
        nonlocal call_count
        with lock:
            call_count += 1
        return {"input": item, "output": "ok"}

    mock_experiment = mock.Mock(id="exp", name="exp")
    with (
        mock.patch.object(
            opik_client.Opik, "create_experiment", return_value=mock_experiment
        ),
        mock.patch.object(
            url_helpers, "get_experiment_url_by_id", return_value="any_url"
        ),
    ):
        result = evaluator_module.run_tests(
            test_suite=suite,
            task=task,
            experiment_name="exp",
            verbose=0,
            worker_threads=1,
        )

    assert call_count == 2
    assert result.items_total == 2
    assert result.items_passed == 2
    assert result.all_items_passed is True
    for item_result in result.item_results.values():
        assert item_result.runs_total == 1
        assert item_result.pass_threshold == 1
    assert len(fake_backend.trace_trees) == 2
