"""Test rate limit retry logic for dataset items."""
import unittest
from unittest.mock import Mock, patch, call
import time

from opik.api_objects.dataset import dataset
from opik.rest_api.types import dataset_item_write as rest_dataset_item
from opik.rest_api.core.api_error import ApiError


class TestDatasetRateLimitRetry(unittest.TestCase):
    """Test rate limit retry behavior for dataset items."""

    @patch("time.sleep")
    def test_insert_batch_with_retry__429_with_retry_after_header__retries_with_correct_delay(
        self, mock_sleep
    ):
        """Test that 429 errors with Retry-After header are retried with correct delay."""
        # Setup
        mock_rest_client = Mock()
        dataset_obj = dataset.Dataset.__new__(dataset.Dataset)
        dataset_obj._name = "test_dataset"
        dataset_obj._description = "test"
        dataset_obj._rest_client = mock_rest_client
        dataset_obj._hashes = set()
        dataset_obj._id_to_hash = {}

        # Create mock batch
        batch = [
            rest_dataset_item.DatasetItemWrite(
                source="manual",
                data={"input": "test"},
            )
        ]

        # First call raises 429 with rate limit headers, second call succeeds
        rate_limit_error = ApiError(
            status_code=429,
            headers={
                "RateLimit-Reset": "5",  # 5 seconds retry after
            },
            body="Rate limit exceeded",
        )
        mock_rest_client.datasets.create_or_update_dataset_items.side_effect = [
            rate_limit_error,
            None,  # Success on second attempt
        ]

        # Execute
        dataset_obj._insert_batch_with_retry(batch)

        # Verify
        assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 2
        mock_sleep.assert_called_once_with(5.0)

    @patch("time.sleep")
    def test_insert_batch_with_retry__429_without_header__uses_exponential_backoff(
        self, mock_sleep
    ):
        """Test that 429 errors without headers use exponential backoff."""
        # Setup
        mock_rest_client = Mock()
        dataset_obj = dataset.Dataset.__new__(dataset.Dataset)
        dataset_obj._name = "test_dataset"
        dataset_obj._description = "test"
        dataset_obj._rest_client = mock_rest_client
        dataset_obj._hashes = set()
        dataset_obj._id_to_hash = {}

        batch = [
            rest_dataset_item.DatasetItemWrite(
                source="manual",
                data={"input": "test"},
            )
        ]

        # First two calls raise 429 without headers, third succeeds
        rate_limit_error = ApiError(
            status_code=429,
            headers={},
            body="Rate limit exceeded",
        )
        mock_rest_client.datasets.create_or_update_dataset_items.side_effect = [
            rate_limit_error,
            rate_limit_error,
            None,  # Success on third attempt
        ]

        # Execute
        dataset_obj._insert_batch_with_retry(batch)

        # Verify exponential backoff: 2^0=1, 2^1=2
        assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 3
        assert mock_sleep.call_count == 2
        # First retry after 1 second, second after 2 seconds
        mock_sleep.assert_any_call(1)
        mock_sleep.assert_any_call(2)

    def test_insert_batch_with_retry__non_429_error__raises_immediately(self):
        """Test that non-429 errors are raised immediately without retry."""
        # Setup
        mock_rest_client = Mock()
        dataset_obj = dataset.Dataset.__new__(dataset.Dataset)
        dataset_obj._name = "test_dataset"
        dataset_obj._description = "test"
        dataset_obj._rest_client = mock_rest_client
        dataset_obj._hashes = set()
        dataset_obj._id_to_hash = {}

        batch = [
            rest_dataset_item.DatasetItemWrite(
                source="manual",
                data={"input": "test"},
            )
        ]

        # Simulate a 500 error
        error = ApiError(
            status_code=500,
            headers={},
            body="Internal server error",
        )
        mock_rest_client.datasets.create_or_update_dataset_items.side_effect = error

        # Execute & Verify
        with self.assertRaises(ApiError) as context:
            dataset_obj._insert_batch_with_retry(batch)

        assert context.exception.status_code == 500
        # Should only try once for non-429 errors
        assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1

    @patch("time.sleep")
    def test_insert_batch_with_retry__max_retries_exceeded__raises_opik_exception(
        self, mock_sleep
    ):
        """Test that exceeding max retries raises OpikException."""
        # Setup
        mock_rest_client = Mock()
        dataset_obj = dataset.Dataset.__new__(dataset.Dataset)
        dataset_obj._name = "test_dataset"
        dataset_obj._description = "test"
        dataset_obj._rest_client = mock_rest_client
        dataset_obj._hashes = set()
        dataset_obj._id_to_hash = {}

        batch = [
            rest_dataset_item.DatasetItemWrite(
                source="manual",
                data={"input": "test"},
            )
        ]

        # Always return 429
        rate_limit_error = ApiError(
            status_code=429,
            headers={},
            body="Rate limit exceeded",
        )
        mock_rest_client.datasets.create_or_update_dataset_items.side_effect = (
            rate_limit_error
        )

        # Execute & Verify
        from opik.exceptions import OpikException

        with self.assertRaises(OpikException) as context:
            dataset_obj._insert_batch_with_retry(batch)

        assert "Failed to insert dataset items after 5 retries" in str(context.exception)
        # Should try max_retries + 1 times (initial + 5 retries = 6 total)
        assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 6

