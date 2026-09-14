from unittest.mock import Mock, patch

from opik.api_objects.dataset import rest_operations
from opik.rest_api.types import dataset_item as rest_dataset_item


def _find_datasets_returning(*pages) -> Mock:
    """A rest client whose find_datasets yields the given pages, then an empty one.

    The pagination loop only stops on an empty page, so the trailing empty page
    is what keeps these tests from spinning forever.
    """
    mock_rest_client = Mock()
    mock_rest_client.datasets.find_datasets.side_effect = [
        Mock(content=list(page)) for page in (*pages, ())
    ]
    return mock_rest_client


def _backend_dataset(name: str, type_: str, items_total: int) -> Mock:
    dataset_fern = Mock()
    dataset_fern.configure_mock(
        name=name,
        description="",
        type=type_,
        dataset_items_count=items_total,
    )
    return dataset_fern


def test_get_test_suites__insert_duplicates_existing_item__duplicate_not_submitted():
    """A listed suite must re-read its backend items before deduplicating.

    Otherwise the first insert compares against an empty local hash set,
    decides every item is new, and resubmits items the suite already holds.
    """
    existing_content = {"question": "already in the suite"}
    mock_rest_client = _find_datasets_returning(
        [_backend_dataset("my-suite", "evaluation_suite", items_total=1)]
    )

    suites = rest_operations.get_test_suites(
        project_name="Test project",
        rest_client=mock_rest_client,
    )
    assert len(suites) == 1

    backend_item = rest_dataset_item.DatasetItem(
        id="existing-item-id", source="sdk", data=existing_content
    )
    with patch(
        "opik.api_objects.dataset.rest_operations.rest_stream_parser.read_and_parse_stream",
        side_effect=[[backend_item], []],
    ):
        suites[0].insert(
            [
                {"data": existing_content},
                {"data": {"question": "brand new"}},
            ]
        )

    create_or_update = mock_rest_client.datasets.create_or_update_dataset_items
    submitted = [
        item
        for call in create_or_update.call_args_list
        for item in call.kwargs["items"]
    ]

    assert [item.data for item in submitted] == [{"question": "brand new"}], (
        "The item the suite already holds must be recognised as a duplicate and "
        "left out of the batch"
    )


def test_get_test_suites__non_suite_datasets__are_skipped():
    mock_rest_client = _find_datasets_returning(
        [
            _backend_dataset("plain-dataset", "dataset", items_total=3),
            _backend_dataset("my-suite", "evaluation_suite", items_total=3),
        ]
    )

    suites = rest_operations.get_test_suites(
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    assert [suite.name for suite in suites] == ["my-suite"]
