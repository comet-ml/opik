"""Unit tests for stream_dataset_items() in rest_operations."""

from unittest.mock import Mock, patch

from opik.api_objects.dataset import rest_operations
from opik.rest_api.types import dataset_item as rest_dataset_item


def _make_rest_item(item_id: str, data: dict) -> rest_dataset_item.DatasetItem:
    return rest_dataset_item.DatasetItem(
        id=item_id,
        source="sdk",
        data=data,
    )


def test_stream_dataset_items__colliding_id_key__uses_real_id_and_warns(caplog):
    real_id = "real-uuid-1234"
    rest_item = _make_rest_item(real_id, {"id": "COLLISION", "question": "What?"})

    mock_rest_client = Mock()

    with patch(
        "opik.api_objects.dataset.rest_operations.rest_stream_parser.read_and_parse_stream",
        side_effect=[[rest_item], []],
    ):
        items = list(
            rest_operations.stream_dataset_items(
                rest_client=mock_rest_client,
                dataset_name="test-dataset",
                project_name=None,
            )
        )

    assert len(items) == 1
    assert items[0].id == real_id
    assert "COLLISION" not in vars(items[0]).get("id", "")

    warning_records = [
        r for r in caplog.records if r.levelname == "WARNING" and "shadow" in r.message
    ]
    assert len(warning_records) == 1
    assert "['id']" in warning_records[0].message


def test_stream_dataset_items__colliding_id_key__warning_emitted_only_once(caplog):
    """Warning is logged once per stream even when multiple items have the collision."""
    items_data = [
        _make_rest_item(f"uuid-{i}", {"id": f"hotpot-{i}", "question": "Q?"})
        for i in range(3)
    ]

    mock_rest_client = Mock()

    with patch(
        "opik.api_objects.dataset.rest_operations.rest_stream_parser.read_and_parse_stream",
        side_effect=[items_data, []],
    ):
        result = list(
            rest_operations.stream_dataset_items(
                rest_client=mock_rest_client,
                dataset_name="test-dataset",
                project_name=None,
            )
        )

    assert len(result) == 3
    warning_records = [
        r for r in caplog.records if r.levelname == "WARNING" and "shadow" in r.message
    ]
    assert len(warning_records) == 1
