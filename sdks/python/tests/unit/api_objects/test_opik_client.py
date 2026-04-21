import json
from typing import List

import pytest
from unittest.mock import patch, MagicMock, Mock

from opik.api_objects import opik_client
from opik.api_objects.dataset import Dataset
from opik.api_objects.dataset.test_suite import TestSuite
from opik.api_objects import prompt as prompt_module
from opik.api_objects.prompt import client as prompt_client_module
from opik.message_processing import messages
from opik.types import BatchFeedbackScoreDict


@pytest.mark.parametrize(
    "trace_id,project_name",
    [
        (None, "some-project"),
        ("some-trace-id", None),
        (None, None),
        ("", "some-project"),
        ("some-trace-id", ""),
        ("", ""),
    ],
)
def test_opik_client__update_trace__missing_mandatory_parameters__error_raised(
    trace_id, project_name
):
    opik_client_ = opik_client.Opik()

    with pytest.raises(ValueError):
        opik_client_.update_trace(trace_id=trace_id, project_name=project_name)


def test_opik_client__update_experiment__both_name_and_config__both_sent_to_api():
    opik_client_ = opik_client.Opik()

    with patch.object(
        opik_client_._rest_client.experiments, "update_experiment"
    ) as mock_update:
        new_config = {"model": "gpt-4", "temperature": 0.7}
        opik_client_.update_experiment(
            id="some-experiment-id", name="new-name", experiment_config=new_config
        )

        mock_update.assert_called_once()
        call_kwargs = mock_update.call_args[1]
        assert call_kwargs["name"] == "new-name"
        assert call_kwargs["metadata"] == new_config


def test_opik_client__update_experiment__name_only__only_name_sent_to_api():
    opik_client_ = opik_client.Opik()

    with patch.object(
        opik_client_._rest_client.experiments, "update_experiment"
    ) as mock_update:
        opik_client_.update_experiment(id="some-experiment-id", name="new-name")

        mock_update.assert_called_once()
        call_kwargs = mock_update.call_args[1]
        assert call_kwargs["name"] == "new-name"
        assert "metadata" not in call_kwargs


def test_opik_client__update_experiment__config_only__only_metadata_sent_to_api():
    opik_client_ = opik_client.Opik()

    with patch.object(
        opik_client_._rest_client.experiments, "update_experiment"
    ) as mock_update:
        new_config = {"model": "gpt-4", "temperature": 0.7}
        opik_client_.update_experiment(
            id="some-experiment-id", experiment_config=new_config
        )

        mock_update.assert_called_once()
        call_kwargs = mock_update.call_args[1]
        assert "name" not in call_kwargs
        assert call_kwargs["metadata"] == new_config


@pytest.mark.parametrize(
    "experiment_id",
    [
        None,
        "",
    ],
)
def test_opik_client__update_experiment__missing_mandatory_parameters__error_raised(
    experiment_id,
):
    opik_client_ = opik_client.Opik()

    with pytest.raises(ValueError):
        opik_client_.update_experiment(id=experiment_id)


def test_opik_client__update_experiment__no_update_parameters__error_raised():
    opik_client_ = opik_client.Opik()

    with pytest.raises(ValueError):
        opik_client_.update_experiment(id="some-experiment-id")


def test_opik_client__log_spans_feedback_scores__happy_path():
    """Test log_spans_feedback_scores with valid scores."""
    opik_client_ = opik_client.Opik(project_name="test-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {
            "id": "span-1",
            "name": "quality",
            "value": 0.85,
            "reason": "Good quality",
        },
        {
            "id": "span-2",
            "name": "latency",
            "value": 0.75,
            "category_name": "performance",
        },
    ]

    # Mock parse_feedback_score_messages to return valid messages
    mock_score_messages = [
        messages.FeedbackScoreMessage(
            id="span-1",
            project_name="test-project",
            name="quality",
            value=0.85,
            source="sdk",
            reason="Good quality",
        ),
        messages.FeedbackScoreMessage(
            id="span-2",
            project_name="test-project",
            name="latency",
            value=0.75,
            source="sdk",
            category_name="performance",
        ),
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch(
            "opik.message_processing.batching.sequence_splitter.split_into_batches"
        ) as mock_batch,
    ):
        mock_parse.return_value = mock_score_messages
        mock_batch.return_value = [mock_score_messages]  # Single batch

        opik_client_.log_spans_feedback_scores(scores=scores)

        # Verify parse_feedback_score_messages was called correctly
        mock_parse.assert_called_once()
        call_kwargs = mock_parse.call_args[1]
        assert call_kwargs["scores"] == scores
        assert call_kwargs["project_name"] == "test-project"
        assert call_kwargs["parsed_item_class"] == messages.FeedbackScoreMessage

        # Verify batching was called
        mock_batch.assert_called_once()
        batch_call_args = mock_batch.call_args[0]
        assert batch_call_args[0] == mock_score_messages

        # Verify streamer.put was called with AddSpanFeedbackScoresBatchMessage
        mock_streamer.put.assert_called_once()
        put_call_arg = mock_streamer.put.call_args[0][0]
        assert isinstance(put_call_arg, messages.AddSpanFeedbackScoresBatchMessage)
        assert put_call_arg.batch == mock_score_messages


