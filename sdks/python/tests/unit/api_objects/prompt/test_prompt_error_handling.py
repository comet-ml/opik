import logging
from unittest import mock

import pytest
import tenacity

from opik.api_objects.prompt import prompt as opik_prompt
from opik.api_objects.prompt.types import PromptType
from opik.rest_api import core as rest_api_core


class TestPromptErrorHandling:
    """Test graceful error handling in Prompt class when Opik backend is unavailable."""

    def test_prompt_constructor__api_error_502__graceful_fallback(self, caplog):
        """Test that Prompt constructor gracefully handles 502 Bad Gateway errors."""
        # Arrange
        api_error = rest_api_core.ApiError(
            status_code=502,
            body="Bad Gateway",
            headers={}
        )
        
        with mock.patch('opik.api_objects.opik_client.get_client_cached') as mock_client:
            mock_client.return_value.create_prompt.side_effect = api_error
            
            # Act - Create prompt (should not raise an exception)
            prompt = opik_prompt.Prompt(
                name="test-prompt",
                prompt="Hello {{name}}",
                metadata={"test": "value"}
            )
            
            # Assert - Prompt should be created in fallback mode
            assert prompt.name == "test-prompt"
            assert prompt.prompt == "Hello {{name}}"
            assert prompt.commit == "fallback"
            assert prompt.metadata == {"test": "value"}
            assert prompt.type == PromptType.MUSTACHE
            assert prompt.__internal_api__prompt_id__ == "fallback-test-prompt"
            assert prompt.__internal_api__version_id__ == "fallback-test-prompt-version"

    def test_prompt_constructor__retry_error__graceful_fallback(self):
        """Test that Prompt constructor gracefully handles RetryError (after exhausting retries)."""
        # Arrange
        api_error = rest_api_core.ApiError(
            status_code=502,
            body="Bad Gateway",
            headers={}
        )
        
        # Create a RetryError with the API error as the last attempt
        attempt = tenacity.AttemptManager(1)
        attempt.exception = lambda: api_error
        retry_error = tenacity.RetryError(attempt)
        
        with mock.patch('opik.api_objects.opik_client.get_client_cached') as mock_client:
            mock_client.return_value.create_prompt.side_effect = retry_error
            
            # Act - Create prompt (should not raise an exception)
            prompt = opik_prompt.Prompt(
                name="test-prompt-retry",
                prompt="Hello {{name}}",
                type=PromptType.JINJA2
            )
            
            # Assert - Prompt should be created in fallback mode
            assert prompt.name == "test-prompt-retry"
            assert prompt.prompt == "Hello {{name}}"
            assert prompt.commit == "fallback"
            assert prompt.type == PromptType.JINJA2
            assert prompt.__internal_api__prompt_id__ == "fallback-test-prompt-retry"
            assert prompt.__internal_api__version_id__ == "fallback-test-prompt-retry-version"

    def test_prompt_constructor__can_still_format_in_fallback_mode(self):
        """Test that Prompt can still format templates even in fallback mode."""
        # Arrange
        api_error = rest_api_core.ApiError(
            status_code=503,
            body="Service Unavailable",
            headers={}
        )
        
        with mock.patch('opik.api_objects.opik_client.get_client_cached') as mock_client:
            mock_client.return_value.create_prompt.side_effect = api_error
            
            # Act
            prompt = opik_prompt.Prompt(
                name="test-prompt",
                prompt="Hello {{name}}, welcome to {{city}}!"
            )
            
            result = prompt.format(name="Alice", city="Wonderland")
            
            # Assert - Formatting should work normally
            assert result == "Hello Alice, welcome to Wonderland!"

    def test_prompt_constructor__different_api_errors__all_handled_gracefully(self):
        """Test that various API error status codes are handled gracefully."""
        error_codes = [500, 502, 503, 504, 429, 408]
        
        for status_code in error_codes:
            api_error = rest_api_core.ApiError(
                status_code=status_code,
                body=f"Server Error {status_code}",
                headers={}
            )
            
            with mock.patch('opik.api_objects.opik_client.get_client_cached') as mock_client:
                mock_client.return_value.create_prompt.side_effect = api_error
                
                # Act - Should not raise an exception
                prompt = opik_prompt.Prompt(
                    name=f"test-prompt-{status_code}",
                    prompt="Test prompt"
                )
                
                # Assert - Should always create fallback prompt
                assert prompt.commit == "fallback"
                assert prompt.name == f"test-prompt-{status_code}"

    def test_prompt_constructor__successful_creation__no_fallback(self):
        """Test that when API works, normal prompt creation occurs (no fallback)."""
        # Arrange
        mock_prompt = mock.Mock()
        mock_prompt.name = "test-prompt"
        mock_prompt.prompt = "Hello {{name}}"
        mock_prompt.commit = "abc123"
        mock_prompt.metadata = {"test": "value"}
        mock_prompt.type = PromptType.MUSTACHE
        mock_prompt.__internal_api__prompt_id__ = "real-prompt-id"
        mock_prompt.__internal_api__version_id__ = "real-version-id"
        
        with mock.patch('opik.api_objects.opik_client.get_client_cached') as mock_client:
            mock_client.return_value.create_prompt.return_value = mock_prompt
            
            # Act
            prompt = opik_prompt.Prompt(
                name="test-prompt",
                prompt="Hello {{name}}",
                metadata={"test": "value"}
            )
            
            # Assert - Should use real values, not fallback
            assert prompt.name == "test-prompt"
            assert prompt.prompt == "Hello {{name}}"
            assert prompt.commit == "abc123"  # Not "fallback"
            assert prompt.metadata == {"test": "value"}
            assert prompt.__internal_api__prompt_id__ == "real-prompt-id"
            assert prompt.__internal_api__version_id__ == "real-version-id"

    def test_prompt_constructor__no_exceptions_thrown_on_errors(self):
        """Test that API errors don't cause exceptions to bubble up to user code."""
        error_scenarios = [
            rest_api_core.ApiError(status_code=502, body="Bad Gateway", headers={}),
            rest_api_core.ApiError(status_code=500, body="Internal Server Error", headers={}),
        ]
        
        # Add RetryError scenario
        api_error = rest_api_core.ApiError(status_code=502, body="Bad Gateway", headers={})
        attempt = tenacity.AttemptManager(1)
        attempt.exception = lambda: api_error
        retry_error = tenacity.RetryError(attempt)
        error_scenarios.append(retry_error)
        
        for i, error in enumerate(error_scenarios):
            with mock.patch('opik.api_objects.opik_client.get_client_cached') as mock_client:
                mock_client.return_value.create_prompt.side_effect = error
                
                # Act & Assert - Should not raise any exception
                try:
                    prompt = opik_prompt.Prompt(
                        name=f"test-prompt-{i}",
                        prompt="Test prompt"
                    )
                    # If we get here, the error was handled gracefully
                    assert prompt.commit == "fallback"
                    assert prompt.name == f"test-prompt-{i}"
                except Exception as e:
                    pytest.fail(f"Unexpected exception raised: {e}")

    def test_prompt_constructor__error_logging_functionality(self):
        """Test that error logging is working by checking the logger is called."""
        api_error = rest_api_core.ApiError(
            status_code=502,
            body="Bad Gateway",
            headers={}
        )
        
        with mock.patch('opik.api_objects.opik_client.get_client_cached') as mock_client, \
             mock.patch('opik.api_objects.prompt.prompt.LOGGER') as mock_logger:
            
            mock_client.return_value.create_prompt.side_effect = api_error
            
            # Act
            opik_prompt.Prompt(
                name="monitoring-test",
                prompt="Test prompt"
            )
            
            # Assert - Logger should have been called with error
            mock_logger.error.assert_called_once()
            call_args = mock_logger.error.call_args
            assert "Failed to create prompt via Opik API, falling back to local prompt" in call_args[0][0]
            assert "monitoring-test" in call_args[0][1] 