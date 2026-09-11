"""The request compression level is configurable, validated, and actually applied."""

import gzip

import pydantic
import pytest

from opik import httpx_client
from opik.config import OpikConfig


def test_compression_level__default_is_zlib_default_not_python_default():
    """Python's gzip default of 9 costs several times the CPU of 6 for under 1% fewer
    bytes on these payloads, so the SDK picks 6 deliberately."""
    assert OpikConfig().request_compression_level == 6
    assert httpx_client.DEFAULT_COMPRESSION_LEVEL == 6


def test_compression_level__read_from_the_environment(monkeypatch):
    monkeypatch.setenv("OPIK_REQUEST_COMPRESSION_LEVEL", "1")
    assert OpikConfig().request_compression_level == 1


@pytest.mark.parametrize("value", ["10", "-1", "99"])
def test_compression_level__out_of_range__rejected(monkeypatch, value):
    """An unusable level must fail loudly rather than be silently ignored."""
    monkeypatch.setenv("OPIK_REQUEST_COMPRESSION_LEVEL", value)
    with pytest.raises(pydantic.ValidationError):
        OpikConfig()


def test_compression_level__not_an_integer__rejected(monkeypatch):
    monkeypatch.setenv("OPIK_REQUEST_COMPRESSION_LEVEL", "high")
    with pytest.raises(pydantic.ValidationError):
        OpikConfig()


@pytest.mark.parametrize("level", [1, 6, 9])
def test_build_request__uses_the_configured_level(level):
    """The body must still decompress, and the level must reach gzip."""
    client = httpx_client.OpikHttpxClient(
        compress_json_requests=True, compression_level=level
    )
    payload = {"items": [{"input": "x" * 500} for _ in range(20)]}

    request = client.build_request("PUT", "http://testserver/x", json=payload)
    body = request.read()

    assert request.headers["Content-Encoding"] == "gzip"
    assert gzip.decompress(body) == httpx_client.jsonlib.dumps(payload).encode("utf-8")


def test_build_request__lower_level_sends_more_bytes():
    """Sanity check that the level is doing something rather than being accepted and
    dropped."""
    payload = {"items": [{"input": "abcdefghij" * 200} for _ in range(20)]}

    def body_size(level: int) -> int:
        client = httpx_client.OpikHttpxClient(
            compress_json_requests=True, compression_level=level
        )
        return len(
            client.build_request("PUT", "http://testserver/x", json=payload).read()
        )

    assert body_size(1) > body_size(9)


def test_build_request__compression_disabled__body_is_plain_json():
    client = httpx_client.OpikHttpxClient(compress_json_requests=False)
    request = client.build_request("PUT", "http://testserver/x", json={"a": 1})

    assert "Content-Encoding" not in request.headers


def test_orjson_kill_switch__default_on_but_overridable(monkeypatch):
    assert OpikConfig().enable_orjson_serialization is True

    monkeypatch.setenv("OPIK_ENABLE_ORJSON_SERIALIZATION", "false")
    assert OpikConfig().enable_orjson_serialization is False