def test_opik_client__log_spans_feedback_scores__with_explicit_project_name():
    """Test log_spans_feedback_scores with explicit project_name parameter."""
    opik_client_ = opik_client.Opik(project_name="default-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {"id": "span-1", "name": "quality", "value": 0.85}
    ]

    mock_score_messages = [
        messages.FeedbackScoreMessage(
            id="span-1",
            project_name="explicit-project",
            name="quality",
            value=0.85,
            source="sdk",
        )
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch(
            "opik.message_processing.batching.sequence_splitter.split_into_batches"
        ) as mock_batch,
    ):
        mock_parse.return_value = mock_score_messages
        mock_batch.return_value = [mock_score_messages]

        opik_client_.log_spans_feedback_scores(
            scores=scores, project_name="explicit-project"
        )

        # Verify project_name parameter was used
        call_kwargs = mock_parse.call_args[1]
        assert call_kwargs["project_name"] == "explicit-project"


def test_opik_client__log_spans_feedback_scores__no_valid_scores():
    """Test log_spans_feedback_scores when no valid scores are provided."""
    opik_client_ = opik_client.Opik(project_name="test-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {"id": "span-1", "name": "quality"}  # Missing required 'value' field
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch("opik.api_objects.opik_client.LOGGER") as mock_logger,
    ):
        mock_parse.return_value = None  # No valid scores

        opik_client_.log_spans_feedback_scores(scores=scores)

        # Verify error was logged
        mock_logger.error.assert_called_once()
        error_message = mock_logger.error.call_args[0][0]
        assert "No valid spans feedback scores" in error_message
        assert str(scores) in error_message

        # Verify streamer.put was NOT called
        mock_streamer.put.assert_not_called()


def test_opik_client__log_traces_feedback_scores__happy_path():
    """Test log_traces_feedback_scores with valid scores."""
    opik_client_ = opik_client.Opik(project_name="test-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {
            "id": "trace-1",
            "name": "accuracy",
            "value": 0.92,
            "reason": "High accuracy",
        }
    ]

    mock_score_messages = [
        messages.FeedbackScoreMessage(
            id="trace-1",
            project_name="test-project",
            name="accuracy",
            value=0.92,
            source="sdk",
            reason="High accuracy",
        )
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch(
            "opik.message_processing.batching.sequence_splitter.split_into_batches"
        ) as mock_batch,
    ):
        mock_parse.return_value = mock_score_messages
        mock_batch.return_value = [mock_score_messages]

        opik_client_.log_traces_feedback_scores(scores=scores)

        # Verify parse_feedback_score_messages was called correctly
        mock_parse.assert_called_once()
        call_kwargs = mock_parse.call_args[1]
        assert call_kwargs["scores"] == scores
        assert call_kwargs["project_name"] == "test-project"
        assert call_kwargs["parsed_item_class"] == messages.FeedbackScoreMessage

        # Verify streamer.put was called with AddTraceFeedbackScoresBatchMessage
        mock_streamer.put.assert_called_once()
        put_call_arg = mock_streamer.put.call_args[0][0]
        assert isinstance(put_call_arg, messages.AddTraceFeedbackScoresBatchMessage)
        assert put_call_arg.batch == mock_score_messages


def test_opik_client__log_traces_feedback_scores__with_explicit_project_name():
    """Test log_traces_feedback_scores with explicit project_name parameter."""
    opik_client_ = opik_client.Opik(project_name="default-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {"id": "trace-1", "name": "accuracy", "value": 0.92}
    ]

    mock_score_messages = [
        messages.FeedbackScoreMessage(
            id="trace-1",
            project_name="explicit-project",
            name="accuracy",
            value=0.92,
            source="sdk",
        )
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch(
            "opik.message_processing.batching.sequence_splitter.split_into_batches"
        ) as mock_batch,
    ):
        mock_parse.return_value = mock_score_messages
        mock_batch.return_value = [mock_score_messages]

        opik_client_.log_traces_feedback_scores(
            scores=scores, project_name="explicit-project"
        )

        # Verify project_name parameter was used
        call_kwargs = mock_parse.call_args[1]
        assert call_kwargs["project_name"] == "explicit-project"


def test_opik_client__log_traces_feedback_scores__no_valid_scores():
    """Test log_traces_feedback_scores when no valid scores are provided."""
    opik_client_ = opik_client.Opik(project_name="test-project")
    mock_streamer = MagicMock()
    opik_client_._streamer = mock_streamer

    scores: List[BatchFeedbackScoreDict] = [
        {"id": "trace-1", "name": "accuracy"}  # Missing required 'value' field
    ]

    with (
        patch("opik.api_objects.helpers.parse_feedback_score_messages") as mock_parse,
        patch("opik.api_objects.opik_client.LOGGER") as mock_logger,
    ):
        mock_parse.return_value = None  # No valid scores

        opik_client_.log_traces_feedback_scores(scores=scores)

        # Verify error was logged
        mock_logger.error.assert_called_once()
        error_message = mock_logger.error.call_args[0][0]
        assert "No valid traces feedback scores" in error_message
        assert str(scores) in error_message

        # Verify streamer.put was NOT called
        mock_streamer.put.assert_not_called()


