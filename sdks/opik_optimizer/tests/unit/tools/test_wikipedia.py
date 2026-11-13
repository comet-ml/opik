"""Unit tests for Wikipedia search tools."""

from unittest.mock import Mock, patch

import pytest

from opik_optimizer.utils.tools.wikipedia import (
    search_wikipedia,
    _search_wikipedia_api,
    _search_wikipedia_colbert,
)

pytestmark = pytest.mark.usefixtures("suppress_expected_optimizer_warnings")


class TestSearchWikipediaAPI:
    """Tests for Wikipedia API search."""

    @patch("opik_optimizer.utils.tools.wikipedia.requests.get")
    def test_api_search_success(self, mock_get: Mock) -> None:
        """Test successful API search."""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "query": {
                "search": [
                    {
                        "title": "Python (programming language)",
                        "snippet": "Python is a <span>high-level</span> programming language",
                    }
                ]
            }
        }
        mock_get.return_value = mock_response

        results = _search_wikipedia_api("Python", k=1)

        assert len(results) == 1
        assert "Python (programming language)" in results[0]
        assert "high-level" in results[0]
        # HTML tags should be stripped
        assert "<span>" not in results[0]


class TestSearchWikipediaColBERT:
    """Tests for ColBERT search."""

    @patch("opik_optimizer.utils.tools.wikipedia._search_wikipedia_api")
    def test_colbert_not_available_fallback(self, mock_api: Mock) -> None:
        """Test fallback to API when ColBERT not available."""
        mock_api.return_value = ["fallback result"]

        # ColBERT module won't be available in most test environments
        results = _search_wikipedia_colbert("test", k=1)

        assert results == ["fallback result"]
        mock_api.assert_called_once_with("test", k=1)


class TestSearchWikipediaUnified:
    """Tests for unified search_wikipedia function."""

    def test_invalid_search_type(self) -> None:
        """Test invalid search type raises error."""
        with pytest.raises(ValueError, match="Invalid search_type.*Must be"):
            search_wikipedia("test", search_type="invalid")  # type: ignore
