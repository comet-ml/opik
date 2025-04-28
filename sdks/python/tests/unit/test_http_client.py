import gzip
import json

from opik import httpx_client


def test_json_compression__compressed_if_json(respx_mock):
    rx_url = "https://example.com"
    respx_mock.post(rx_url).respond(200)

    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )

    json_data = {"a": 1}
    client.post(rx_url, json=json_data)

    assert len(respx_mock.calls) == 1
    request = respx_mock.calls[0].request
    content = gzip.decompress(request.read())

    assert json.loads(content) == json_data


def test_json_compression__uncompressed_if_not_json(respx_mock):
    rx_url = "https://example.com"
    respx_mock.post(rx_url).respond(200)

    client = httpx_client.get(
        None, None, check_tls_certificate=False, compress_json_requests=True
    )

    txt_data = b"this is not json"
    client.post(rx_url, content=txt_data)

    assert len(respx_mock.calls) == 1
    content = respx_mock.calls[0].request.read()

    assert content == txt_data