class TestOpikClientCreateDataset:
    """Tests for Opik.create_dataset() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_rest_datasets = self.opik_client_._rest_client.datasets

        with (
            patch.object(self.mock_rest_datasets, "create_dataset") as self.mock_create,
            patch.object(
                self.mock_rest_datasets,
                "get_dataset_by_identifier",
                return_value=Mock(id="some-dataset-id"),
            ) as self.mock_get_by_identifier,
            patch.object(
                self.opik_client_, "_display_created_dataset_url"
            ) as self.mock_display_url,
        ):
            yield

    def test_create_dataset__no_project_name__uses_default_project(self):
        """Verify create_dataset uses the client's default project when project_name is None."""
        result = self.opik_client_.create_dataset(name="my-dataset")

        self.mock_create.assert_called_once_with(
            name="my-dataset",
            description=None,
            project_name="default-project",
        )
        assert isinstance(result, Dataset)
        assert result.name == "my-dataset"
        assert result.description is None
        assert result.project_name == "default-project"

    def test_create_dataset__explicit_project_name__uses_given_project(self):
        """Verify create_dataset uses the provided project_name over the default."""
        result = self.opik_client_.create_dataset(
            name="my-dataset", project_name="custom-project"
        )

        self.mock_create.assert_called_once_with(
            name="my-dataset",
            description=None,
            project_name="custom-project",
        )
        assert isinstance(result, Dataset)
        assert result.name == "my-dataset"
        assert result.description is None
        assert result.project_name == "custom-project"

    def test_create_dataset__with_description__passes_description_to_api(self):
        """Verify create_dataset forwards the description to the REST API."""
        result = self.opik_client_.create_dataset(
            name="my-dataset", description="A test dataset"
        )

        self.mock_create.assert_called_once_with(
            name="my-dataset",
            description="A test dataset",
            project_name="default-project",
        )
        assert result.description == "A test dataset"

    def test_create_dataset__returns_dataset_with_zero_items(self):
        """Verify create_dataset returns a Dataset with dataset_items_count=0."""
        result = self.opik_client_.create_dataset(name="my-dataset")

        assert result.dataset_items_count == 0

    def test_create_dataset__logs_url_after_creation(self):
        """Verify create_dataset calls _display_created_dataset_url with name and dataset id."""
        result = self.opik_client_.create_dataset(name="my-dataset")
        dataset_id = result.id  # triggers cached_property fetch

        self.mock_display_url.assert_called_once_with(
            dataset_name="my-dataset", dataset_id=dataset_id
        )


