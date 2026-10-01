import unittest
from unittest.mock import Mock, patch

from opik.api_objects.dataset import dataset
from opik.rest_api.core.api_error import ApiError

from .upload_capture import UploadCapture, make_dataset

# The upload carries the same tenacity retry the generated client methods carry, and it
# sits inside the rate-limit loop. These tests are about the loop, so the decorator is
# neutralised to keep one layer under test; its own behaviour is covered in
# test_dataset_streaming.py.
_NO_TENACITY = "opik.api_objects.dataset.dataset.retry_decorator.opik_rest_retry"


class TestDatasetRateLimitRetry(unittest.TestCase):
    """Test rate limit retry behavior for dataset operations using public API."""

    def _create_dataset_with_mock_client(self) -> tuple[dataset.Dataset, Mock]:
        """Create a Dataset instance with a mocked REST client."""
        mock_rest_client = Mock()
        dataset_obj = dataset.Dataset(
            name="test_dataset",
            description="test",
            project_name="Test project",
            rest_client=mock_rest_client,
        )
        return dataset_obj, mock_rest_client

    def _create_dataset_with_capture(self, capture: UploadCapture) -> dataset.Dataset:
        """A Dataset whose uploads land in `capture` instead of on the network."""
        return make_dataset(dataset.Dataset, Mock(), capture)

    @patch("opik.api_objects.rest_helpers._sleep")
    def test_insert__429_with_retry_after_header__retries_with_correct_delay(
        self, mock_sleep: Mock
    ) -> None:
        """Test that 429 errors with RateLimit-Reset header are retried with correct delay."""
        # First request is rate limited with a reset header, the retry succeeds.
        capture = UploadCapture(
            responses=[429, 204], response_headers={"RateLimit-Reset": "5"}
        )
        dataset_obj = self._create_dataset_with_capture(capture)

        with patch(_NO_TENACITY, lambda send: send):
            dataset_obj.insert([{"input": "test"}])

        # Verify retry behavior
        assert capture.request_count == 2
        mock_sleep.assert_called_once_with(5.0)

    @patch("opik.api_objects.rest_helpers._sleep")
    def test_insert__429_without_header__uses_fallback_delay(
        self, mock_sleep: Mock
    ) -> None:
        """Test that 429 errors without headers use fallback 1 second delay."""
        # Two rate-limited requests without a reset header, then success.
        capture = UploadCapture(responses=[429, 429, 204])
        dataset_obj = self._create_dataset_with_capture(capture)

        with patch(_NO_TENACITY, lambda send: send):
            dataset_obj.insert([{"input": "test"}])

        # Verify fallback delay: always 1 second when no header
        assert capture.request_count == 3
        assert mock_sleep.call_count == 2
        # Both retries should use 1 second delay
        assert all(call[0][0] == 1 for call in mock_sleep.call_args_list)

    def test_insert__non_429_error__raises_immediately(self) -> None:
        """Test that non-429 errors are raised immediately without retry."""
        # Simulate a 500 error
        capture = UploadCapture(status_code=500)
        dataset_obj = self._create_dataset_with_capture(capture)

        # Execute & Verify
        with patch(_NO_TENACITY, lambda send: send):
            with self.assertRaises(ApiError) as context:
                dataset_obj.insert([{"input": "test"}])

        assert context.exception.status_code == 500
        # Should only try once for non-429 errors
        assert capture.request_count == 1

    @patch("opik.api_objects.rest_helpers._sleep")
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
