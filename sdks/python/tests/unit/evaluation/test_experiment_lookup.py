"""Unit tests for Opik.get_experiment_by_id / get_experiment_by_name error mapping.

These replace e2e tests that previously spun up a real backend just to assert
that a missing experiment surfaces as ``ExperimentNotFound``. The mapping is
pure SDK logic — unit tests exercise it in milliseconds and pin the exception
type precisely, instead of relying on a live 404 response.
"""

from unittest import mock

import pytest

from opik import exceptions
from opik.api_objects import opik_client
from opik.rest_api.core.api_error import ApiError


def test_get_experiment_by_id__rest_returns_404__raises_ExperimentNotFound():
    client = opik_client.Opik()
    with mock.patch.object(
        client._rest_client.experiments,
        "get_experiment_by_id",
        side_effect=ApiError(status_code=404, body=None),
    ):
        with pytest.raises(exceptions.ExperimentNotFound):
            client.get_experiment_by_id("not-existing-id")


def test_get_experiment_by_id__rest_returns_500__propagates_original_error():
    """Non-404 REST errors must propagate as-is so callers can distinguish."""
    client = opik_client.Opik()
    with mock.patch.object(
        client._rest_client.experiments,
        "get_experiment_by_id",
        side_effect=ApiError(status_code=500, body="internal error"),
    ):
        with pytest.raises(ApiError) as exc_info:
            client.get_experiment_by_id("any-id")
        assert exc_info.value.status_code == 500


def test_get_experiment_by_name__no_matches__raises_ExperimentNotFound():
    """The deprecated get_experiment_by_name turns an empty stream into
    ExperimentNotFound."""
    client = opik_client.Opik()
    with mock.patch(
        "opik.api_objects.experiment.rest_operations.get_experiments_data_by_name",
        return_value=[],
    ):
        with pytest.raises(exceptions.ExperimentNotFound):
            client.get_experiment_by_name("not-existing-name")