class TestOpikClientGetDataset:
    """Tests for Opik.get_dataset() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_rest_datasets = self.opik_client_._rest_client.datasets

        self.mock_dataset_public = Mock()
        self.mock_dataset_public.configure_mock(name="test_dataset")
        self.mock_dataset_public.description = "Test description"
        self.mock_dataset_public.dataset_items_count = 10
        self.mock_dataset_public.project_id = None

        with (
            patch.object(
                self.mock_rest_datasets,
                "get_dataset_by_identifier",
                return_value=self.mock_dataset_public,
            ) as self.mock_get_by_identifier,
            patch.object(
                self.mock_rest_datasets,
                "stream_dataset_items",
                return_value=iter([]),
            ),
        ):
            yield

    def test_get_dataset__no_filter__returns_dataset(self):
        """Verify get_dataset without filter returns a Dataset object."""
        result = self.opik_client_.get_dataset(name="test_dataset")
        assert result.name == "test_dataset"
        assert result.description == "Test description"
        assert result.dataset_items_count == 10

    def test_get_dataset__no_project_name__returns_dataset_with_default_project(self):
        """Verify get_dataset without project_name returns a Dataset using the default project."""
        result = self.opik_client_.get_dataset(name="test_dataset")

        assert isinstance(result, Dataset)
        assert result.name == "test_dataset"
        assert result.project_name == "default-project"

        self.mock_get_by_identifier.assert_called_once_with(
            dataset_name="test_dataset", project_name="default-project"
        )

    def test_get_dataset__explicit_project_name__uses_given_project(self):
        """Verify get_dataset passes the explicit project_name to the REST client."""
        result = self.opik_client_.get_dataset(
            name="test_dataset", project_name="custom-project"
        )

        assert result.project_name == "custom-project"
        self.mock_get_by_identifier.assert_called_once_with(
            dataset_name="test_dataset", project_name="custom-project"
        )


class TestOpikClientCreateTestSuite:
    """Tests for Opik.create_test_suite() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_rest_datasets = self.opik_client_._rest_client.datasets

        with (
            patch.object(
                self.mock_rest_datasets, "create_dataset"
            ) as self.mock_create_dataset,
            patch.object(
                self.mock_rest_datasets,
                "get_dataset_by_identifier",
                return_value=Mock(id="some-suite-id"),
            ) as self.mock_get_by_identifier,
            patch.object(
                self.mock_rest_datasets, "apply_dataset_item_changes"
            ) as self.mock_apply_changes,
        ):
            yield

    def test_create_test_suite__minimal__returns_suite(self):
        """Verify create_test_suite returns a TestSuite with the given name."""
        result = self.opik_client_.create_test_suite(name="my-suite")

        assert isinstance(result, TestSuite)
        assert result.name == "my-suite"

    def test_create_test_suite__no_project_name__uses_default_project(self):
        """Verify create_test_suite uses the client's default project when project_name is None."""
        self.opik_client_.create_test_suite(name="my-suite")

        self.mock_create_dataset.assert_called_once()
        assert (
            self.mock_create_dataset.call_args[1]["project_name"] == "default-project"
        )

        self.mock_get_by_identifier.assert_called_once()
        assert (
            self.mock_get_by_identifier.call_args[1]["project_name"]
            == "default-project"
        )

    def test_create_test_suite__explicit_project_name__uses_given_project(self):
        """Verify create_test_suite uses the provided project_name over the default."""
        self.opik_client_.create_test_suite(
            name="my-suite", project_name="custom-project"
        )

        assert self.mock_create_dataset.call_args[1]["project_name"] == "custom-project"
        assert (
            self.mock_get_by_identifier.call_args[1]["project_name"] == "custom-project"
        )

    def test_create_test_suite__with_description__passes_description_to_api(self):
        """Verify create_test_suite forwards the description to the REST layer."""
        self.opik_client_.create_test_suite(
            name="my-suite", description="A regression suite"
        )

        assert (
            self.mock_create_dataset.call_args[1]["description"] == "A regression suite"
        )

    def test_create_test_suite__with_tags__passes_tags_to_api(self):
        """Verify create_test_suite forwards tags to the REST layer."""
        self.opik_client_.create_test_suite(
            name="my-suite", tags=["smoke", "regression"]
        )

        assert self.mock_create_dataset.call_args[1]["tags"] == ["smoke", "regression"]

    def test_create_test_suite__with_assertions__resolves_evaluators(self):
        """Verify assertions are resolved into evaluators and forwarded via apply_dataset_item_changes."""
        self.opik_client_.create_test_suite(
            name="my-suite",
            global_assertions=["Response is helpful", "No hallucinations"],
        )

        apply_request = self.mock_apply_changes.call_args[1]["request"]
        assert "evaluators" in apply_request
        assert (
            len(apply_request["evaluators"]) == 1
        )  # single LLMJudge wrapping all assertions

    def test_create_test_suite__no_assertions__apply_changes_not_called(self):
        """Verify apply_dataset_item_changes is not called when no assertions or policy are provided (OPIK-5815)."""
        self.opik_client_.create_test_suite(name="my-suite")

        self.mock_apply_changes.assert_not_called()

    def test_create_test_suite__with_execution_policy__passes_policy_to_api(self):
        """Verify a valid execution policy is forwarded via apply_dataset_item_changes."""
        policy = {"runs_per_item": 3, "pass_threshold": 2}
        self.opik_client_.create_test_suite(
            name="my-suite", global_execution_policy=policy
        )

        apply_request = self.mock_apply_changes.call_args[1]["request"]
        assert apply_request["execution_policy"] == {
            "runs_per_item": 3,
            "pass_threshold": 2,
        }

    def test_create_test_suite__invalid_execution_policy__raises_value_error(
        self,
    ):
        """Verify an invalid execution policy raises ValueError before any API call."""
        with pytest.raises(ValueError):
            self.opik_client_.create_test_suite(
                name="my-suite",
                global_execution_policy={"runs_per_item": 3},  # missing pass_threshold
            )

        self.mock_create_dataset.assert_not_called()


class TestOpikClientDeleteDataset:
    """Tests for Opik.delete_dataset() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_rest_datasets = self.opik_client_._rest_client.datasets

        with patch.object(
            self.mock_rest_datasets, "delete_dataset_by_name"
        ) as self.mock_delete:
            yield

    def test_delete_dataset__no_project_name__uses_default_project(self):
        """Verify delete_dataset resolves None project_name to the client's default project."""
        self.opik_client_.delete_dataset(name="my-dataset")

        self.mock_delete.assert_called_once_with(
            dataset_name="my-dataset",
            project_name="default-project",
        )

    def test_delete_dataset__explicit_project_name__uses_given_project(self):
        """Verify delete_dataset forwards an explicit project_name to the REST API."""
        self.opik_client_.delete_dataset(
            name="my-dataset", project_name="custom-project"
        )

        self.mock_delete.assert_called_once_with(
            dataset_name="my-dataset",
            project_name="custom-project",
        )

    def test_delete_dataset__passes_correct_dataset_name(self):
        """Verify delete_dataset forwards the dataset name unchanged to the REST API."""
        self.opik_client_.delete_dataset(
            name="target-dataset", project_name="some-project"
        )

        call_kwargs = self.mock_delete.call_args[1]
        assert call_kwargs["dataset_name"] == "target-dataset"
        assert call_kwargs["project_name"] == "some-project"


