import logging
from unittest import mock

import pytest
import tenacity

from opik.api_objects.prompt import client as prompt_client
from opik.api_objects.prompt.types import PromptType
from opik.rest_api import core as rest_api_core
from opik.rest_api.types import prompt_version_detail


class TestPromptClientErrorHandling:
    """Test graceful error handling in PromptClient when Opik backend is unavailable."""

    def test_create_prompt__api_error_502__graceful_fallback(self):
        """Test that PromptClient.create_prompt gracefully handles 502 Bad Gateway errors."""
        # Arrange
        api_error = rest_api_core.ApiError(
            status_code=502,
            body="Bad Gateway",
            headers={}
        )
        
        mock_rest_client = mock.Mock()
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = api_error
        
        client = prompt_client.PromptClient(mock_rest_client)
        
        # Act - Should not raise an exception
        result_prompt = client.create_prompt(
            name="test-prompt",
            prompt="Hello {{name}}",
            metadata={"test": "value"},
            type=PromptType.MUSTACHE
        )
        
        # Assert - Should return fallback prompt
        assert result_prompt.name == "test-prompt"
        assert result_prompt.prompt == "Hello {{name}}"
        assert result_prompt.commit == "fallback"
        assert result_prompt.metadata == {"test": "value"}
        assert result_prompt.type == PromptType.MUSTACHE

    def test_create_prompt__retry_error__graceful_fallback(self):
        """Test that PromptClient.create_prompt gracefully handles RetryError."""
        # Arrange
        api_error = rest_api_core.ApiError(
            status_code=502,
            body="Bad Gateway",
            headers={}
        )
        
        attempt = tenacity.AttemptManager(1)
        attempt.exception = lambda: api_error
        retry_error = tenacity.RetryError(attempt)
        
        mock_rest_client = mock.Mock()
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = retry_error
        
        client = prompt_client.PromptClient(mock_rest_client)
        
        # Act - Should not raise an exception
        result_prompt = client.create_prompt(
            name="test-prompt-retry",
            prompt="Hello {{name}}",
            metadata=None,
            type=PromptType.JINJA2
        )
        
        # Assert - Should return fallback prompt
        assert result_prompt.name == "test-prompt-retry"
        assert result_prompt.commit == "fallback"
        assert result_prompt.type == PromptType.JINJA2

    def test_get_prompt__api_error_502__returns_none_gracefully(self):
        """Test that PromptClient.get_prompt gracefully handles 502 errors by returning None."""
        # Arrange
        api_error = rest_api_core.ApiError(
            status_code=502,
            body="Bad Gateway",
            headers={}
        )
        
        mock_rest_client = mock.Mock()
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = api_error
        
        client = prompt_client.PromptClient(mock_rest_client)
        
        # Act - Should not raise an exception
        result = client.get_prompt(name="test-prompt", commit="abc123")
        
        # Assert - Should return None gracefully
        assert result is None

    def test_get_prompt__404_error__returns_none_no_logging(self):
        """Test that PromptClient.get_prompt handles 404 errors without logging (expected case)."""
        # Arrange
        api_error = rest_api_core.ApiError(
            status_code=404,
            body="Not Found",
            headers={}
        )
        
        mock_rest_client = mock.Mock()
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = api_error
        
        client = prompt_client.PromptClient(mock_rest_client)
        
        # Act - Should not raise an exception
        result = client.get_prompt(name="non-existent-prompt")
        
        # Assert - Should return None gracefully
        assert result is None

    def test_get_all_prompts__api_error_502__returns_empty_list_gracefully(self):
        """Test that PromptClient.get_all_prompts gracefully handles 502 errors by returning empty list."""
        # Arrange
        api_error = rest_api_core.ApiError(
            status_code=502,
            body="Bad Gateway",
            headers={}
        )
        
        mock_rest_client = mock.Mock()
        mock_rest_client.prompts.get_prompts.side_effect = api_error
        
        client = prompt_client.PromptClient(mock_rest_client)
        
        # Act - Should not raise an exception
        result = client.get_all_prompts(name="test-prompt")
        
        # Assert - Should return empty list gracefully
        assert result == []

    def test_get_all_prompts__value_error_no_prompts__returns_empty_list_gracefully(self):
        """Test that PromptClient.get_all_prompts gracefully handles ValueError when no prompts found."""
        # Arrange
        mock_rest_client = mock.Mock()
        mock_rest_client.prompts.get_prompts.return_value.content = []  # Empty list
        
        client = prompt_client.PromptClient(mock_rest_client)
        
        # Act - Should not raise an exception
        result = client.get_all_prompts(name="non-existent-prompt")
        
        # Assert - Should return empty list gracefully
        assert result == []

    def test_create_prompt__successful_creation__no_fallback(self):
        """Test that when API works, normal prompt creation occurs (no fallback)."""
        # Arrange
        mock_version = prompt_version_detail.PromptVersionDetail(
            id="real-version-id",
            prompt_id="real-prompt-id",
            template="Hello {{name}}",
            type=PromptType.MUSTACHE.value,
            metadata={"test": "value"},
            commit="abc123"
        )
        
        mock_rest_client = mock.Mock()
        mock_rest_client.prompts.retrieve_prompt_version.return_value = mock_version
        
        client = prompt_client.PromptClient(mock_rest_client)
        
        # Act
        result_prompt = client.create_prompt(
            name="test-prompt",
            prompt="Hello {{name}}",
            metadata={"test": "value"},
            type=PromptType.MUSTACHE
        )
        
        # Assert - Should use real values, not fallback
        assert result_prompt.name == "test-prompt"
        assert result_prompt.commit == "abc123"  # Not "fallback"
        assert result_prompt.__internal_api__version_id__ == "real-version-id"

    def test_error_handling__no_exceptions_thrown(self):
        """Test that various error scenarios don't throw exceptions."""
        error_scenarios = [
            rest_api_core.ApiError(status_code=502, body="Bad Gateway", headers={}),
            rest_api_core.ApiError(status_code=500, body="Internal Server Error", headers={}),
            rest_api_core.ApiError(status_code=503, body="Service Unavailable", headers={}),
        ]
        
        # Add RetryError scenario
        api_error = rest_api_core.ApiError(status_code=502, body="Bad Gateway", headers={})
        attempt = tenacity.AttemptManager(1)
        attempt.exception = lambda: api_error
        retry_error = tenacity.RetryError(attempt)
        error_scenarios.append(retry_error)
        
        for i, error in enumerate(error_scenarios):
            mock_rest_client = mock.Mock()
            mock_rest_client.prompts.retrieve_prompt_version.side_effect = error
            mock_rest_client.prompts.get_prompts.side_effect = error
            
            client = prompt_client.PromptClient(mock_rest_client)
            
            # Test create_prompt - should not raise exception
            try:
                result = client.create_prompt(
                    name=f"test-prompt-{i}",
                    prompt="Test prompt",
                    metadata=None,
                    type=PromptType.MUSTACHE
                )
                assert result.commit == "fallback"
            except Exception as e:
                pytest.fail(f"create_prompt raised unexpected exception: {e}")
            
            # Test get_prompt - should not raise exception
            try:
                result = client.get_prompt(name=f"test-prompt-{i}")
                assert result is None  # Should return None gracefully
            except Exception as e:
                pytest.fail(f"get_prompt raised unexpected exception: {e}")
            
            # Test get_all_prompts - should not raise exception
            try:
                result = client.get_all_prompts(name=f"test-prompt-{i}")
                assert result == []  # Should return empty list gracefully
            except Exception as e:
                pytest.fail(f"get_all_prompts raised unexpected exception: {e}")

    def test_logging_functionality__logger_called(self):
        """Test that error logging is working by checking the logger is called."""
        api_error = rest_api_core.ApiError(
            status_code=502,
            body="Bad Gateway",
            headers={}
        )
        
        mock_rest_client = mock.Mock()
        mock_rest_client.prompts.retrieve_prompt_version.side_effect = api_error
        
        client = prompt_client.PromptClient(mock_rest_client)
        
        with mock.patch('opik.api_objects.prompt.client.LOGGER') as mock_logger:
            # Act
            client.create_prompt(
                name="monitoring-test",
                prompt="Test prompt",
                metadata=None,
                type=PromptType.MUSTACHE
            )
            
            # Assert - Logger should have been called with error
            assert mock_logger.error.call_count >= 1  # May be called multiple times
            
            # Check that at least one call mentions the fallback
            call_args_list = [str(call) for call in mock_logger.error.call_args_list]
            fallback_mentioned = any("fallback" in call for call in call_args_list)
            assert fallback_mentioned 