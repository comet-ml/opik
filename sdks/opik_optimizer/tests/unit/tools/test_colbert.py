"""Tests for ColBERT utility module."""

from collections.abc import Iterator
from unittest.mock import MagicMock, patch

import pytest

from opik_optimizer.utils.tools.colbert import (
    colbertv2_get_request,
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
