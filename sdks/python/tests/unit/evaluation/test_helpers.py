import logging
from types import SimpleNamespace
from unittest import mock

from opik.api_objects.dataset import dataset_item
from opik.evaluation import helpers


class TestResolveProjectName:
    def test_dataset_has_no_project__user_value_used(self, capture_log):
        resolved = helpers.resolve_project_name(
            value_from_dataset=None,
            value_from_user="caller-project",
            caller_name="evaluate",
        )

        assert resolved == "caller-project"
        assert capture_log.records == []

    def test_dataset_has_no_project__user_none__returns_none(self, capture_log):
        resolved = helpers.resolve_project_name(
            value_from_dataset=None,
            value_from_user=None,
            caller_name="evaluate",
        )

        assert resolved is None
        assert capture_log.records == []

    def test_dataset_has_project__user_none__returns_dataset_project__no_warning(
        self, capture_log
    ):
        resolved = helpers.resolve_project_name(
            value_from_dataset="dataset-project",
            value_from_user=None,
            caller_name="evaluate",
        )

        assert resolved == "dataset-project"
        assert capture_log.records == []

    def test_dataset_has_project__user_override__dataset_wins_and_warning_logged(
        self, capture_log
    ):
        resolved = helpers.resolve_project_name(
            value_from_dataset="dataset-project",
            value_from_user="caller-project",
            caller_name="evaluate_prompt",
        )

        assert resolved == "dataset-project"
        warning_records = [
            record
            for record in capture_log.records
            if record.levelno == logging.WARNING
        ]
        assert len(warning_records) == 1
        message = warning_records[0].getMessage()
        assert "deprecated" in message
        assert "evaluate_prompt()" in message
        assert "dataset-project" in message
        assert "caller-project" in message


class TestCalculateTotalItems:
    def test_dataset_item_ids_provided__returns_len_of_ids(self):
        dataset_ = SimpleNamespace(dataset_items_count=100)

        assert (
            helpers._calculate_total_items(
                dataset_=dataset_,
                nb_samples=None,
                dataset_item_ids=["a", "b", "c"],
            )
            == 3
        )

    def test_nb_samples_only__capped_by_dataset_count(self):
        dataset_ = SimpleNamespace(dataset_items_count=5)

        assert (
            helpers._calculate_total_items(
                dataset_=dataset_, nb_samples=10, dataset_item_ids=None
            )
            == 5
        )

    def test_nb_samples_only__below_dataset_count(self):
        dataset_ = SimpleNamespace(dataset_items_count=100)

        assert (
            helpers._calculate_total_items(
                dataset_=dataset_, nb_samples=10, dataset_item_ids=None
            )
            == 10
        )

    def test_nb_samples_only__dataset_count_none__returns_nb_samples(self):
        dataset_ = SimpleNamespace(dataset_items_count=None)

        assert (
            helpers._calculate_total_items(
                dataset_=dataset_, nb_samples=7, dataset_item_ids=None
            )
            == 7
        )

    def test_neither_provided__returns_dataset_count(self):
        dataset_ = SimpleNamespace(dataset_items_count=42)

        assert (
            helpers._calculate_total_items(
                dataset_=dataset_, nb_samples=None, dataset_item_ids=None
            )
            == 42
        )


class TestResolveDatasetItemsGenerator:
    @staticmethod
    def _make_dataset(items, items_count=None):
        dataset_ = SimpleNamespace(dataset_items_count=items_count)
        dataset_.__internal_api__stream_items_as_dataclasses__ = mock.MagicMock(
            return_value=iter(items)
        )
        return dataset_

    def test_no_sampler__returns_streamed_iterator_and_total(self):
        items = [dataset_item.DatasetItem(id=f"i-{i}") for i in range(3)]
        dataset_ = self._make_dataset(items, items_count=10)

        items_iter, total = helpers.resolve_dataset_items_generator(
            dataset_=dataset_,
            nb_samples=None,
            dataset_item_ids=None,
            dataset_sampler=None,
            dataset_filter_string=None,
        )

        assert list(items_iter) == items
        assert total == 10
        dataset_.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
            nb_samples=None,
            dataset_item_ids=None,
            batch_size=helpers.EVALUATION_STREAM_DATASET_BATCH_SIZE,
            filter_string=None,
        )

    def test_no_sampler__nb_samples_and_filter_forwarded(self):
        items = [dataset_item.DatasetItem(id="i-0")]
        dataset_ = self._make_dataset(items, items_count=100)

        _, total = helpers.resolve_dataset_items_generator(
            dataset_=dataset_,
            nb_samples=2,
            dataset_item_ids=None,
            dataset_sampler=None,
            dataset_filter_string='tags contains "x"',
        )

        assert total == 2
        dataset_.__internal_api__stream_items_as_dataclasses__.assert_called_once_with(
            nb_samples=2,
            dataset_item_ids=None,
            batch_size=helpers.EVALUATION_STREAM_DATASET_BATCH_SIZE,
            filter_string='tags contains "x"',
        )

    def test_with_sampler__materialises_list_and_returns_sampled_count(self):
        items = [dataset_item.DatasetItem(id=f"i-{i}") for i in range(4)]
        dataset_ = self._make_dataset(items)
        sampled = items[:2]
        sampler = SimpleNamespace(sample=lambda xs: sampled)

        items_iter, total = helpers.resolve_dataset_items_generator(
            dataset_=dataset_,
            nb_samples=None,
            dataset_item_ids=None,
            dataset_sampler=sampler,
            dataset_filter_string=None,
        )

        assert list(items_iter) == sampled
        assert total == len(sampled)


class TestMergeBlueprintIntoConfig:
    @staticmethod
    def _make_blueprint(id, name):
        bp = mock.MagicMock()
        bp.id = id
        bp.name = name
        return bp

    def test_blueprint_fetched_and_version_stored(self):
        mock_client = mock.Mock()
        mock_client._rest_client.agent_configs.get_blueprint_by_id.return_value = (
            self._make_blueprint("bp-123", "v9")
        )

        result = helpers.merge_blueprint_into_config(
            mock_client,
            "bp-123",
            {"model": "gpt-4o"},
        )

        assert result["model"] == "gpt-4o"
        assert result["agent_configuration"] == {
            "_blueprint_id": "bp-123",
            "blueprint_version": "v9",
        }

    def test_blueprint_fetch_fails_still_stores_id(self):
        mock_client = mock.Mock()
        mock_client._rest_client.agent_configs.get_blueprint_by_id.side_effect = (
            Exception("not found")
        )

        result = helpers.merge_blueprint_into_config(
            mock_client,
            "bp-456",
            None,
        )

        assert result["agent_configuration"] == {"_blueprint_id": "bp-456"}
