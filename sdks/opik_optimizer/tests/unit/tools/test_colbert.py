"""Tests for ColBERT utility module."""

import copy
from collections.abc import Iterator
from unittest.mock import MagicMock, patch

import pytest

from opik_optimizer.utils.tools.colbert import (
    ColBERTv2,
    dotdict,
    colbertv2_get_request,
    colbertv2_post_request,
    _create_session_with_retries,
)


def _json_response(payload: dict) -> MagicMock:
    response = MagicMock()
    response.json.return_value = payload
    return response


@pytest.fixture
def mocked_requests_session() -> Iterator[MagicMock]:
    """
    Patch `requests.Session` construction inside the ColBERT module.

    Tests can then configure `.get` / `.post` return values or side effects without
    repeating the same patch boilerplate.
    """
    with patch("opik_optimizer.utils.tools.colbert.requests.Session") as session_cls:
        session = MagicMock()
        session_cls.return_value = session
        yield session


class TestDotDict:
    """Tests for dotdict helper class."""

    def test_attribute_access(self) -> None:
        d = dotdict({"name": "test", "value": 42})
        assert d.name == "test"
        assert d.value == 42

    def test_attribute_assignment(self) -> None:
        d = dotdict()
        d.name = "test"
        d.value = 42
        assert d["name"] == "test"
        assert d["value"] == 42

    def test_attribute_deletion(self) -> None:
        d = dotdict({"name": "test", "value": 42})
        del d.name
        assert "name" not in d
        assert d.value == 42

    def test_raises_attribute_error_for_missing_key(self) -> None:
        d = dotdict({"name": "test"})
        with pytest.raises(AttributeError, match="has no attribute"):
            _ = d.missing_key

    def test_dunder_attribute_passthrough(self) -> None:
        d = dotdict()
        # Should not raise - dunder attributes use parent behavior
        repr(d)
        str(d)

    def test_deepcopy(self) -> None:
        d = dotdict({"nested": {"value": 42}})
        copied = copy.deepcopy(d)
        assert copied.nested["value"] == 42
        # Modify original - copy should be independent
        d.nested["value"] = 100
        assert copied.nested["value"] == 42

    def test_dict_operations(self) -> None:
        d = dotdict({"a": 1, "b": 2})
        assert len(d) == 2
        assert list(d.keys()) == ["a", "b"]
        assert list(d.values()) == [1, 2]


class TestCreateSessionWithRetries:
    """Tests for _create_session_with_retries function."""

    def test_creates_session(self) -> None:
        session = _create_session_with_retries()
        assert session is not None
        # Check adapters are mounted
        assert "http://" in session.adapters
        assert "https://" in session.adapters

    def test_custom_max_retries(self) -> None:
        session = _create_session_with_retries(max_retries=10)
        assert session is not None


class TestColbertv2GetRequest:
    """Tests for colbertv2_get_request function."""

    def test_successful_request(self, mocked_requests_session: MagicMock) -> None:
        mock_response = {
            "topk": [
                {"text": "Result 1", "score": 0.9},
                {"text": "Result 2", "score": 0.8},
            ]
        }
        mocked_requests_session.get.return_value = _json_response(mock_response)

        results = colbertv2_get_request(
            url="http://localhost:8000/search",
            query="test query",
            k=2,
        )

        assert len(results) == 2
        assert results[0]["text"] == "Result 1"
        assert results[0]["long_text"] == "Result 1"

    def test_raises_on_k_greater_than_100(self) -> None:
        with pytest.raises(AssertionError, match="k <= 100"):
            colbertv2_get_request(
                url="http://localhost:8000/search",
                query="test query",
                k=101,
            )

    def test_handles_server_error_response(
        self, mocked_requests_session: MagicMock
    ) -> None:
        mock_response = {
            "error": True,
            "message": "Internal server error",
        }
        mocked_requests_session.get.return_value = _json_response(mock_response)

        with pytest.raises(Exception, match="ColBERTv2 server error"):
            colbertv2_get_request(
                url="http://localhost:8000/search",
                query="test query",
                k=5,
            )

    def test_retries_on_connection_error(
        self, mocked_requests_session: MagicMock
    ) -> None:
        mock_response_error = {
            "error": True,
            "message": "Cannot connect to host",
        }
        mock_response_success = {"topk": [{"text": "Result", "score": 0.9}]}
        mocked_requests_session.get.side_effect = [
            _json_response(mock_response_error),
            _json_response(mock_response_success),
        ]

        with patch("opik_optimizer.utils.tools.colbert.time.sleep"):
            results = colbertv2_get_request(
                url="http://localhost:8000/search",
                query="test query",
                k=1,
            )

        assert len(results) == 1

    def test_handles_unexpected_response_format(
        self, mocked_requests_session: MagicMock
    ) -> None:
        mock_response = {"unexpected_key": "value"}
        mocked_requests_session.get.return_value = _json_response(mock_response)

        with pytest.raises(Exception, match="Unexpected response format"):
            colbertv2_get_request(
                url="http://localhost:8000/search",
                query="test query",
                k=5,
            )

    def test_handles_request_exception(
        self, mocked_requests_session: MagicMock
    ) -> None:
        import requests

        mocked_requests_session.get.side_effect = requests.RequestException(
            "Network error"
        )

        with patch("opik_optimizer.utils.tools.colbert.time.sleep"):
            with pytest.raises(Exception, match="ColBERTv2 request failed"):
                colbertv2_get_request(
                    url="http://localhost:8000/search",
                    query="test query",
                    k=5,
                    max_retries=2,
                )