class TestOpikClientGetDatasets:
    """Tests for Opik.get_datasets() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_rest_datasets = self.opik_client_._rest_client.datasets

        mock_page = Mock()
        mock_page.content = []

        with (
            patch.object(
                self.mock_rest_datasets,
                "find_datasets",
                return_value=mock_page,
            ) as self.mock_find_datasets,
            patch.object(
                self.mock_rest_datasets,
                "stream_dataset_items",
                return_value=iter([]),
            ),
            patch(
                "opik.api_objects.rest_helpers.resolve_project_id_by_name",
                return_value="resolved-project-id",
            ) as self.mock_resolve_project_id,
        ):
            yield

    def test_get_datasets__no_project_name__uses_default_project_id(self):
        """Verify get_datasets resolves the default project name to an ID and passes it to find_datasets."""
        self.opik_client_.get_datasets()

        self.mock_resolve_project_id.assert_called_once_with(
            self.opik_client_._rest_client, "default-project"
        )
        self.mock_find_datasets.assert_called_once()
        assert (
            self.mock_find_datasets.call_args[1]["project_id"] == "resolved-project-id"
        )

    def test_get_datasets__explicit_project_name__resolves_and_passes_project_id(self):
        """Verify get_datasets resolves the explicit project_name to an ID and passes it to find_datasets."""
        self.opik_client_.get_datasets(project_name="custom-project")

        self.mock_resolve_project_id.assert_called_once_with(
            self.opik_client_._rest_client, "custom-project"
        )
        assert (
            self.mock_find_datasets.call_args[1]["project_id"] == "resolved-project-id"
        )

    def test_get_datasets__returns_list_of_datasets(self):
        """Verify get_datasets returns a list of Dataset objects built from the API response."""
        mock_dataset_fern = Mock()
        mock_dataset_fern.name = "ds-1"
        mock_dataset_fern.description = "desc"
        mock_dataset_fern.dataset_items_count = 5

        mock_page = Mock()
        mock_page.content = [mock_dataset_fern]
        self.mock_find_datasets.side_effect = [mock_page, Mock(content=[])]

        result = self.opik_client_.get_datasets(project_name="custom-project")

        assert len(result) == 1
        assert isinstance(result[0], Dataset)

        dataset = result[0]
        assert dataset.name == "ds-1"
        assert dataset.description == "desc"
        assert dataset.dataset_items_count == 5
        assert dataset.project_name == "custom-project"

    def test_get_datasets__respects_max_results(self):
        """Verify get_datasets stops after collecting max_results items."""
        mock_dataset_fern = Mock()
        mock_dataset_fern.name = "ds"
        mock_dataset_fern.description = None
        mock_dataset_fern.dataset_items_count = 0

        mock_page = Mock()
        mock_page.content = [mock_dataset_fern] * 10
        self.mock_find_datasets.return_value = mock_page

        result = self.opik_client_.get_datasets(max_results=3)

        assert len(result) == 3


class TestOpikClientGetTestSuite:
    """Tests for Opik.get_test_suite() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_rest_datasets = self.opik_client_._rest_client.datasets

        self.mock_dataset_fern = Mock()
        self.mock_dataset_fern.configure_mock(name="my-suite")
        self.mock_dataset_fern.description = "Suite description"
        self.mock_dataset_fern.dataset_items_count = 0
        self.mock_dataset_fern.project_id = None

        with (
            patch.object(
                self.mock_rest_datasets,
                "get_dataset_by_identifier",
                return_value=self.mock_dataset_fern,
            ) as self.mock_get_by_identifier,
            patch.object(
                self.mock_rest_datasets,
                "stream_dataset_items",
                return_value=iter([]),
            ),
        ):
            yield

    def test_get_test_suite__returns_test_suite(self):
        """Verify get_test_suite returns a TestSuite with the correct name."""
        result = self.opik_client_.get_test_suite(name="my-suite")

        assert isinstance(result, TestSuite)
        assert result.name == "my-suite"

    def test_get_test_suite__no_project_name__uses_default_project(self):
        """Verify get_test_suite passes the default project name to get_dataset_by_identifier."""
        self.opik_client_.get_test_suite(name="my-suite")

        self.mock_get_by_identifier.assert_called_once_with(
            dataset_name="my-suite",
            project_name="default-project",
        )

    def test_get_test_suite__explicit_project_name__uses_given_project(self):
        """Verify get_test_suite passes the explicit project_name to get_dataset_by_identifier."""
        self.opik_client_.get_test_suite(name="my-suite", project_name="custom-project")

        self.mock_get_by_identifier.assert_called_once_with(
            dataset_name="my-suite",
            project_name="custom-project",
        )


