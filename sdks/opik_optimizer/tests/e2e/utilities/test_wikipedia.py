"""End-to-end test for Wikipedia API search."""

from opik_optimizer.utils.tools.wikipedia import search_wikipedia


class TestWikipediaAPIE2E:
    """End-to-end tests for Wikipedia API."""

    def test_api_search_basic_query(self) -> None:
        """Test basic Wikipedia API search with a real query."""
        # Use a well-known topic that should always return results
        results = search_wikipedia(
            "Python programming language", search_type="api", n=1
        )

        # Should return at least one result
        assert len(results) >= 1
        assert isinstance(results[0], str)
        assert len(results[0]) > 0

        # Result should contain the query term or related terms
        result_lower = results[0].lower()
        assert "python" in result_lower or "programming" in result_lower

    def test_api_search_multiple_results(self) -> None:
        """Test API search returns multiple results."""
        results = search_wikipedia("Machine learning", search_type="api", n=3)

        # Should return up to 3 results
        assert len(results) <= 3
        assert all(isinstance(r, str) for r in results)
        assert all(len(r) > 0 for r in results)

    def test_api_search_default_parameters(self) -> None:
        """Test API search with default parameters."""
        # Default should be search_type="api", n=3
        results = search_wikipedia("Artificial intelligence")

        assert len(results) <= 3
        assert all(isinstance(r, str) for r in results)