class TestColbertv2PostRequest:
    """Tests for colbertv2_post_request function."""

    def test_successful_post_request(self, mocked_requests_session: MagicMock) -> None:
        mock_response = {
            "topk": [
                {"text": "Result 1", "score": 0.9},
                {"text": "Result 2", "score": 0.8},
            ]
        }
        mocked_requests_session.post.return_value = _json_response(mock_response)

        results = colbertv2_post_request(
            url="http://localhost:8000/search",
            query="test query",
            k=2,
        )

        assert len(results) == 2
        assert results[0]["text"] == "Result 1"

    def test_handles_server_error_response(
        self, mocked_requests_session: MagicMock
    ) -> None:
        mock_response = {
            "error": True,
            "message": "Bad request",
        }
        mocked_requests_session.post.return_value = _json_response(mock_response)

        with pytest.raises(Exception, match="ColBERTv2 server error"):
            colbertv2_post_request(
                url="http://localhost:8000/search",
                query="test query",
                k=5,
            )

    def test_retries_on_connection_refused(
        self, mocked_requests_session: MagicMock
    ) -> None:
        mock_response_error = {
            "error": True,
            "message": "Connection refused",
        }
        mocked_requests_session.post.return_value = _json_response(mock_response_error)

        with patch("opik_optimizer.utils.tools.colbert.time.sleep"):
            with pytest.raises(Exception, match="ColBERTv2 server error"):
                colbertv2_post_request(
                    url="http://localhost:8000/search",
                    query="test query",
                    k=5,
                    max_retries=2,
                )


class TestColBERTv2Class:
    """Tests for ColBERTv2 wrapper class."""

    def test_initialization_with_defaults(self) -> None:
        client = ColBERTv2()
        assert client.url == "http://0.0.0.0"
        assert client.post_requests is False

    def test_initialization_with_port(self) -> None:
        client = ColBERTv2(url="http://localhost", port=8080)
        assert client.url == "http://localhost:8080"

    def test_initialization_with_post_requests(self) -> None:
        client = ColBERTv2(post_requests=True)
        assert client.post_requests is True

    def test_call_with_get_request(self, mocked_requests_session: MagicMock) -> None:
        mock_response = {
            "topk": [
                {"text": "Result 1", "score": 0.9},
                {"text": "Result 2", "score": 0.8},
            ]
        }
        mocked_requests_session.get.return_value = _json_response(mock_response)

        client = ColBERTv2(post_requests=False)
        results = client(query="test", k=2)

        assert len(results) == 2
        assert isinstance(results[0], dotdict)

    def test_call_with_post_request(self, mocked_requests_session: MagicMock) -> None:
        mock_response = {
            "topk": [
                {"text": "Result 1", "score": 0.9},
            ]
        }
        mocked_requests_session.post.return_value = _json_response(mock_response)

        client = ColBERTv2(post_requests=True)
        results = client(query="test", k=1)

        assert len(results) == 1
        mocked_requests_session.post.assert_called_once()

    def test_call_with_simplify_returns_strings(
        self, mocked_requests_session: MagicMock
    ) -> None:
        mock_response = {
            "topk": [
                {"text": "Result 1", "score": 0.9, "long_text": "Result 1 full"},
                {"text": "Result 2", "score": 0.8, "long_text": "Result 2 full"},
            ]
        }
        mocked_requests_session.get.return_value = _json_response(mock_response)

        client = ColBERTv2()
        results = client(query="test", k=2, simplify=True)

        assert len(results) == 2
        assert isinstance(results[0], str)

    def test_call_with_custom_max_retries(
        self, mocked_requests_session: MagicMock
    ) -> None:
        mock_response = {"topk": [{"text": "Result", "score": 0.9}]}
        mocked_requests_session.get.return_value = _json_response(mock_response)

        client = ColBERTv2()
        results = client(query="test", k=1, max_retries=10)

        assert len(results) == 1
