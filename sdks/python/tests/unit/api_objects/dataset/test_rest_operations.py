from unittest.mock import Mock

from opik.api_objects.dataset import rest_operations


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


def test_get_test_suites__suite_holds_backend_items__first_insert_syncs_hashes():
    """A listed suite must not treat its empty local hash set as authoritative.

    Otherwise the first deduplicated insert compares against nothing, decides
    every item is new, and resubmits items the suite already holds.
    """
    mock_rest_client = _find_datasets_returning(
        [_backend_dataset("my-suite", "evaluation_suite", items_total=25)]
    )

    suites = rest_operations.get_test_suites(
        project_name="Test project",
        rest_client=mock_rest_client,
    )

    assert len(suites) == 1
    assert not suites[0]._dataset.__internal_api__hashes_synced__, (
        "A suite listed from the backend has items we have not hashed locally, "
        "so the first insert must sync before deduplicating"
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
