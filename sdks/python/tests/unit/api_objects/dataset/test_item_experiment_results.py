"""The per-item history methods on Dataset and TestSuite."""

import uuid
from typing import Any, Callable
from unittest import mock

import pytest

from opik import exceptions
from opik.api_objects.dataset import dataset
from opik.api_objects.dataset.test_suite import test_suite
from opik.api_objects.experiment import experiments_client

ITEM_ID = "01a20c8b-6f0c-7649-9945-393585ae103c"


def _dataset() -> dataset.Dataset:
    return dataset.Dataset(
        name="my-dataset",
        description=None,
        project_name="my-project",
        rest_client=mock.Mock(),
    )


def _dataset_read(**kwargs: Any) -> Any:
    return _dataset().get_item_experiment_results(**kwargs)


def _suite_read(**kwargs: Any) -> Any:
    suite = test_suite.TestSuite(name="my-dataset", dataset_=_dataset())
    return suite.get_item_experiment_results(**kwargs)


BOTH_METHODS = pytest.mark.parametrize(
    "read", [_dataset_read, _suite_read], ids=["dataset", "test_suite"]
)


@BOTH_METHODS
def test_get_item_experiment_results__happyflow__delegates_to_the_experiments_client(
    read: Callable[..., Any],
):
    expected = [object()]
    with mock.patch.object(
        experiments_client.ExperimentsClient,
        "find_experiment_items_for_dataset",
        return_value=expected,
    ) as find:
        results = read(
            item_id=ITEM_ID,
            dataset_versions=["b888a6df", "baseline"],
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
def test_get_item_experiment_results__defaults__every_version_and_1000_results(
    read: Callable[..., Any],
):
    with mock.patch.object(
        experiments_client.ExperimentsClient,
        "find_experiment_items_for_dataset",
        return_value=[],
    ) as find:
        read(item_id=ITEM_ID)

    assert find.call_args.kwargs["dataset_versions"] is None
    assert find.call_args.kwargs["max_results"] == 1000


@BOTH_METHODS
def test_get_item_experiment_results__uuid_in_another_form__sent_in_canonical_form(
    read: Callable[..., Any],
):
    with mock.patch.object(
        experiments_client.ExperimentsClient,
        "find_experiment_items_for_dataset",
        return_value=[],
    ) as find:
        read(item_id="{" + ITEM_ID.upper() + "}")

    assert find.call_args.kwargs["filter_string"] == f'id = "{ITEM_ID}"'


@BOTH_METHODS
@pytest.mark.parametrize(
    "item_id",
    ["not-a-uuid", f'{ITEM_ID}" or id != "x', "", None, 123],
)
def test_get_item_experiment_results__item_id_not_a_uuid__raises_value_error_without_reading(
    read: Callable[..., Any], item_id: Any
):
    with mock.patch.object(
        experiments_client.ExperimentsClient, "find_experiment_items_for_dataset"
    ) as find:
        with pytest.raises(ValueError, match="must be a UUID"):
            read(item_id=item_id)

    find.assert_not_called()


@BOTH_METHODS
def test_get_item_experiment_results__unknown_version__dataset_version_not_found_propagates(
    read: Callable[..., Any],
):
    with mock.patch.object(
        experiments_client.ExperimentsClient,
        "find_experiment_items_for_dataset",
        side_effect=exceptions.DatasetVersionNotFound("Version not found"),
    ):
        with pytest.raises(exceptions.DatasetVersionNotFound):
            read(item_id=str(uuid.uuid4()), dataset_versions=["v9"])
