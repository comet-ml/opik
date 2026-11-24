"""The public per-item history methods on the Opik client."""

import uuid
from typing import Any, Callable
from unittest import mock

import pytest

from opik import exceptions
from opik.api_objects import opik_client
from opik.api_objects.experiment import experiments_client

ITEM_ID = "01a20c8b-6f0c-7649-9945-393585ae103c"


def _client() -> opik_client.Opik:
    return opik_client.Opik(
        project_name="default-project", _show_misconfiguration_message=False
    )


def _dataset_read(client: opik_client.Opik, **kwargs: Any) -> Any:
    return client.get_dataset_item_experiment_results(
        dataset_name="my-dataset", **kwargs
    )


def _suite_read(client: opik_client.Opik, **kwargs: Any) -> Any:
    item_id = kwargs.pop("dataset_item_id")
    return client.get_test_suite_item_experiment_results(
        name="my-dataset", item_id=item_id, **kwargs
    )


BOTH_METHODS = pytest.mark.parametrize(
    "read", [_dataset_read, _suite_read], ids=["dataset", "test_suite"]
)


@BOTH_METHODS
def test_item_experiment_results__happyflow__delegates_to_the_experiments_client(
    read: Callable[..., Any],
):
    client = _client()
    expected = [object()]
    with mock.patch.object(
        experiments_client.ExperimentsClient,
        "find_experiment_items_for_dataset",
        return_value=expected,
    ) as find:
        results = read(
            client,
            dataset_item_id=ITEM_ID,
            dataset_versions=["b888a6df", "baseline"],
            project_name="my-project",
            max_results=50,
        )

    assert results is expected
    find.assert_called_once_with(
        dataset_name="my-dataset",
        filter_string=f'id = "{ITEM_ID}"',
        project_name="my-project",
        max_results=50,
        dataset_versions=["b888a6df", "baseline"],
    )


@BOTH_METHODS
def test_item_experiment_results__no_project_name__uses_the_clients_project(
    read: Callable[..., Any],
):
    client = _client()
    with mock.patch.object(
        experiments_client.ExperimentsClient,
        "find_experiment_items_for_dataset",
        return_value=[],
    ) as find:
        read(client, dataset_item_id=ITEM_ID)

    assert find.call_args.kwargs["project_name"] == "default-project"
    assert find.call_args.kwargs["dataset_versions"] is None
    assert find.call_args.kwargs["max_results"] == 1000


@BOTH_METHODS
def test_item_experiment_results__uuid_in_another_form__sent_in_canonical_form(
    read: Callable[..., Any],
):
    client = _client()
    with mock.patch.object(
        experiments_client.ExperimentsClient,
        "find_experiment_items_for_dataset",
        return_value=[],
    ) as find:
        read(client, dataset_item_id="{" + ITEM_ID.upper() + "}")

    assert find.call_args.kwargs["filter_string"] == f'id = "{ITEM_ID}"'


@BOTH_METHODS
@pytest.mark.parametrize(
    "item_id",
    ["not-a-uuid", f'{ITEM_ID}" or id != "x', "", None, 123],
)
def test_item_experiment_results__item_id_not_a_uuid__raises_value_error_without_reading(
    read: Callable[..., Any], item_id: Any
):
    client = _client()
    with mock.patch.object(
        experiments_client.ExperimentsClient, "find_experiment_items_for_dataset"
    ) as find:
        with pytest.raises(ValueError, match="must be a UUID"):
            read(client, dataset_item_id=item_id)

    find.assert_not_called()


@BOTH_METHODS
def test_item_experiment_results__unknown_version__dataset_version_not_found_propagates(
    read: Callable[..., Any],
):
    client = _client()
    with mock.patch.object(
        experiments_client.ExperimentsClient,
        "find_experiment_items_for_dataset",
        side_effect=exceptions.DatasetVersionNotFound("Version not found"),
    ):
        with pytest.raises(exceptions.DatasetVersionNotFound):
            read(client, dataset_item_id=str(uuid.uuid4()), dataset_versions=["v9"])
