"""Shared fixtures for evaluation unit tests.

Each fixture does exactly one thing and names itself after what it patches or
builds. Tests opt in by listing the fixtures they need in their signature —
what a test depends on is visible at a glance.
"""

from typing import Any, List, Optional
from unittest import mock

import pytest

from opik import url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item


@pytest.fixture(autouse=True)
def _isolate_from_real_backend(fake_backend):
    """Route every evaluation unit test through the in-memory backend emulator.

    Many evaluation paths instantiate tracked metrics (``track=True`` by
    default), which install an ``opik.track`` decorator that produces traces
    via the global streamer. Without this fixture, tests that never opt into
    ``fake_backend`` would build a real HTTP streamer and spam the test output
    with 401s when the pipeline tries to push to a non-existent backend.
    """
    return fake_backend


@pytest.fixture
def mock_create_experiment():
    """Patches ``Opik.create_experiment`` and returns the mock.

    Inspect ``mock_create_experiment.call_args.kwargs`` to see what the SDK
    sent to the backend.
    """
    experiment = mock.Mock()
    experiment.id = "exp-test"
    experiment.name = "exp-test"
    with mock.patch.object(
        opik_client.Opik, "create_experiment", return_value=experiment
    ) as mocked:
        yield mocked


@pytest.fixture
def mock_experiment_url():
    """Patches ``url_helpers.get_experiment_url_by_id`` so the evaluator's
    URL rendering doesn't hit the backend. Tests rarely need to inspect this."""
    with mock.patch.object(
        url_helpers, "get_experiment_url_by_id", return_value="any_url"
    ):
        yield


@pytest.fixture
def make_dataset_item():
    """Factory fixture: call ``make_dataset_item(id, input, reference=..., execution_policy=...)``
    to build a DatasetItem, optionally with a per-item execution policy."""

    def _make(
        id_: str,
        input_: Any,
        *,
        reference: Any = None,
        execution_policy: Optional[dict] = None,
    ) -> dataset_item.DatasetItem:
        kwargs: dict = {"id": id_, "input": input_}
        if reference is not None:
            kwargs["reference"] = reference
        if execution_policy is not None:
            kwargs["execution_policy"] = dataset_item.ExecutionPolicyItem(
                **execution_policy
            )
        return dataset_item.DatasetItem(**kwargs)

    return _make


@pytest.fixture
def make_dataset():
    """Factory fixture: call ``make_dataset(items=[...], execution_policy=...)``
    inside a test to get a mock Dataset wired for ``opik.evaluate()`` /
    ``opik.run_tests()``."""

    def _make(
        *,
        name: str = "test-dataset",
        items: Optional[List[dataset_item.DatasetItem]] = None,
        execution_policy: Optional[dict] = None,
    ) -> mock.MagicMock:
        m = mock.MagicMock()
        m.name = name
        m.id = "dataset-id"
        m.project_name = None
        m.dataset_items_count = len(items) if items else 0
        m.get_version_info.return_value = None
        m.get_execution_policy.return_value = execution_policy or {
            "runs_per_item": 1,
            "pass_threshold": 1,
        }
        m.get_evaluators.return_value = []
        # MagicMock flags __x__ names as magic methods and refuses auto-access;
        # assign the streaming method explicitly instead.
        m.__internal_api__stream_items_as_dataclasses__ = mock.MagicMock(
            return_value=iter(items or [])
        )
        m.client = None
        return m

    return _make
