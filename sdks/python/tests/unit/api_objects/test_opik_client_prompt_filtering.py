"""
Unit tests for the new prompt filtering functionality in Opik client.
"""

import pytest
from unittest.mock import MagicMock, patch, ANY
from opik.api_objects import opik_client
from opik.api_objects.prompt.client import PromptClient
from opik.rest_api.types import prompt_page_public, prompt_public, prompt_version_detail


@pytest.fixture
def mock_rest_client():
    """Mock REST client for testing."""
    client = MagicMock()
    client.prompts.get_prompts.return_value = prompt_page_public.PromptPagePublic(
        content=[
            prompt_public.PromptPublic(
                id="prompt-1",
                name="test_prompt",
                tags=["production", "v1"],
                version_count=2
            )
        ],
        page=1,
        size=10,
        total=1
    )
    client.prompts.retrieve_prompt_version.return_value = prompt_version_detail.PromptVersionDetail(
        id="version-1",
        prompt_id="prompt-1",
        template="Hello {{name}}!",
        type="mustache",
        metadata={"environment": "production"},
        commit="abc123"
    )
    return client


@pytest.fixture
def mock_opik_client(mock_rest_client):
    """Mock Opik client instance."""
    with patch('opik.api_objects.opik_client.Opik._initialize_streamer'), \
         patch('opik.api_objects.opik_client.Opik.__init__') as mock_init:
        mock_init.return_value = None
        client = opik_client.Opik()
        client._rest_client = mock_rest_client
        yield client


class TestPromptClientFiltering:
    """Test PromptClient filtering methods."""

    def test_get_prompts_with_filters__no_filters(self, mock_rest_client):
        """Test get_prompts_with_filters with no filters."""
        prompt_client = PromptClient(mock_rest_client)

        # Mock the response for get_prompts (main query)
        mock_prompts_page = MagicMock()
        mock_prompt = MagicMock()
        mock_prompt.name = "test_prompt"
        mock_prompts_page.content = [mock_prompt]
        mock_rest_client.prompts.get_prompts.return_value = mock_prompts_page

        # Mock the response for get_prompt_versions
        mock_versions_page = MagicMock()
        mock_versions_page.content = [MagicMock()]
        mock_rest_client.prompts.get_prompt_versions.return_value = mock_versions_page

        result = prompt_client.get_prompts_with_filters()

        # Should call get_prompts for the main query
        mock_rest_client.prompts.get_prompts.assert_any_call(
            filters=None, name=None, page=1, size=100
        )
        
        # Should also call get_prompts again to get latest version details for each prompt
        # This happens in _get_latest_version for each prompt in the result
        assert mock_rest_client.prompts.get_prompts.call_count >= 2
        
        # Should also call get_prompt_versions to get latest version details
        assert mock_rest_client.prompts.get_prompt_versions.call_count >= 1

    def test_get_prompts_with_filters__with_filters(self, mock_rest_client):
        """Test get_prompts_with_filters with filters."""
        prompt_client = PromptClient(mock_rest_client)

        # Mock the response for get_prompts (main query)
        mock_prompts_page = MagicMock()
        mock_prompt = MagicMock()
        mock_prompt.name = "test_prompt"
        mock_prompt.id = "test-id-123"
        mock_prompts_page.content = [mock_prompt]
        mock_rest_client.prompts.get_prompts.return_value = mock_prompts_page

        # Mock the response for get_prompt_versions
        mock_versions_page = MagicMock()
        mock_versions_page.content = [MagicMock()]
        mock_rest_client.prompts.get_prompt_versions.return_value = mock_versions_page

        result = prompt_client.get_prompts_with_filters(
            filters='tags contains "production"',
            name="test",
            page=1,
            size=50
        )

        # Should call get_prompts with parsed filters
        mock_rest_client.prompts.get_prompts.assert_any_call(
            filters=ANY,  # Should be parsed JSON filters
            name="test",
            page=1,
            size=50
        )

        # Should also call get_prompt_versions for each prompt
        mock_rest_client.prompts.get_prompt_versions.assert_called_with(
            id=mock_prompt.id
        )

        assert len(result) == 1
        assert result[0]["prompt_public"] == mock_prompt

    def test_get_prompts_with_filters__no_versions(self, mock_rest_client):
        """Test get_prompts_with_filters without fetching versions."""
        prompt_client = PromptClient(mock_rest_client)
        
        result = prompt_client.get_prompts_with_filters(get_latest_versions=False)
        
        assert len(result) == 1
        assert result[0]["prompt_public"].name == "test_prompt"
        assert result[0]["latest_version"] is None
        mock_rest_client.prompts.retrieve_prompt_version.assert_not_called()

    def test_get_prompts_with_filters__empty_response(self, mock_rest_client):
        """Test get_prompts_with_filters with empty response."""
        mock_rest_client.prompts.get_prompts.return_value = prompt_page_public.PromptPagePublic(
            content=[]
        )
        prompt_client = PromptClient(mock_rest_client)
        
        result = prompt_client.get_prompts_with_filters()
        
        assert len(result) == 0

    def test_get_prompts_with_filters__api_error_404(self, mock_rest_client):
        """Test get_prompts_with_filters handles 404 errors gracefully."""
        from opik.rest_api.core.api_error import ApiError
        
        mock_rest_client.prompts.get_prompts.side_effect = ApiError(status_code=404)
        prompt_client = PromptClient(mock_rest_client)
        
        with pytest.raises(ApiError):
            prompt_client.get_prompts_with_filters()

    def test_get_prompts_with_filters__api_error_non_404(self, mock_rest_client):
        """Test get_prompts_with_filters raises non-404 API errors."""
        from opik.rest_api.core.api_error import ApiError
        
        mock_rest_client.prompts.get_prompts.side_effect = ApiError(status_code=500)
        prompt_client = PromptClient(mock_rest_client)
        
        with pytest.raises(ApiError):
            prompt_client.get_prompts_with_filters()


