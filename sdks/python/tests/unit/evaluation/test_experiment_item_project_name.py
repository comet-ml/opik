"""Tests to verify project_name is correctly passed through experiment item creation."""

from unittest import mock

import opik
from opik.api_objects.experiment import experiment_item
from opik.api_objects.trace import trace_data
from opik.evaluation.engine import helpers
from opik.message_processing import messages


def test_evaluate_llm_task_context__experiment_item_includes_trace_project_name():
    """
    Verify that when creating experiment items via evaluate_llm_task_context,
    the project_name from the trace is included in the ExperimentItemReferences.
    """
    # Setup
    test_project_name = "test-project"
    dataset_item_id = "dataset-item-123"

    trace = trace_data.TraceData(
        name="test-trace",
        project_name=test_project_name,
    )

    # Create mock experiment
    mock_experiment = mock.Mock()
    mock_experiment.insert = mock.Mock()

    # Create mock client
    mock_client = mock.Mock(spec=opik.Opik)

    # Execute the context manager
    with helpers.evaluate_llm_task_context(
        experiment=mock_experiment,
        dataset_item_id=dataset_item_id,
        trace_data=trace,
        client=mock_client,
    ):
        pass  # Context manager handles experiment item creation on exit

    # Verify experiment.insert was called
    mock_experiment.insert.assert_called_once()

    # Get the experiment items that were passed to insert
    call_args = mock_experiment.insert.call_args
    experiment_items = call_args.kwargs["experiment_items_references"]

    # Verify the experiment item has the correct project_name
    assert len(experiment_items) == 1
    exp_item = experiment_items[0]
    assert isinstance(exp_item, experiment_item.ExperimentItemReferences)
    assert exp_item.dataset_item_id == dataset_item_id
    assert exp_item.trace_id == trace.id
    assert exp_item.project_name == test_project_name


def test_evaluate_llm_task_context__experiment_item_includes_none_project_name():
    """
    Verify that when trace has no project_name (None),
    the ExperimentItemReferences also has None for project_name.
    """
    # Setup
    dataset_item_id = "dataset-item-456"

    trace = trace_data.TraceData(
        name="test-trace",
        project_name=None,  # No project name
    )

    # Create mock experiment
    mock_experiment = mock.Mock()
    mock_experiment.insert = mock.Mock()

    # Create mock client
    mock_client = mock.Mock(spec=opik.Opik)

    # Execute the context manager
    with helpers.evaluate_llm_task_context(
        experiment=mock_experiment,
        dataset_item_id=dataset_item_id,
        trace_data=trace,
        client=mock_client,
    ):
        pass

    # Verify experiment.insert was called
    mock_experiment.insert.assert_called_once()

    # Get the experiment items
    call_args = mock_experiment.insert.call_args
    experiment_items = call_args.kwargs["experiment_items_references"]

    # Verify project_name is None
    assert len(experiment_items) == 1
    exp_item = experiment_items[0]
    assert exp_item.project_name is None


def test_experiment_item_message__includes_project_name():
    """
    Verify that ExperimentItemMessage correctly stores project_name
    when created from ExperimentItemReferences.
    """
    # Create ExperimentItemReferences with project_name
    item_ref = experiment_item.ExperimentItemReferences(
        dataset_item_id="dataset-789",
        trace_id="trace-101",
        project_name="my-project",
    )

    # Create ExperimentItemMessage (simulating what experiment.insert() does)
    msg = messages.ExperimentItemMessage(
        id="exp-item-999",
        experiment_id="exp-888",
        dataset_item_id=item_ref.dataset_item_id,
        trace_id=item_ref.trace_id,
        project_name=item_ref.project_name,
    )

    # Verify all fields are correctly set
    assert msg.id == "exp-item-999"
    assert msg.experiment_id == "exp-888"
    assert msg.dataset_item_id == "dataset-789"
    assert msg.trace_id == "trace-101"
    assert msg.project_name == "my-project"


def test_experiment_item_message__project_name_optional():
    """
    Verify that ExperimentItemMessage works without project_name (backward compatibility).
    """
    # Create ExperimentItemReferences without project_name
    item_ref = experiment_item.ExperimentItemReferences(
        dataset_item_id="dataset-222",
        trace_id="trace-333",
    )

    # Create ExperimentItemMessage without project_name
    msg = messages.ExperimentItemMessage(
        id="exp-item-444",
        experiment_id="exp-555",
        dataset_item_id=item_ref.dataset_item_id,
        trace_id=item_ref.trace_id,
    )

    # Verify project_name defaults to None
    assert msg.project_name is None


def test_trace_data__has_project_name_field():
    """
    Verify that TraceData has project_name field (inherited from ObservationData).
    """
    trace = trace_data.TraceData(
        name="test-trace",
        project_name="test-project-123",
    )

    assert trace.name == "test-trace"
    assert trace.project_name == "test-project-123"
    assert hasattr(trace, "project_name")


def test_trace_data__project_name_in_as_start_parameters():
    """
    Verify that project_name is included in trace start parameters.
    """
    trace = trace_data.TraceData(
        name="test-trace",
        project_name="start-params-project",
    )

    start_params = trace.as_start_parameters

    assert "project_name" in start_params
    assert start_params["project_name"] == "start-params-project"


def test_trace_data__project_name_used_in_child_span():
    """
    Verify that when creating a child span, the trace's project_name is passed.
    """
    trace = trace_data.TraceData(
        name="parent-trace",
        project_name="parent-project",
    )

    child_span = trace.create_child_span_data(
        name="child-span",
    )

    # Verify the child span has the same project_name as the parent trace
    assert child_span.project_name == "parent-project"