class TestOpikClientCreateExperiment:
    """Tests for Opik.create_experiment() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")

        with patch.object(
            self.opik_client_._rest_client.experiments, "create_experiment"
        ) as self.mock_create_experiment:
            yield

    def test_create_experiment__returns_experiment(self):
        """Verify create_experiment returns an Experiment with the correct dataset_name."""
        from opik.api_objects.experiment import experiment as experiment_module

        result = self.opik_client_.create_experiment(
            dataset_name="my-dataset", project_name="custom-project"
        )

        assert isinstance(result, experiment_module.Experiment)
        assert result.dataset_name == "my-dataset"
        assert result.project_name == "custom-project"

    def test_create_experiment__no_project_name__passes_default_project_name_to_api(
        self,
    ):
        """Verify create_experiment passes default project_name to the REST API."""
        result = self.opik_client_.create_experiment(dataset_name="my-dataset")

        call_kwargs = self.mock_create_experiment.call_args[1]
        assert call_kwargs["project_name"] == "default-project"
        assert result.project_name == "default-project"

    def test_create_experiment__explicit_project_name__passes_project_name_to_api(self):
        """Verify create_experiment forwards an explicit project_name to the REST API."""
        result = self.opik_client_.create_experiment(
            dataset_name="my-dataset", project_name="custom-project"
        )

        call_kwargs = self.mock_create_experiment.call_args[1]
        assert call_kwargs["project_name"] == "custom-project"
        assert result.project_name == "custom-project"

    def test_create_experiment__with_name__passes_name_to_api(self):
        """Verify create_experiment forwards the experiment name to the REST API."""
        self.opik_client_.create_experiment(
            dataset_name="my-dataset", name="my-experiment"
        )

        call_kwargs = self.mock_create_experiment.call_args[1]
        assert call_kwargs["name"] == "my-experiment"
        assert call_kwargs["dataset_name"] == "my-dataset"

    def test_create_experiment__with_tags__passes_tags_to_api(self):
        """Verify create_experiment forwards tags to the REST API."""
        self.opik_client_.create_experiment(
            dataset_name="my-dataset", tags=["v1", "smoke"]
        )

        call_kwargs = self.mock_create_experiment.call_args[1]
        assert call_kwargs["tags"] == ["v1", "smoke"]

    def test_create_experiment__with_experiment_config__passes_metadata_to_api(self):
        """Verify experiment_config is serialized into metadata and forwarded to the REST API."""
        config = {"model": "gpt-4", "temperature": 0.5}
        self.opik_client_.create_experiment(
            dataset_name="my-dataset", experiment_config=config
        )

        call_kwargs = self.mock_create_experiment.call_args[1]
        assert call_kwargs["metadata"] == config


def _make_text_prompt_version() -> Mock:
    """Return a minimal mock PromptVersionDetail for a text prompt."""
    v = Mock()
    v.id = "version-id"
    v.prompt_id = "prompt-id"
    v.template = "Hello {{name}}"
    v.type = "mustache"
    v.commit = "abc123"
    v.metadata = None
    v.change_description = None
    v.tags = None
    v.template_structure = "text"
    return v


def _make_chat_prompt_version() -> Mock:
    """Return a minimal mock PromptVersionDetail for a chat prompt."""
    v = Mock()
    v.id = "version-id"
    v.prompt_id = "prompt-id"
    v.template = json.dumps([{"role": "user", "content": "Hello"}])
    v.type = "mustache"
    v.commit = "abc123"
    v.metadata = None
    v.change_description = None
    v.tags = None
    v.template_structure = "chat"
    return v


class TestOpikClientCreatePrompt:
    """Tests for Opik.create_prompt() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_version = _make_text_prompt_version()

        with patch.object(
            prompt_client_module.PromptClient,
            "create_prompt",
            return_value=self.mock_version,
        ) as self.mock_create:
            yield

    def test_create_prompt__no_project_name__uses_default_project(self):
        """Verify create_prompt resolves None project_name to the client's default."""
        self.opik_client_.create_prompt(name="my-prompt", prompt="Hello {{name}}")

        call_kwargs = self.mock_create.call_args[1]
        assert call_kwargs["project_name"] == "default-project"

    def test_create_prompt__explicit_project_name__forwards_to_prompt_client(self):
        """Verify create_prompt forwards an explicit project_name to PromptClient."""
        self.opik_client_.create_prompt(
            name="my-prompt", prompt="Hello {{name}}", project_name="custom-project"
        )

        call_kwargs = self.mock_create.call_args[1]
        assert call_kwargs["project_name"] == "custom-project"

    def test_create_prompt__returns_prompt_with_resolved_project_name(self):
        """Verify the returned Prompt carries the resolved project_name."""
        result = self.opik_client_.create_prompt(
            name="my-prompt", prompt="Hello {{name}}"
        )

        assert isinstance(result, prompt_module.Prompt)
        assert result.project_name == "default-project"


class TestOpikClientCreateChatPrompt:
    """Tests for Opik.create_chat_prompt() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.messages = [{"role": "user", "content": "Hello"}]
        self.mock_version = _make_chat_prompt_version()

        with patch.object(
            prompt_client_module.PromptClient,
            "create_prompt",
            return_value=self.mock_version,
        ) as self.mock_create:
            yield

    def test_create_chat_prompt__no_project_name__uses_default_project(self):
        """Verify create_chat_prompt resolves None project_name to the client's default."""
        result = self.opik_client_.create_chat_prompt(
            name="my-chat-prompt", messages=self.messages
        )

        assert isinstance(result, prompt_module.ChatPrompt)
        assert result.project_name == "default-project"

    def test_create_chat_prompt__explicit_project_name__uses_given_project(self):
        """Verify create_chat_prompt uses the provided project_name."""
        result = self.opik_client_.create_chat_prompt(
            name="my-chat-prompt",
            messages=self.messages,
            project_name="custom-project",
        )

        assert result.project_name == "custom-project"


class TestOpikClientGetPrompt:
    """Tests for Opik.get_prompt() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_version = _make_text_prompt_version()

        with patch.object(
            prompt_client_module.PromptClient,
            "get_prompt",
            return_value=self.mock_version,
        ) as self.mock_get:
            yield

    def test_get_prompt__no_project_name__uses_default_project(self):
        """Verify get_prompt resolves None project_name to the client's default."""
        self.opik_client_.get_prompt(name="my-prompt")

        call_kwargs = self.mock_get.call_args[1]
        assert call_kwargs["project_name"] == "default-project"

    def test_get_prompt__explicit_project_name__forwards_to_prompt_client(self):
        """Verify get_prompt forwards an explicit project_name to PromptClient."""
        self.opik_client_.get_prompt(name="my-prompt", project_name="custom-project")

        call_kwargs = self.mock_get.call_args[1]
        assert call_kwargs["project_name"] == "custom-project"

    def test_get_prompt__returns_prompt_with_resolved_project_name(self):
        """Verify the returned Prompt carries the resolved project_name."""
        result = self.opik_client_.get_prompt(name="my-prompt")

        assert isinstance(result, prompt_module.Prompt)
        assert result.project_name == "default-project"

    def test_get_prompt__not_found__returns_none(self):
        """Verify get_prompt returns None when the prompt does not exist."""
        with patch.object(
            prompt_client_module.PromptClient, "get_prompt", return_value=None
        ):
            result = self.opik_client_.get_prompt(name="nonexistent")

        assert result is None


