import inspect
import unittest.mock as mock
import pytest

import opik_optimizer
from opik.api_objects import opik_client
from opik.rest_api.core.api_error import ApiError
from opik.api_objects.dataset import Dataset

# Expected size mappings - add expected sizes based on dataset function names
dataset_sizes = {
    "hotpot_300": 300,
    "hotpot_500": 500,
    "halu_eval_300": 300,
    "tiny_test": 5,
    "gsm8k": 300,
    "ai2_arc": 300,
    "truthful_qa": 300,
    "cnn_dailymail": 100,
    "ragbench_sentence_relevance": 300,
    "election_questions": 300,
    "medhallu": 300,
    "rag_hallucinations": 300
}

# Get all dataset functions from opik_optimizer.datasets with their
# expected sizes
dataset_functions = [
    (name, func, dataset_sizes[name])
    for name, func in inspect.getmembers(opik_optimizer.datasets)
    if inspect.isfunction(func) and name in opik_optimizer.datasets.__all__
]


@pytest.mark.parametrize(
    "dataset_name,dataset_func,expected_size",
    dataset_functions
)
def test_full_datasets(dataset_name, dataset_func, expected_size):
    # Create ApiError with status code 404 to simulate dataset not found
    api_error = ApiError(status_code=404)
    mock_get_dataset = mock.Mock(side_effect=api_error)

    # Create a proper mock Dataset instead of a real one
    mock_rest_client = mock.Mock()
    mock_dataset = Dataset(
        "test_dataset",
        "Test description",
        mock_rest_client
    )
    mock_dataset.get_items = mock.Mock(return_value=[])

    with mock.patch.object(
        opik_client.Opik, "get_dataset", mock_get_dataset
    ):
        # Mock create_dataset to return our mock dataset
        mock_create_dataset = mock.Mock(return_value=mock_dataset)
        with mock.patch.object(
            opik_client.Opik, "create_dataset", mock_create_dataset
        ):
            # Test the dataset function when get_dataset fails with 404
            dataset = dataset_func()

            # Check that the dataset has the expected number of items
            assert len(dataset._hashes) == expected_size, (
                f"Expected {dataset_name} to have {expected_size} items,"
                f" got {len(dataset._hashes)}"
            )


dataset_functions = [
    (name, func, 5)
    for name, func in inspect.getmembers(opik_optimizer.datasets)
    if inspect.isfunction(func) and name in opik_optimizer.datasets.__all__
]


@pytest.mark.parametrize(
    "dataset_name,dataset_func,expected_size",
    dataset_functions
)
def test_test_datasets(dataset_name, dataset_func, expected_size):
    # Create ApiError with status code 404 to simulate dataset not found
    api_error = ApiError(status_code=404)
    mock_get_dataset = mock.Mock(side_effect=api_error)

    # Create a proper mock Dataset instead of a real one
    mock_rest_client = mock.Mock()
    mock_dataset = Dataset(
        "test_dataset",
        "Test description",
        mock_rest_client
    )
    mock_dataset.get_items = mock.Mock(return_value=[])

    with mock.patch.object(
        opik_client.Opik, "get_dataset", mock_get_dataset
    ):
        # Mock create_dataset to return our mock dataset
        mock_create_dataset = mock.Mock(return_value=mock_dataset)
        with mock.patch.object(
            opik_client.Opik, "create_dataset", mock_create_dataset
        ):
            # Test the dataset function when get_dataset fails with 404
            dataset = dataset_func(test_mode=True)

            # Check that the dataset has the expected number of items
            assert len(dataset._hashes) == expected_size, (
                f"Expected {dataset_name} to have {expected_size} items, "
                f"got {len(dataset._hashes)}"
            )
