import unittest
from unittest.mock import Mock, patch

from opik.api_objects.dataset import dataset
from opik.rest_api.core.api_error import ApiError


class TestDatasetRateLimitRetry(unittest.TestCase):
    """Test rate limit retry behavior for dataset operations using public API."""

    def _create_dataset_with_mock_client(self) -> tuple[dataset.Dataset, Mock]:
        """Create a Dataset instance with a mocked REST client."""
        mock_rest_client = Mock()
        dataset_obj = dataset.Dataset(
            name="test_dataset",
            description="test",
            rest_client=mock_rest_client,
        )
        return dataset_obj, mock_rest_client

    @patch("opik.api_objects.rest_helpers.time.sleep")
    def test_insert__429_with_retry_after_header__retries_with_correct_delay(
        self, mock_sleep: Mock
    ) -> None:
        """Test that 429 errors with RateLimit-Reset header are retried with correct delay."""
        dataset_obj, mock_rest_client = self._create_dataset_with_mock_client()

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

        # Execute using public API
        dataset_obj.insert([{"input": "test"}])

        # Verify retry behavior
        assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 2
        mock_sleep.assert_called_once_with(5.0)

    @patch("opik.api_objects.rest_helpers.time.sleep")
    def test_insert__429_without_header__uses_fallback_delay(
        self, mock_sleep: Mock
    ) -> None:
        """Test that 429 errors without headers use fallback 1 second delay."""
        dataset_obj, mock_rest_client = self._create_dataset_with_mock_client()

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

        # Execute using public API
        dataset_obj.insert([{"input": "test"}])

        # Verify fallback delay: always 1 second when no header
        assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 3
        assert mock_sleep.call_count == 2
        # Both retries should use 1 second delay
        assert all(call[0][0] == 1 for call in mock_sleep.call_args_list)

    def test_insert__non_429_error__raises_immediately(self) -> None:
        """Test that non-429 errors are raised immediately without retry."""
        dataset_obj, mock_rest_client = self._create_dataset_with_mock_client()

        # Simulate a 500 error
        error = ApiError(
            status_code=500,
            headers={},
            body="Internal server error",
        )
        mock_rest_client.datasets.create_or_update_dataset_items.side_effect = error

        # Execute & Verify
        with self.assertRaises(ApiError) as context:
            dataset_obj.insert([{"input": "test"}])

        assert context.exception.status_code == 500
        # Should only try once for non-429 errors
        assert mock_rest_client.datasets.create_or_update_dataset_items.call_count == 1

    @patch("opik.api_objects.rest_helpers.time.sleep")
    def test_delete__429_with_retry_after_header__retries_with_correct_delay(
        self, mock_sleep: Mock
    ) -> None:
        """Test that delete operation also handles 429 errors correctly."""
        dataset_obj, mock_rest_client = self._create_dataset_with_mock_client()

        # First call raises 429 with rate limit headers, second call succeeds
        rate_limit_error = ApiError(
            status_code=429,
            headers={
                "RateLimit-Reset": "3",
            },
            body="Rate limit exceeded",
        )
        mock_rest_client.datasets.delete_dataset_items.side_effect = [
            rate_limit_error,
            None,  # Success on second attempt
        ]

        # Execute using public API
        dataset_obj.delete(["item-id-1"])

        # Verify retry behavior
        assert mock_rest_client.datasets.delete_dataset_items.call_count == 2
        mock_sleep.assert_called_once_with(3.0)