class TestOpikClientGetChatPrompt:
    """Tests for Opik.get_chat_prompt() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_version = _make_chat_prompt_version()

        with patch.object(
            prompt_client_module.PromptClient,
            "get_prompt",
            return_value=self.mock_version,
        ) as self.mock_get:
            yield

    def test_get_chat_prompt__no_project_name__uses_default_project(self):
        """Verify get_chat_prompt resolves None project_name to the client's default."""
        self.opik_client_.get_chat_prompt(name="my-chat-prompt")

        call_kwargs = self.mock_get.call_args[1]
        assert call_kwargs["project_name"] == "default-project"

    def test_get_chat_prompt__explicit_project_name__forwards_to_prompt_client(self):
        """Verify get_chat_prompt forwards an explicit project_name to PromptClient."""
        self.opik_client_.get_chat_prompt(
            name="my-chat-prompt", project_name="custom-project"
        )

        call_kwargs = self.mock_get.call_args[1]
        assert call_kwargs["project_name"] == "custom-project"

    def test_get_chat_prompt__returns_chat_prompt_with_resolved_project_name(self):
        """Verify the returned ChatPrompt carries the resolved project_name."""
        result = self.opik_client_.get_chat_prompt(name="my-chat-prompt")

        assert isinstance(result, prompt_module.ChatPrompt)
        assert result.project_name == "default-project"

    def test_get_chat_prompt__not_found__returns_none(self):
        """Verify get_chat_prompt returns None when the prompt does not exist."""
        with patch.object(
            prompt_client_module.PromptClient, "get_prompt", return_value=None
        ):
            result = self.opik_client_.get_chat_prompt(name="nonexistent")

        assert result is None


class TestOpikClientGetPromptHistory:
    """Tests for Opik.get_prompt_history() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_version = _make_text_prompt_version()

        with (
            patch.object(
                prompt_client_module.PromptClient,
                "get_prompt",
                return_value=self.mock_version,
            ) as self.mock_get_prompt,
            patch.object(
                prompt_client_module.PromptClient,
                "get_all_prompt_versions",
                return_value=[self.mock_version],
            ) as self.mock_get_all_versions,
        ):
            yield

    def test_get_prompt_history__no_project_name__uses_default_project(self):
        """Verify get_prompt_history resolves None project_name to the client's default."""
        self.opik_client_.get_prompt_history(name="my-prompt")

        assert self.mock_get_prompt.call_args[1]["project_name"] == "default-project"
        assert (
            self.mock_get_all_versions.call_args[1]["project_name"] == "default-project"
        )

    def test_get_prompt_history__explicit_project_name__forwards_to_prompt_client(
        self,
    ):
        """Verify get_prompt_history forwards an explicit project_name to both PromptClient calls."""
        self.opik_client_.get_prompt_history(
            name="my-prompt", project_name="custom-project"
        )

        assert self.mock_get_prompt.call_args[1]["project_name"] == "custom-project"
        assert (
            self.mock_get_all_versions.call_args[1]["project_name"] == "custom-project"
        )

    def test_get_prompt_history__returns_prompts_with_resolved_project_name(self):
        """Verify each returned Prompt carries the resolved project_name."""
        results = self.opik_client_.get_prompt_history(name="my-prompt")

        assert len(results) == 1
        assert isinstance(results[0], prompt_module.Prompt)
        assert results[0].project_name == "default-project"

    def test_get_prompt_history__prompt_not_found__returns_empty_list(self):
        """Verify get_prompt_history returns [] when the prompt does not exist."""
        with patch.object(
            prompt_client_module.PromptClient, "get_prompt", return_value=None
        ):
            results = self.opik_client_.get_prompt_history(name="nonexistent")

        assert results == []