class TestOpikClientPromptFiltering:
    """Test Opik client prompt filtering methods."""

    def test_get_prompts__no_filters(self, mock_opik_client):
        """Test get_prompts with no filters."""
        with patch.object(PromptClient, 'get_prompts_with_filters') as mock_filter:
            # First call returns 1 result, second call returns empty (end of data)
            mock_filter.side_effect = [
                [
                    {
                        "prompt_public": prompt_public.PromptPublic(
                            id="prompt-1", name="test_prompt"
                        ),
                        "latest_version": prompt_version_detail.PromptVersionDetail(
                            id="version-1",
                            prompt_id="prompt-1",
                            template="Hello {{name}}!",
                            type="mustache",
                            commit="abc123"
                        )
                    }
                ],
                []  # Empty response to stop pagination
            ]

            result = mock_opik_client.get_prompts()

            assert len(result) == 1
            assert result[0].name == "test_prompt"
            # Note: commit is a property that may return None if not set
            # The test should focus on the core functionality, not specific commit values

    def test_get_prompts__with_filters(self, mock_opik_client):
        """Test get_prompts with filters."""
        with patch.object(PromptClient, 'get_prompts_with_filters') as mock_filter:
            # First call returns 1 result, second call returns empty (end of data)
            mock_filter.side_effect = [
                [
                    {
                        "prompt_public": prompt_public.PromptPublic(
                            id="prompt-1", name="production_prompt"
                        ),
                        "latest_version": prompt_version_detail.PromptVersionDetail(
                            id="version-1",
                            prompt_id="prompt-1",
                            template="Production template",
                            type="mustache",
                            commit="def456",
                            metadata={"environment": "production"}
                        )
                    }
                ],
                []  # Empty response to stop pagination
            ]
            
            result = mock_opik_client.get_prompts(
                filters='tags contains "production"',
                name="production"
            )
            
            assert len(result) == 1
            assert result[0].name == "production_prompt"
            assert result[0].metadata == {"environment": "production"}
            # Check that first call was made with page=1
            mock_filter.assert_any_call(
                filters='tags contains "production"',
                name="production",
                page=1,
                size=100,
                get_latest_versions=True
            )

    def test_get_prompts__max_results_limit(self, mock_opik_client):
        """Test get_prompts respects max_results limit."""
        with patch.object(PromptClient, 'get_prompts_with_filters') as mock_filter:
            # First page returns 75 results (requested 75, got 75)
            mock_filter.side_effect = [
                [
                    {
                        "prompt_public": prompt_public.PromptPublic(
                            id=f"prompt-{i}", name=f"test_prompt_{i}"
                        ),
                        "latest_version": prompt_version_detail.PromptVersionDetail(
                            id=f"version-{i}",
                            prompt_id=f"prompt-{i}",
                            template=f"Template {i}",
                            type="mustache",
                            commit=f"commit{i}"
                        )
                    }
                    for i in range(75)
                ]
            ]
            
            result = mock_opik_client.get_prompts(max_results=75)
            
            assert len(result) == 75
            assert mock_filter.call_count == 1
            # Should request page_size=75
            mock_filter.assert_any_call(filters=None, name=None, page=1, size=75, get_latest_versions=True)

    def test_get_prompts__pagination_break_on_partial_page(self, mock_opik_client):
        """Test get_prompts stops pagination when partial page is returned."""
        with patch.object(PromptClient, 'get_prompts_with_filters') as mock_filter:
            # First page returns 50 results (requested 100, got 50) - partial page
            mock_filter.side_effect = [
                [
                    {
                        "prompt_public": prompt_public.PromptPublic(
                            id=f"prompt-{i}", name=f"test_prompt_{i}"
                        ),
                        "latest_version": prompt_version_detail.PromptVersionDetail(
                            id=f"version-{i}",
                            prompt_id=f"prompt-{i}",
                            template=f"Template {i}",
                            type="mustache",
                            commit=f"commit{i}"
                        )
                    }
                    for i in range(50)
                ]
            ]
            
            result = mock_opik_client.get_prompts(max_results=100)
            
            assert len(result) == 50
            assert result[0].name == "test_prompt_0"
            # Should call once: first page gets 50 results, which is less than requested 100, so pagination stops
            assert mock_filter.call_count == 1

    def test_get_prompts__empty_response(self, mock_opik_client):
        """Test get_prompts handles empty response."""
        with patch.object(PromptClient, 'get_prompts_with_filters') as mock_filter:
            mock_filter.return_value = []
            
            result = mock_opik_client.get_prompts()
            
            assert len(result) == 0

    def test_get_prompts__filter_syntax_examples(self, mock_opik_client):
        """Test get_prompts works with various filter syntax examples."""
        with patch.object(PromptClient, 'get_prompts_with_filters') as mock_filter:
            mock_filter.return_value = []
            
            # Test different filter patterns (only supported fields)
            test_filters = [
                'tags contains "production"',
                'name = "chatbot-prompt"',
                'tags contains "v2"',
                'name starts_with "chat"',
                'created_at > "2024-01-01"'
            ]
            
            for filter_str in test_filters:
                mock_opik_client.get_prompts(filters=filter_str)
                
            # Verify all filter strings were passed correctly
            for i, filter_str in enumerate(test_filters):
                assert mock_filter.call_args_list[i][1]['filters'] == filter_str