class TestOpikClientGetChatPromptHistory:
    """Tests for Opik.get_chat_prompt_history() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")
        self.mock_version = _make_chat_prompt_version()

        with (
            patch.object(
                prompt_client_module.PromptClient,
                "get_prompt",
                return_value=self.mock_version,
            ) as self.mock_get_prompt,
            patch.object(
                prompt_client_module.PromptClient,
                "get_all_prompt_versions",
                return_value=[self.mock_version],
            ) as self.mock_get_all_versions,
        ):
            yield

    def test_get_chat_prompt_history__no_project_name__uses_default_project(self):
        """Verify get_chat_prompt_history resolves None project_name to the client's default."""
        self.opik_client_.get_chat_prompt_history(name="my-chat-prompt")

        assert self.mock_get_prompt.call_args[1]["project_name"] == "default-project"
        assert (
            self.mock_get_all_versions.call_args[1]["project_name"] == "default-project"
        )

    def test_get_chat_prompt_history__explicit_project_name__forwards_to_prompt_client(
        self,
    ):
        """Verify get_chat_prompt_history forwards an explicit project_name to both PromptClient calls."""
        self.opik_client_.get_chat_prompt_history(
            name="my-chat-prompt", project_name="custom-project"
        )

        assert self.mock_get_prompt.call_args[1]["project_name"] == "custom-project"
        assert (
            self.mock_get_all_versions.call_args[1]["project_name"] == "custom-project"
        )

    def test_get_chat_prompt_history__returns_chat_prompts_with_resolved_project_name(
        self,
    ):
        """Verify each returned ChatPrompt carries the resolved project_name."""
        results = self.opik_client_.get_chat_prompt_history(name="my-chat-prompt")

        assert len(results) == 1
        assert isinstance(results[0], prompt_module.ChatPrompt)
        assert results[0].project_name == "default-project"

    def test_get_chat_prompt_history__prompt_not_found__returns_empty_list(self):
        """Verify get_chat_prompt_history returns [] when the prompt does not exist."""
        with patch.object(
            prompt_client_module.PromptClient, "get_prompt", return_value=None
        ):
            results = self.opik_client_.get_chat_prompt_history(name="nonexistent")

        assert results == []


class TestOpikClientSearchPrompts:
    """Tests for Opik.search_prompts() method."""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.opik_client_ = opik_client.Opik(project_name="default-project")

        with patch.object(
            prompt_client_module.PromptClient,
            "search_prompts",
            return_value=[],
        ) as self.mock_search:
            yield

    def test_search_prompts__no_project_name__uses_default_project(self):
        """Verify search_prompts resolves None project_name to the client's default."""
        self.opik_client_.search_prompts()

        call_kwargs = self.mock_search.call_args[1]
        assert call_kwargs["project_name"] == "default-project"

    def test_search_prompts__explicit_project_name__forwards_to_prompt_client(self):
        """Verify search_prompts forwards an explicit project_name to PromptClient."""
        self.opik_client_.search_prompts(project_name="custom-project")

        call_kwargs = self.mock_search.call_args[1]
        assert call_kwargs["project_name"] == "custom-project"

    def test_search_prompts__returns_text_and_chat_prompts_with_resolved_project_name(
        self,
    ):
        """Verify returned prompts carry the resolved project_name."""
        text_version = _make_text_prompt_version()
        chat_version = _make_chat_prompt_version()

        search_results = [
            prompt_client_module.PromptSearchResult(
                name="text-prompt",
                template_structure="text",
                prompt_version_detail=text_version,
                project_name="default-project",
            ),
            prompt_client_module.PromptSearchResult(
                name="chat-prompt",
                template_structure="chat",
                prompt_version_detail=chat_version,
                project_name="default-project",
            ),
        ]

        with patch.object(
            prompt_client_module.PromptClient,
            "search_prompts",
            return_value=search_results,
        ):
            results = self.opik_client_.search_prompts()

        assert len(results) == 2
        assert isinstance(results[0], prompt_module.Prompt)
        assert results[0].project_name == "default-project"
        assert isinstance(results[1], prompt_module.ChatPrompt)
        assert results[1].project_name == "default-project"


@pytest.mark.parametrize(
    "exclude,expected",
    [
        (["feedback_scores"], ["feedback_scores"]),
        (
            ["feedback_scores", "input", "output"],
            ["feedback_scores", "input", "output"],
        ),
    ],
)
def test_opik_client__search_spans__exclude_propagated_to_api(exclude, expected):
    client = opik_client.Opik(project_name="test-project")

    with patch(
        "opik.api_objects.search_helpers.search_spans_with_filters",
        return_value=[],
    ) as mock_search:
        client.search_spans(project_name="test-project", exclude=exclude)

    mock_search.assert_called_once()
    assert mock_search.call_args.kwargs["exclude"] == expected


def test_opik_client__search_spans__exclude_defaults_to_none():
    client = opik_client.Opik(project_name="test-project")

    with patch(
        "opik.api_objects.search_helpers.search_spans_with_filters",
        return_value=[],
    ) as mock_search:
        client.search_spans(project_name="test-project")

    mock_search.assert_called_once()
    assert mock_search.call_args.kwargs["exclude"] is None


@pytest.mark.parametrize(
    "exclude,expected",
    [
        (["feedback_scores"], ["feedback_scores"]),
        (
            ["feedback_scores", "input", "output"],
            ["feedback_scores", "input", "output"],
        ),
    ],
)
def test_opik_client__search_traces__exclude_propagated_to_api(exclude, expected):
    client = opik_client.Opik(project_name="test-project")

    with patch(
        "opik.api_objects.search_helpers.search_traces_with_filters",
        return_value=[],
    ) as mock_search:
        client.search_traces(project_name="test-project", exclude=exclude)

    mock_search.assert_called_once()
    assert mock_search.call_args.kwargs["exclude"] == expected


def test_opik_client__search_traces__exclude_defaults_to_none():
    client = opik_client.Opik(project_name="test-project")

    with patch(
        "opik.api_objects.search_helpers.search_traces_with_filters",
        return_value=[],
    ) as mock_search:
        client.search_traces(project_name="test-project")

    mock_search.assert_called_once()
    assert mock_search.call_args.kwargs["exclude"] is None
